// app/src/main/java/com/example/shadowlinkvpn/service/StrongSwanBridge.kt
package com.example.shadowlinkvpn.service

class StrongSwanBridge {
    companion object {
        init {
            try {
                System.loadLibrary("strongswan_bridge")
            } catch (e: UnsatisfiedLinkError) {
                throw RuntimeException("Failed to load StrongSwan bridge library", e)
            }
        }
    }

    external fun initializeCharon(): Boolean
    external fun loadConfig(configPath: String): Boolean
    external fun startConnection(connectionName: String): Boolean
    external fun stopConnection(connectionName: String)
    external fun cleanup()
}