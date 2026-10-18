package com.doodoolyasin.meli

import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var serverEditText: EditText
    private lateinit var domainEditText: EditText
    private lateinit var connectButton: Button
    private lateinit var statusTextView: TextView
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
        statusTextView = findViewById(R.id.statusTextView)

        prefs = getSharedPreferences("meli_prefs", MODE_PRIVATE)
        loadSettings()

        connectButton.setOnClickListener { toggleVpn() }
        
        updateUI()
    }

    private fun loadSettings() {
        serverEditText.setText(prefs.getString("server", ""))
        domainEditText.setText(prefs.getString("domain", ""))
    }

    private fun saveSettings() {
        prefs.edit()
            .putString("server", serverEditText.text.toString())
            .putString("domain", domainEditText.text.toString())
            .apply()
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
            val intent = Intent(this, MeliVpnService::class.java)
            stopService(intent)
            updateUI()
        } else {
            // Check VPN permission first
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
            Toast.makeText(this, "اجازه VPN داده نشد", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startVpnService(server: String, domain: String) {
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
            statusTextView.text = getString(R.string.status, getString(R.string.connected))
            statusTextView.setTextColor(getColor(R.color.green))
        } else {
            connectButton.text = getString(R.string.connect)
            statusTextView.text = getString(R.string.status, getString(R.string.disconnected))
            statusTextView.setTextColor(getColor(R.color.red))
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }
}