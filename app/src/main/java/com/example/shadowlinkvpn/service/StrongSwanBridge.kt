package com.example.shadowlinkvpn.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import org.strongswan.android.logic.CharonVpnService
import org.strongswan.android.logic.VpnStateService

class StrongSwanBridge {
    companion object {
        private var libraryLoaded = false

        init {
            try {
                // The androidbridge library is already loaded by CharonVpnService
                libraryLoaded = true
            } catch (e: Exception) {
                Log.e("StrongSwanBridge", "Failed to initialize: ${e.message}")
                libraryLoaded = false
            }
        }

        fun isLibraryLoaded(): Boolean = libraryLoaded
    }

    private var vpnStateService: VpnStateService? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            vpnStateService = (service as VpnStateService.LocalBinder).service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vpnStateService = null
        }
    }

    fun bindToStateService(context: Context) {
        val intent = Intent(context, VpnStateService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun unbindFromStateService(context: Context) {
        context.unbindService(serviceConnection)
    }

    fun getLibraryInfo(): String {
        return "Using built-in StrongSwan androidbridge"
    }

    fun initializeCharon(): Boolean {
        // Initialization is handled by CharonVpnService
        return true
    }

    fun startConnection(context: Context, profileUuid: String): Boolean {
        val intent = Intent(context, CharonVpnService::class.java).apply {
            putExtra("org.strongswan.android.VpnProfileDataSource.KEY_UUID", profileUuid)
        }
        context.startService(intent)
        return true
    }

    fun stopConnection(context: Context) {
        val intent = Intent(context, CharonVpnService::class.java).apply {
            action = CharonVpnService.DISCONNECT_ACTION
        }
        context.startService(intent)
    }

    fun cleanup() {
        // Cleanup is handled by CharonVpnService
    }
}
