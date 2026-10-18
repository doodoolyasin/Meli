package com.doodoolyasin.meli

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var serverEditText: EditText
    private lateinit var domainEditText: EditText
    private lateinit var connectButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var prefs: SharedPreferences

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
            Toast.makeText(this, "Please enter server and domain", Toast.LENGTH_SHORT).show()
            return
        }

        saveSettings()

        val intent = Intent(this, MeliVpnService::class.java)
        intent.putExtra("server", server)
        intent.putExtra("domain", domain)

        if (MeliVpnService.isConnected) {
            stopService(intent)
        } else {
            startForegroundService(intent)
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