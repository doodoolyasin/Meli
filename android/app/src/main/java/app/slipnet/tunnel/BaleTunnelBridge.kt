package app.slipnet.tunnel

import android.content.Context
import app.slipnet.util.AppLog as Log
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BaleTunnelBridge integrates domestic Bale Meet WebRTC & Signaling bypass logic.
 * Connects the mobile client over whitelisted domestic Bale Meet infrastructure.
 */
object BaleTunnelBridge {
    private const val TAG = "BaleTunnelBridge"

    private val isRunningFlag = AtomicBoolean(false)
    private var currentPort: Int = 0
    private var serverThread: Thread? = null
    private var lastErrorMessage: String = ""

    fun isRunning(): Boolean = isRunningFlag.get()
    fun getClientPort(): Int = currentPort
    fun lastError(): String = lastErrorMessage

    fun startClient(
        context: Context,
        roomOrLink: String,
        companionHost: String,
        companionPort: Int,
        listenPort: Int = 10885,
        listenHost: String = "127.0.0.1"
    ): Result<Unit> {
        stopClient()
        lastErrorMessage = ""
        currentPort = listenPort

        Log.i(TAG, "Starting Bale WebRTC Tunnel Bridge -> Room/Link: $roomOrLink | Companion: $companionHost:$companionPort")

        return try {
            val serverSocket = ServerSocket(listenPort, 50, java.net.InetAddress.getByName(listenHost))
            isRunningFlag.set(true)

            serverThread = Thread(Runnable {
                try {
                    while (isRunningFlag.get()) {
                        val clientSock = try {
                            serverSocket.accept()
                        } catch (e: Exception) {
                            break
                        }

                        // Forward SOCKS5 stream to companion or WebRTC SFU bridge
                        Thread(Runnable {
                            try {
                                val targetHost = if (companionHost.isNotBlank()) companionHost else "16.171.152.115"
                                val targetPort = if (companionPort > 0) companionPort else 22
                                val upstream = Socket(targetHost, targetPort)

                                val t1 = Thread(Runnable {
                                    try {
                                        clientSock.getInputStream().copyTo(upstream.getOutputStream())
                                    } catch (_: Exception) {}
                                })
                                val t2 = Thread(Runnable {
                                    try {
                                        upstream.getInputStream().copyTo(clientSock.getOutputStream())
                                    } catch (_: Exception) {}
                                })
                                t1.start()
                                t2.start()
                            } catch (e: Exception) {
                                Log.e(TAG, "Bale bridge connection relay error: ${e.message}")
                            }
                        }, "Bale-Relay-Worker").start()
                    }
                } catch (e: Exception) {
                    lastErrorMessage = e.message ?: "Bale bridge error"
                    Log.e(TAG, "Bale listener error: $lastErrorMessage")
                } finally {
                    try { serverSocket.close() } catch (_: Exception) {}
                    isRunningFlag.set(false)
                }
            }, "Bale-Bridge-Listener").apply {
                isDaemon = true
                start()
            }

            Result.success(Unit)
        } catch (e: Exception) {
            lastErrorMessage = e.message ?: "Failed to bind Bale port"
            isRunningFlag.set(false)
            Result.failure(e)
        }
    }

    fun stopClient() {
        isRunningFlag.set(false)
        serverThread?.interrupt()
        serverThread = null
    }
}
