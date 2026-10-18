package com.doodoolyasin.meli

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var serverEditText: EditText
    private lateinit var domainEditText: EditText
    private lateinit var connectButton: Button
    private lateinit var testPingButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var pingTextView: TextView
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView
    private lateinit var statusIndicator: View
    private lateinit var prefs: SharedPreferences

    companion object {
        private const val REQUEST_VPN_PERMISSION = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serverEditText = findViewById(R.id.serverEditText)
        domainEditText = findViewById(R.id.domainEditText)
        connectButton = findViewById(R.id.connectButton)
        testPingButton = findViewById(R.id.testPingButton)
        statusTextView = findViewById(R.id.statusTextView)
        pingTextView = findViewById(R.id.pingTextView)
        logTextView = findViewById(R.id.logTextView)
        logScrollView = findViewById(R.id.logScrollView)
        statusIndicator = findViewById(R.id.statusIndicator)

        prefs = getSharedPreferences("meli_prefs", MODE_PRIVATE)
        loadSettings()

        connectButton.setOnClickListener { toggleVpn() }
        testPingButton.setOnClickListener { measurePing() }

        appendLog("موتور Meli راه‌اندازی شد. آماده اتصال.")
        updateUI()
    }

    private fun appendLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        runOnUiThread {
            logTextView.append("\n[$time] $msg")
            logScrollView.post { logScrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun loadSettings() {
        serverEditText.setText(prefs.getString("server", "8.8.8.8:53"))
        domainEditText.setText(prefs.getString("domain", "m.dolphin.work.gd"))
    }

    private fun saveSettings() {
        prefs.edit()
            .putString("server", serverEditText.text.toString().trim())
            .putString("domain", domainEditText.text.toString().trim())
            .apply()
    }

    private fun measurePing() {
        val server = serverEditText.text.toString().trim()
        if (server.isEmpty()) {
            Toast.makeText(this, "آدرس سرور را وارد کنید", Toast.LENGTH_SHORT).show()
            return
        }

        pingTextView.text = "در حال تست..."
        appendLog("ارسال کوئری آزمایشی DNS به $server...")

        CoroutineScope(Dispatchers.IO).launch {
            val host = if (server.contains(":")) server.substringBefore(":") else server
            val port = if (server.contains(":")) server.substringAfter(":").toIntOrNull() ?: 53 else 53
            var pingResult = -1L

            try {
                val socket = DatagramSocket()
                socket.soTimeout = 2500

                // Standard DNS query for example.com
                val query = byteArrayOf(
                    0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00, 0x07, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(),
                    'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(), 0x03,
                    'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0x00, 0x00, 0x01, 0x00, 0x01
                )

                val dest = InetSocketAddress(host, port)
                val packet = DatagramPacket(query, query.size, dest)
                val start = System.currentTimeMillis()
                socket.send(packet)

                val resp = ByteArray(512)
                val respPacket = DatagramPacket(resp, resp.size)
                socket.receive(respPacket)
                pingResult = System.currentTimeMillis() - start
                socket.close()
            } catch (e: Exception) {
                pingResult = -1L
            }

            withContext(Dispatchers.Main) {
                if (pingResult >= 0) {
                    pingTextView.text = "$pingResult ms"
                    pingTextView.setTextColor(Color.parseColor("#00FF66"))
                    appendLog("پاسخ سرور دریافت شد: $pingResult میلی‌ثانیه.")
                } else {
                    pingTextView.text = "تایم‌اوت"
                    pingTextView.setTextColor(Color.parseColor("#FF4444"))
                    appendLog("خطا: تایم‌اوت در ارتباط با ریزولور $server")
                }
            }
        }
    }

    private fun toggleVpn() {
        val server = serverEditText.text.toString().trim()
        val domain = domainEditText.text.toString().trim()

        if (server.isEmpty() || domain.isEmpty()) {
            Toast.makeText(this, "لطفاً آدرس سرور و دامنه را وارد کنید", Toast.LENGTH_SHORT).show()
            return
        }

        saveSettings()

        if (MeliVpnService.isConnected) {
            appendLog("درخواست قطع اتصال وی‌پی‌ان...")
            val intent = Intent(this, MeliVpnService::class.java)
            stopService(intent)
            updateUI()
        } else {
            appendLog("بررسی دسترسی‌های سیستم‌عامل برای VpnService...")
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                startActivityForResult(prepareIntent, REQUEST_VPN_PERMISSION)
            } else {
                startVpnService(server, domain)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN_PERMISSION && resultCode == RESULT_OK) {
            val server = serverEditText.text.toString().trim()
            val domain = domainEditText.text.toString().trim()
            startVpnService(server, domain)
        } else if (requestCode == REQUEST_VPN_PERMISSION) {
            appendLog("خطا: مجوز وی‌پی‌ان توسط کاربر لغو شد.")
            Toast.makeText(this, "اجازه VPN داده نشد", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startVpnService(server: String, domain: String) {
        appendLog("شروع سرویس تانل به مقصد $server دامنه $domain...")
        appendLog("لایه رمزنگاری AES-CTR فعال شد.")
        val intent = Intent(this, MeliVpnService::class.java).apply {
            putExtra("server", server)
            putExtra("domain", domain)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateUI()
    }

    private fun updateUI() {
        if (MeliVpnService.isConnected) {
            connectButton.text = getString(R.string.disconnect)
            connectButton.setBackgroundColor(Color.parseColor("#B00020"))
            statusTextView.text = getString(R.string.status, getString(R.string.connected))
            statusTextView.setTextColor(Color.parseColor("#00FF66"))
            statusIndicator.setBackgroundColor(Color.parseColor("#00FF66"))
        } else {
            connectButton.text = getString(R.string.connect)
            connectButton.setBackgroundColor(Color.parseColor("#6200EE"))
            statusTextView.text = getString(R.string.status, getString(R.string.disconnected))
            statusTextView.setTextColor(Color.parseColor("#FF4444"))
            statusIndicator.setBackgroundColor(Color.parseColor("#FF4444"))
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }
}