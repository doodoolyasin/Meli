package app.slipnet.tunnel

import app.slipnet.util.AppLog as Log
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.SocketFactory
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * JSch [Proxy] that tunnels SSH through an HTTP CONNECT proxy.
 *
 * Supports:
 *  - Custom Host header for CDN facades / header-based routing.
 *  - Optional TLS wrapping after CONNECT succeeds (SSH-over-TLS-over-HTTP-proxy).
 *  - Leak-free socket management on connection or handshake errors.
 *  - Strict socket timeout during CONNECT & TLS negotiation to prevent dangling threads.
 *  - Bounded header & line parsing to protect against infinite loops and memory exhaustion.
 *
 * Flow:
 *   1. TCP connect to [proxyHost]:[proxyPort] with timeout
 *   2. Send CONNECT sshHost:sshPort HTTP/1.1\r\nHost: customHost\r\n\r\n
 *   3. Read HTTP/1.x 200 ...
 *   4. (Optional) TLS handshake with custom SNI
 *   5. JSch runs SSH protocol over the resulting streams
 *
 * @param proxyHost  HTTP proxy address.
 * @param proxyPort  HTTP proxy port.
 * @param customHost Custom Host header value (empty → use target host:port).
 * @param tlsEnabled Wrap the tunneled connection in TLS after CONNECT succeeds.
 * @param tlsSni     SNI hostname for the TLS ClientHello (empty → use target host).
 */
class ProxyHttpConnect(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val customHost: String = "",
    private val tlsEnabled: Boolean = false,
    private val tlsSni: String = ""
) : Proxy {

    companion object {
        private const val TAG = "ProxyHttpConnect"
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val MAX_LINE_LENGTH = 4096
        private const val MAX_HEADERS_COUNT = 64
    }

    private var rawSocket: Socket? = null
    private var activeSocket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    override fun connect(
        socketFactory: SocketFactory?,
        host: String,
        port: Int,
        timeout: Int
    ) {
        Log.i(TAG, "Connecting to HTTP proxy $proxyHost:$proxyPort -> $host:$port")
        if (customHost.isNotBlank()) Log.i(TAG, "  Custom Host header: $customHost")
        if (tlsEnabled) Log.i(TAG, "  TLS enabled (SNI: ${tlsSni.ifBlank { host }})")

        val timeoutMs = if (timeout > 0) timeout else CONNECT_TIMEOUT_MS

        try {
            // Step 1: TCP connect to the proxy with timeout
            val sock = Socket().apply {
                connect(InetSocketAddress(proxyHost, proxyPort), timeoutMs)
                // Enforce read timeout during HTTP/TLS handshake to prevent dangling threads
                soTimeout = timeoutMs
                tcpNoDelay = true
            }
            rawSocket = sock
            activeSocket = sock

            val out = sock.getOutputStream()
            val inp = sock.getInputStream()

            // Step 2: Send HTTP CONNECT request
            val hostHeader = customHost.ifBlank { "$host:$port" }
            val connectReq = "CONNECT $host:$port HTTP/1.1\r\n" +
                    "Host: $hostHeader\r\n" +
                    "Proxy-Connection: keep-alive\r\n" +
                    "\r\n"
            Log.d(TAG, "TX: CONNECT $host:$port (Host: $hostHeader)")
            out.write(connectReq.toByteArray(Charsets.US_ASCII))
            out.flush()

            // Step 3: Read HTTP response status line with length bounds
            val statusLine = readLine(inp)
                ?: throw IOException("HTTP proxy closed connection without response")
            Log.d(TAG, "RX: $statusLine")

            // Expect "HTTP/1.x 200 ..."
            if (!statusLine.startsWith("HTTP/")) {
                throw IOException("HTTP proxy returned invalid response: $statusLine")
            }
            val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull()
                ?: throw IOException("HTTP proxy returned unparseable status: $statusLine")
            if (statusCode !in 200..299) {
                throw IOException("HTTP proxy CONNECT failed: $statusLine")
            }

            // Consume remaining response headers with count limit
            var headerCount = 0
            while (true) {
                val line = readLine(inp) ?: break
                if (line.isEmpty()) break
                headerCount++
                if (headerCount > MAX_HEADERS_COUNT) {
                    throw IOException("HTTP proxy sent too many headers ($headerCount > $MAX_HEADERS_COUNT)")
                }
                Log.d(TAG, "RX header: $line")
            }

            Log.i(TAG, "HTTP CONNECT tunnel established to $host:$port")

            // Step 4: Optional TLS wrapping
            if (tlsEnabled) {
                val sniHost = tlsSni.ifBlank { host }
                Log.i(TAG, "Upgrading to TLS (SNI: $sniHost)")

                val sslFactory = trustAllSslFactory()
                val sslSocket = sslFactory.createSocket(sock, sniHost, port, true) as SSLSocket

                val params = sslSocket.sslParameters
                params.serverNames = listOf(SNIHostName(sniHost))
                params.endpointIdentificationAlgorithm = "HTTPS"
                sslSocket.sslParameters = params
                sslSocket.soTimeout = timeoutMs

                sslSocket.startHandshake()
                Log.i(TAG, "TLS handshake complete (${sslSocket.session.protocol}, ${sslSocket.session.cipherSuite})")
                activeSocket = sslSocket
            }

            // Handshake successfully completed: reset read timeout for SSH streaming
            activeSocket?.soTimeout = 0

            inputStream = activeSocket!!.getInputStream()
            outputStream = activeSocket!!.getOutputStream()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to establish tunnel through HTTP proxy: ${t.message}")
            close() // GUARANTEED CLEANUP on any failure: prevents socket descriptor leak!
            throw if (t is Exception) t else RuntimeException(t)
        }
    }

    override fun getInputStream(): InputStream = inputStream ?: throw IOException("Proxy not connected")
    override fun getOutputStream(): OutputStream = outputStream ?: throw IOException("Proxy not connected")
    override fun getSocket(): Socket = activeSocket ?: rawSocket ?: throw IOException("Proxy not connected")

    override fun close() {
        try { inputStream?.close() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        try { activeSocket?.close() } catch (_: Exception) {}
        if (rawSocket !== null && rawSocket !== activeSocket) {
            try { rawSocket?.close() } catch (_: Exception) {}
        }
        activeSocket = null
        rawSocket = null
        inputStream = null
        outputStream = null
    }

    private fun trustAllSslFactory(): SSLSocketFactory {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, SecureRandom())
        return ctx.socketFactory
    }

    /**
     * Reads a line terminated by CRLF (\r\n) or bare LF (\n).
     * Enforces [MAX_LINE_LENGTH] to prevent memory exhaustion from runaway lines.
     */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                return sb.toString()
            }
            if (b == '\r'.code) {
                val next = input.read()
                if (next == '\n'.code) return sb.toString()
                if (next == -1) return sb.toString()
                sb.append('\r').append(next.toChar())
            } else {
                sb.append(b.toChar())
            }
            if (sb.length > MAX_LINE_LENGTH) {
                throw IOException("HTTP header line exceeded maximum allowed length of $MAX_LINE_LENGTH bytes")
            }
        }
    }
}
