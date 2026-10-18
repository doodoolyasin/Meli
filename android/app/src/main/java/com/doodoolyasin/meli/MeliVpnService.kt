package com.doodoolyasin.meli

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.net.VpnService
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MeliVpnService : VpnService() {

    companion object {
        const val TAG = "MeliVpnService"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "meli_vpn_channel"
        
        @Volatile var isConnected = false
        private var instance: MeliVpnService? = null
        
        fun getInstance(): MeliVpnService? = instance
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var dnsSocket: DatagramSocket? = null
    private var serverAddress: String = ""
    private var domain: String = ""
    private val running = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var notificationManager: NotificationManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val vpnThread = Executors.newSingleThreadExecutor()
    private val dnsThread = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        instance = this
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            return START_STICKY
        }

        serverAddress = intent.getStringExtra("server") ?: ""
        domain = intent.getStringExtra("domain") ?: ""

        if (serverAddress.isEmpty() || domain.isEmpty()) {
            Log.e(TAG, "Missing server or domain")
            stopSelf()
            return START_NOT_STICKY
        }

        if (running.get()) {
            return START_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification("Connecting..."))
        
        scope.launch { establishVpn() }
        
        registerNetworkCallback()
        
        return START_STICKY
    }

    private fun establishVpn() {
        try {
            val builder = Builder()
                .setMtu(1200)
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
                .setSession("Meli VPN")

            vpnInterface = builder.establish()
            
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                handler.post { stopSelf() }
                return
            }

            // Create and protect outbound UDP socket so traffic does not loop into VPN interface
            val socket = DatagramSocket()
            protect(socket)
            dnsSocket = socket

            running.set(true)
            isConnected = true
            
            updateNotification("Connected")
            Log.i(TAG, "VPN established successfully")

            // Start packet processing loops
            vpnThread.execute { processVpnPackets() }
            dnsThread.execute { processDnsResponses() }

        } catch (e: Exception) {
            Log.e(TAG, "Error establishing VPN", e)
            handler.post { stopSelf() }
        }
    }

    private fun processVpnPackets() {
        val descriptor = vpnInterface?.fileDescriptor ?: return
        val fileInputStream = FileInputStream(descriptor)
        val buffer = ByteBuffer.allocate(32767)
        
        while (running.get()) {
            try {
                val bytesRead = fileInputStream.read(buffer.array())
                if (bytesRead <= 0) {
                    Thread.sleep(10)
                    continue
                }
                
                // Chunk packet so FQDN does not exceed 253 bytes limit
                val rawData = buffer.array().copyOfRange(0, bytesRead)
                val chunkSize = 100 // 100 bytes binary = 160 bytes Base32
                var offset = 0
                while (offset < rawData.size) {
                    val end = minOf(offset + chunkSize, rawData.size)
                    sendPacketViaDns(rawData.copyOfRange(offset, end))
                    offset = end
                }
                
                buffer.clear()
            } catch (e: Exception) {
                if (running.get()) {
                    Log.e(TAG, "Error reading VPN packet", e)
                }
                break
            }
        }
        
        try {
            fileInputStream.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN input stream", e)
        }
    }

    private fun processDnsResponses() {
        val buffer = ByteArray(4096)
        val packet = DatagramPacket(buffer, buffer.size)
        
        while (running.get()) {
            try {
                val socket = dnsSocket ?: break
                socket.receive(packet)
                val length = packet.length
                
                if (length > 0) {
                    val payload = extractPayloadFromDns(buffer, length)
                    if (payload != null && payload.isNotEmpty()) {
                        writeToVpn(payload)
                    }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    Log.e(TAG, "Error receiving DNS response", e)
                }
                break
            }
        }
    }

    private fun sendPacketViaDns(chunk: ByteArray) {
        try {
            val socket = dnsSocket ?: return
            val encoded = Base32.encode(chunk)
            if (encoded.isEmpty()) return
            
            // Split into labels (max 63 chars each)
            val labels = mutableListOf<String>()
            var remaining = encoded
            while (remaining.isNotEmpty()) {
                val take = minOf(remaining.length, 63)
                labels.add(remaining.substring(0, take))
                remaining = remaining.substring(take)
            }
            
            val nameBuilder = StringBuilder()
            labels.forEachIndexed { index, label ->
                if (index > 0) nameBuilder.append(".")
                nameBuilder.append(label)
            }
            nameBuilder.append(".").append(domain)
            
            val query = buildDnsQuery(nameBuilder.toString())
            
            val host = if (serverAddress.contains(":")) serverAddress.substringBefore(":") else serverAddress
            val port = if (serverAddress.contains(":")) serverAddress.substringAfter(":").toIntOrNull() ?: 53 else 53
            val serverAddr = InetSocketAddress(host, port)
            
            socket.send(DatagramPacket(query, query.size, serverAddr))
        } catch (e: Exception) {
            Log.e(TAG, "Error sending DNS query", e)
        }
    }

    private fun buildDnsQuery(qname: String): ByteArray {
        val buffer = ByteBuffer.allocate(512)
        buffer.putShort(0x1234.toShort()) // ID
        buffer.putShort(0x0100.toShort()) // Flags: standard query, recursion desired
        buffer.putShort(1.toShort())      // QDCOUNT = 1
        buffer.putShort(0.toShort())      // ANCOUNT = 0
        buffer.putShort(0.toShort())      // NSCOUNT = 0
        buffer.putShort(0.toShort())      // ARCOUNT = 0
        
        qname.split(".").forEach { label ->
            if (label.isNotEmpty()) {
                buffer.put(label.length.toByte())
                buffer.put(label.toByteArray())
            }
        }
        buffer.put(0.toByte()) // Null terminator
        buffer.putShort(1.toShort())  // QTYPE = A
        buffer.putShort(1.toShort())  // QCLASS = IN
        
        return buffer.array().copyOf(buffer.position())
    }

    private fun extractPayloadFromDns(data: ByteArray, length: Int): ByteArray? {
        try {
            if (length < 12) return null
            var pos = 12
            while (pos < length && data[pos].toInt() != 0) {
                val len = data[pos].toInt() and 0xFF
                pos += 1 + len
            }
            pos += 5 // null + qtype(2) + qclass(2)
            
            val answerCount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
            for (i in 0 until answerCount) {
                if (pos >= length) break
                // Skip name
                if ((data[pos].toInt() and 0xC0) == 0xC0) {
                    pos += 2
                } else {
                    while (pos < length && data[pos].toInt() != 0) {
                        pos += 1 + (data[pos].toInt() and 0xFF)
                    }
                    pos += 1
                }
                
                if (pos + 10 > length) break
                val type = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                val rdlength = ((data[pos + 8].toInt() and 0xFF) shl 8) or (data[pos + 9].toInt() and 0xFF)
                pos += 10
                
                if (type == 16 && pos + rdlength <= length) { // TXT record
                    val txtLen = data[pos].toInt() and 0xFF
                    if (txtLen in 1 until rdlength) {
                        val txtStr = String(data, pos + 1, txtLen)
                        return Base32.decode(txtStr)
                    }
                }
                pos += rdlength
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing DNS response", e)
        }
        return null
    }

    private fun writeToVpn(data: ByteArray) {
        try {
            val descriptor = vpnInterface?.fileDescriptor ?: return
            val fileOutputStream = FileOutputStream(descriptor)
            fileOutputStream.write(data)
            fileOutputStream.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to VPN", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Meli VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Meli DNS Tunnel VPN Connection"
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Meli VPN")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(status: String) {
        notificationManager?.notify(NOTIFICATION_ID, createNotification(status))
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                Log.i(TAG, "Network lost, reconnecting...")
                handler.postDelayed({ establishVpn() }, 5000)
            }
        }
        cm.registerNetworkCallback(request, networkCallback!!)
    }

    override fun onDestroy() {
        running.set(false)
        isConnected = false
        instance = null
        
        scope.cancel()
        vpnThread.shutdown()
        dnsThread.shutdown()
        
        try {
            vpnInterface?.close()
            dnsSocket?.close()
            networkCallback?.let { 
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it) 
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up", e)
        }
        
        notificationManager?.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}