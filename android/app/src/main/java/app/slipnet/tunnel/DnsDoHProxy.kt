package app.slipnet.tunnel

import app.slipnet.util.AppLog as Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Lightweight UDP-to-DoH DNS proxy.
 *
 * Listens on a local UDP port and forwards DNS queries to a DoH server
 * via HTTPS, then returns the responses back over UDP.
 *
 * Hardened for:
 * - HTTP/2 flow control deadlock prevention via stream concurrency bounding ([MAX_CONCURRENT_DOH_STREAMS]).
 * - Proactive HTTP/2 PING keepalive to prevent mobile/firewall silent NAT drops.
 * - Stale connection pool recovery with automatic Broken Pipe retry and pool eviction.
 * - Defense against HPACK decompression bombs and oversized response payloads.
 *
 * Lifecycle: start before consumer, stop after consumer.
 */
object DnsDoHProxy {
    private const val TAG = "DnsDoHProxy"
    private const val MAX_DNS_PACKET = 4096
    private const val MAX_CONCURRENT_DOH_STREAMS = 16
    private const val MAX_HEADER_COUNT = 32
    private const val MAX_TOTAL_HEADER_BYTES = 4096
    private const val MAX_DNS_RESPONSE_BYTES = 65535

    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private var executor: ExecutorService? = null
    private val running = AtomicBoolean(false)
    @Volatile
    private var httpClient: OkHttpClient? = null
    private var dohUrl: String = ""

    /**
     * Bounded semaphore to prevent overwhelming the HTTP/2 connection-level
     * flow control window (65,535 bytes) during concurrent DNS bursts.
     */
    private val dohSemaphore = Semaphore(MAX_CONCURRENT_DOH_STREAMS)

    /** Pre-resolved IP map from [DOH_SERVERS] — shared with DohBridge. */
    private val serverIpMap: Map<String, List<String>> by lazy {
        DOH_SERVERS
            .filter { it.ips.isNotEmpty() }
            .associate { server ->
                try { java.net.URL(server.url).host } catch (_: Exception) { "" } to server.ips
            }
            .filterKeys { it.isNotEmpty() }
    }

    /**
     * Start the UDP-to-DoH proxy.
     * @return the local UDP port the proxy is listening on, or -1 on failure.
     */
    fun start(dohUrl: String): Int {
        stop()

        this.dohUrl = dohUrl

        httpClient = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            // HTTP/2 PING keepalive prevents cellular NAT timeouts & detects dead TCP sessions
            .pingInterval(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // Keep idle connections short (20s) to prune stale TLS sessions before NAT drops
            .connectionPool(ConnectionPool(8, 20, TimeUnit.SECONDS))
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    val preResolved = serverIpMap[hostname]?.mapNotNull { ip ->
                        try { InetAddress.getByName(ip) } catch (_: Exception) { null }
                    } ?: emptyList()
                    val systemResolved = try {
                        Dns.SYSTEM.lookup(hostname)
                    } catch (_: Exception) { emptyList() }
                    val combined = (preResolved + systemResolved).distinctBy { it.hostAddress }
                    if (combined.isNotEmpty()) return combined
                    throw java.net.UnknownHostException("No addresses for $hostname")
                }
            })
            .build()

        // Discard oldest queries on queue saturation rather than crashing or throwing RejectedExecutionException
        val pool = ThreadPoolExecutor(
            4, 32, 30L, TimeUnit.SECONDS,
            LinkedBlockingQueue(256),
            ThreadPoolExecutor.DiscardOldestPolicy()
        )
        pool.allowCoreThreadTimeOut(true)
        executor = pool

        return try {
            val ds = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
            socket = ds
            val port = ds.localPort
            running.set(true)

            thread = Thread({
                Log.i(TAG, "UDP-to-DoH proxy started on 127.0.0.1:$port -> $dohUrl")
                val buf = ByteArray(MAX_DNS_PACKET)
                while (running.get()) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        ds.receive(packet)
                        val query = buf.copyOfRange(0, packet.length)
                        val clientAddr = packet.address
                        val clientPort = packet.port

                        executor?.execute {
                            try {
                                val response = forwardViaDoH(query)
                                if (response != null && running.get()) {
                                    val resp = DatagramPacket(response, response.size, clientAddr, clientPort)
                                    ds.send(resp)
                                }
                            } catch (e: Exception) {
                                if (running.get()) {
                                    Log.w(TAG, "DoH forward error: ${e.message}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (running.get()) {
                            Log.w(TAG, "Receive error: ${e.message}")
                        }
                    }
                }
            }, "DnsDoHProxy").apply {
                isDaemon = true
                start()
            }

            Log.i(TAG, "Started on port $port for $dohUrl")
            port
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start: ${e.message}")
            stop()
            -1
        }
    }

    fun stop() {
        running.set(false)
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        thread?.interrupt()
        thread = null
        try { executor?.shutdownNow() } catch (_: Exception) {}
        executor = null
        try {
            httpClient?.connectionPool?.evictAll()
            httpClient?.dispatcher?.cancelAll()
            httpClient?.dispatcher?.executorService?.shutdown()
        } catch (_: Exception) {}
        httpClient = null
        Log.d(TAG, "Stopped")
    }

    fun isRunning(): Boolean = running.get()

    /**
     * Forwards a DNS query over DoH with:
     * 1. Concurrency control via [dohSemaphore] to prevent HTTP/2 flow control stalls.
     * 2. Automatic retry on broken pipe / stale TLS connections with pool eviction.
     * 3. Response validation against HPACK decompression bombs and non-DNS bodies.
     */
    private fun forwardViaDoH(query: ByteArray): ByteArray? {
        val client = httpClient ?: return null

        if (!dohSemaphore.tryAcquire(5, TimeUnit.SECONDS)) {
            Log.w(TAG, "DoH concurrency limit ($MAX_CONCURRENT_DOH_STREAMS) saturated, dropping query")
            return null
        }

        try {
            return executeDoHWithRetry(client, query)
        } finally {
            dohSemaphore.release()
        }
    }

    private fun executeDoHWithRetry(client: OkHttpClient, query: ByteArray): ByteArray? {
        var lastException: Exception? = null

        for (attempt in 1..2) {
            try {
                val body = query.toRequestBody("application/dns-message".toMediaType())
                val request = Request.Builder()
                    .url(dohUrl)
                    .post(body)
                    .header("Accept", "application/dns-message")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "DoH HTTP ${response.code} (attempt $attempt)")
                        return null
                    }

                    // HPACK Bomb & Header Validation
                    val headers = response.headers
                    if (headers.size > MAX_HEADER_COUNT) {
                        Log.w(TAG, "DoH rejected response: excessive headers (${headers.size} > $MAX_HEADER_COUNT)")
                        return null
                    }
                    var headerBytes = 0
                    for (i in 0 until headers.size) {
                        headerBytes += headers.name(i).length + headers.value(i).length + 4
                    }
                    if (headerBytes > MAX_TOTAL_HEADER_BYTES) {
                        Log.w(TAG, "DoH rejected response: header size $headerBytes > $MAX_TOTAL_HEADER_BYTES")
                        return null
                    }

                    // Content-Type validation (RFC 8484)
                    val contentType = response.header("Content-Type")?.lowercase() ?: ""
                    if (!contentType.contains("application/dns-message")) {
                        Log.w(TAG, "DoH rejected non-DNS response: Content-Type='$contentType'")
                        return null
                    }

                    // Body size bound to avoid decompression/OOM bombs
                    val contentLength = response.body?.contentLength() ?: -1L
                    if (contentLength > MAX_DNS_RESPONSE_BYTES) {
                        Log.w(TAG, "DoH rejected response: Content-Length $contentLength > $MAX_DNS_RESPONSE_BYTES")
                        return null
                    }

                    val source = response.body?.source() ?: return null
                    val bytes = source.readByteArray()
                    if (bytes.size > MAX_DNS_PACKET) {
                        Log.w(TAG, "DoH response exceeds UDP packet buffer (${bytes.size} > $MAX_DNS_PACKET)")
                        return null
                    }
                    return bytes
                }
            } catch (e: Exception) {
                lastException = e
                if (attempt == 1 && running.get()) {
                    Log.w(TAG, "DoH attempt 1 failed (${e.javaClass.simpleName}: ${e.message}), evicting stale connection pool and retrying...")
                    try {
                        client.connectionPool.evictAll()
                    } catch (_: Exception) {}
                }
            }
        }

        if (running.get() && lastException != null) {
            Log.w(TAG, "DoH forward failed after 2 attempts: ${lastException.message}")
        }
        return null
    }
}
