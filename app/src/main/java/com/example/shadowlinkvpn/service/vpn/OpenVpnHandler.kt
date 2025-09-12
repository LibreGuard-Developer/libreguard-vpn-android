// Kotlin
package com.example.shadowlinkvpn.service.vpn

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference

class OpenVpnHandler(
    private val appContext: Context
) : VpnProtocolHandler {

    private val tag = "OpenVpnHandler"

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _state

    // Reflection cached members
    private var startVpnOwner: Class<*>? = null
    private var startVpnReceiver: Any? = null // for companion instance if needed
    private var startVpnMethod: Method? = null
    private var stopVpnOwner: Class<*>? = null
    private var stopVpnReceiver: Any? = null
    private var stopVpnMethod: Method? = null
    private var addStateListenerMethod: Method? = null
    private var removeStateListenerMethod: Method? = null
    private var stateListenerInstance: Any? = null

    override suspend fun initialize(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            fun findCompanionInstance(klass: Class<*>): Any? {
                return try {
                    val field = klass.declaredFields.firstOrNull { it.name == "Companion" }
                    field?.isAccessible = true
                    field?.get(null)
                } catch (_: Throwable) { null }
            }

            // Try OpenVpnApi first
            runCatching {
                val api = Class.forName("unified.vpn.sdk.OpenVpnApi")
                val start = api.methods.firstOrNull { m ->
                    m.name.equals("startVpn", true) && m.parameterTypes.size == 2 &&
                        Context::class.java.isAssignableFrom(m.parameterTypes[0])
                }
                val stop = api.methods.firstOrNull { m ->
                    m.name.equals("stopVpn", true) && m.parameterTypes.size == 1 &&
                        Context::class.java.isAssignableFrom(m.parameterTypes[0])
                }
                if (start != null) {
                    startVpnOwner = api
                    startVpnReceiver = null
                    startVpnMethod = start
                }
                if (stop != null) {
                    stopVpnOwner = api
                    stopVpnReceiver = null
                    stopVpnMethod = stop
                }
            }

            // Fallback to OpenVpnApi2 if needed
            if (startVpnMethod == null || stopVpnMethod == null) {
                runCatching {
                    val api2 = Class.forName("unified.vpn.sdk.OpenVpnApi2")
                    val companion = findCompanionInstance(api2)
                    val start = (api2.methods + api2.declaredMethods).firstOrNull { m ->
                        m.name.equals("startVpn", true) && m.parameterTypes.size == 2 &&
                            Context::class.java.isAssignableFrom(m.parameterTypes[0])
                    }
                    val stop = (api2.methods + api2.declaredMethods).firstOrNull { m ->
                        m.name.equals("stopVpn", true) && m.parameterTypes.size == 1 &&
                            Context::class.java.isAssignableFrom(m.parameterTypes[0])
                    }
                    if (start != null) {
                        startVpnOwner = api2
                        startVpnReceiver = companion // may be null if static
                        startVpnMethod = start
                    }
                    if (stop != null) {
                        stopVpnOwner = api2
                        stopVpnReceiver = companion
                        stopVpnMethod = stop
                    }
                }.onFailure {
                    Log.w(tag, "OpenVpnApi2 not available: ${it.message}")
                }
            }

            if (startVpnMethod == null) {
                Log.e(tag, "No suitable startVpn() found in unified.vpn.sdk")
                return@withContext false
            }

            // Resolve status listener methods if available
            runCatching {
                val statusClass = Class.forName("unified.vpn.sdk.OpenVpnStatus")
                addStateListenerMethod = statusClass.methods.firstOrNull { it.name == "addStateListener" && it.parameterTypes.size == 1 }
                removeStateListenerMethod = statusClass.methods.firstOrNull { it.name == "removeStateListener" && it.parameterTypes.size == 1 }
            }.onFailure {
                Log.w(tag, "OpenVpnStatus not available or changed API: ${it.message}")
            }

            true
        } catch (e: Throwable) {
            Log.e(tag, "Failed to initialize OpenVPN reflection", e)
            false
        }
    }

    override suspend fun connect(context: Context, configPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val cfgFile = File(configPath)
            if (!cfgFile.exists()) {
                Log.e(tag, "Config file not found at $configPath")
                _state.value = ConnectionState.Error("OpenVPN config not found")
                _state.value = ConnectionState.Disconnected
                return@withContext false
            }

            val configContent = cfgFile.readText()
            Log.d(tag, "Starting OpenVPN with config ${cfgFile.name} (${configContent.length} chars)")

            // Register a best-effort state listener to observe CONNECTED/DISCONNECTED
            if (addStateListenerMethod != null && stateListenerInstance == null) {
                try {
                    val listenerParamType = addStateListenerMethod!!.parameterTypes.first()
                    val listener = Proxy.newProxyInstance(
                        listenerParamType.classLoader,
                        arrayOf(listenerParamType)
                    ) { _, _, args ->
                        try {
                            if (args != null && args.isNotEmpty()) {
                                val stateObj = args[0]
                                val stateStr = stateObj?.toString() ?: ""
                                when {
                                    stateStr.contains("CONNECTED", true) -> _state.value = ConnectionState.Connected
                                    stateStr.contains("RECONNECTING", true) || stateStr.contains("CONNECTING", true) || stateStr.contains("WAIT", true) -> _state.value = ConnectionState.Connecting
                                    stateStr.contains("NOPROCESS", true) || stateStr.contains("DISCONNECTED", true) || stateStr.contains("EXITING", true) -> _state.value = ConnectionState.Disconnected
                                    stateStr.contains("AUTH", true) && stateStr.contains("FAILED", true) -> _state.value = ConnectionState.Error("Authentication failed")
                                }
                                if (args.size >= 2) {
                                    (args[1] as? CharSequence)?.let { Log.d(tag, "[OVPN] $stateStr: $it") }
                                }
                            }
                        } catch (t: Throwable) {
                            Log.w(tag, "State listener invocation error: ${t.message}")
                        }
                        null
                    }
                    addStateListenerMethod!!.invoke(null, listener)
                    stateListenerInstance = listener
                } catch (e: Throwable) {
                    Log.w(tag, "Unable to attach OpenVPN state listener: ${e.message}")
                }
            }

            // Prepare parameters for startVpn according to method signature
            val start = startVpnMethod ?: run {
                Log.e(tag, "startVpn method not available")
                _state.value = ConnectionState.Error("OpenVPN start API not found")
                _state.value = ConnectionState.Disconnected
                return@withContext false
            }

            val paramTypes = start.parameterTypes
            val args: Array<Any> = when {
                // (Context, String)
                paramTypes.size == 2 && Context::class.java.isAssignableFrom(paramTypes[0]) &&
                        (paramTypes[1] == String::class.java || CharSequence::class.java.isAssignableFrom(paramTypes[1])) -> arrayOf(context, configContent)
                // (Context, File)
                paramTypes.size == 2 && Context::class.java.isAssignableFrom(paramTypes[0]) &&
                        File::class.java.isAssignableFrom(paramTypes[1]) -> arrayOf(context, cfgFile)
                // (Context, InputStream)
                paramTypes.size == 2 && Context::class.java.isAssignableFrom(paramTypes[0]) &&
                        java.io.InputStream::class.java.isAssignableFrom(paramTypes[1]) -> arrayOf(context, ByteArrayInputStream(configContent.toByteArray()))
                else -> {
                    Log.e(tag, "Unsupported startVpn signature: ${paramTypes.joinToString { it.simpleName }}")
                    _state.value = ConnectionState.Error("Unsupported OpenVPN start signature")
                    _state.value = ConnectionState.Disconnected
                    return@withContext false
                }
            }

            // Invoke
            start.invoke(startVpnReceiver, *args)
            _state.value = ConnectionState.Connecting

            // Wait up to 15s for CONNECTED state, otherwise treat as failure
            var attempts = 0
            while (attempts < 30) { // 30 * 500ms = 15s
                if (_state.value is ConnectionState.Connected) return@withContext true
                if (_state.value is ConnectionState.Error) break
                delay(500)
                attempts++
            }

            Log.w(tag, "OpenVPN did not reach CONNECTED within timeout")
            runCatching { stopVpnMethod?.invoke(stopVpnReceiver, context) }
            _state.value = ConnectionState.Disconnected
            false
        } catch (e: Throwable) {
            Log.e(tag, "Failed to start OpenVPN", e)
            _state.value = ConnectionState.Error("OpenVPN start failed: ${e.localizedMessage}")
            _state.value = ConnectionState.Disconnected
            false
        }
    }

    override suspend fun disconnect(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            _state.value = ConnectionState.Disconnecting
            // Remove listener if present
            runCatching {
                if (removeStateListenerMethod != null && stateListenerInstance != null) {
                    removeStateListenerMethod!!.invoke(null, stateListenerInstance)
                    stateListenerInstance = null
                }
            }
            // Stop via reflected API
            runCatching { stopVpnMethod?.invoke(stopVpnReceiver, context) }
            _state.value = ConnectionState.Disconnected
            true
        } catch (e: Throwable) {
            Log.e(tag, "Failed to stop OpenVPN", e)
            _state.value = ConnectionState.Error("OpenVPN disconnect failed: ${e.localizedMessage}")
            _state.value = ConnectionState.Disconnected
            false
        }
    }

    override suspend fun getConnectionLogs(context: Context): String? {
        return try {
            // No persistent logs exposed; advise to use Logcat
            "OpenVPN via unified.vpn.sdk; see Logcat tag '$tag' for live logs."
        } catch (e: Exception) {
            Log.e(tag, "Failed to get logs", e)
            null
        }
    }
}