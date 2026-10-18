package com.doodoolyasin.meli

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "MeliBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || 
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            
            Log.i(TAG, "Boot completed, checking auto-start")
            
            val prefs = context.getSharedPreferences("meli_prefs", Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("auto_start", false)
            
            if (autoStart) {
                val server = prefs.getString("server", "")
                val domain = prefs.getString("domain", "")
                
                if (!server.isNullOrEmpty() && !domain.isNullOrEmpty()) {
                    val serviceIntent = Intent(context, MeliVpnService::class.java)
                    serviceIntent.putExtra("server", server)
                    serviceIntent.putExtra("domain", domain)
                    
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    Log.i(TAG, "Auto-started VPN service")
                }
            }
        }
    }
}