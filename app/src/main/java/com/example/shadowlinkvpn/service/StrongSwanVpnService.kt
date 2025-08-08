package com.example.shadowlinkvpn.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import org.strongswan.android.logic.CharonVpnService

class StrongSwanVpnService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val configFilePath = intent?.getStringExtra("configFile") ?: return START_NOT_STICKY

        // Delegate to the built-in CharonVpnService
        val charonIntent = Intent(this, CharonVpnService::class.java).apply {
            // Add necessary extras for the profile
            putExtras(intent.extras ?: return START_NOT_STICKY)
        }

        startService(charonIntent)
        return START_NOT_STICKY
    }
}