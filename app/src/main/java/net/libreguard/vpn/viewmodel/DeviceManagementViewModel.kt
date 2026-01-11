package net.libreguard.vpn.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.libreguard.vpn.network.*
import net.libreguard.vpn.util.RateLimitConfig
import retrofit2.Response
import java.io.IOException

private const val TAG = "DeviceManagementVM"

/**
 * ViewModel for device management operations.
 * Handles fetching device list, removing devices (single and bulk), error handling, and rate limiting.
 */
class DeviceManagementViewModel(application: Application) : AndroidViewModel(application) {

    // Device list state
    private val _devices = MutableStateFlow<List<DeviceDto>>(emptyList())
    val devices: StateFlow<List<DeviceDto>> = _devices

    // Loading states
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    // Error state
    private val _error = MutableStateFlow<ApiError?>(null)
    val error: StateFlow<ApiError?> = _error

    // Rate limit countdown (seconds remaining)
    private val _rateLimitCountdown = MutableStateFlow<Int?>(null)
    val rateLimitCountdown: StateFlow<Int?> = _rateLimitCountdown

    // Success message
    private val _successMessage = MutableStateFlow<String?>(null)
    val successMessage: StateFlow<String?> = _successMessage

    // Optimistic removal tracking for rollback
    private var optimisticRemovals = mutableMapOf<Int, DeviceDto>()

    /**
     * Fetch device list from API
     *
     * @param isRefresh Whether this is a pull-to-refresh action
     */
    fun fetchDevices(isRefresh: Boolean = false) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "========== FETCH DEVICES START ==========")
                Log.d(TAG, "isRefresh: $isRefresh")

                if (isRefresh) {
                    _isRefreshing.value = true
                } else {
                    _isLoading.value = true
                }
                _error.value = null

                val token = RetrofitClient.getTokenManager().getAccessToken()
                Log.d(TAG, "Token retrieved: ${if (token.isNullOrBlank()) "NULL/BLANK" else "EXISTS (${token.take(20)}...)"}")

                if (token.isNullOrBlank()) {
                    Log.e(TAG, "ERROR: No authentication token available")
                    _error.value = ApiError.Unauthorized("No authentication token available")
                    return@launch
                }

                Log.d(TAG, "Making API call to GET /api/devices")
                Log.d(TAG, "Authorization header: Bearer ${token.take(20)}...")

                val response = RetrofitClient.instance.getDevices("Bearer $token")

                Log.d(TAG, "API Response received:")
                Log.d(TAG, "  - Status Code: ${response.code()}")
                Log.d(TAG, "  - Is Successful: ${response.isSuccessful}")
                Log.d(TAG, "  - Message: ${response.message()}")

                if (response.isSuccessful) {
                    val deviceList = response.body()?.devices ?: emptyList()
                    Log.d(TAG, "SUCCESS: Fetched ${deviceList.size} devices")
                    deviceList.forEachIndexed { index, device ->
                        Log.d(TAG, "  Device $index: id=${device.id}, deviceId=${device.deviceId.takeLast(8)}, isActive=${device.isActive}")
                    }
                    _devices.value = deviceList
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "ERROR Response:")
                    Log.e(TAG, "  - Error Body: $errorBody")
                    _error.value = handleApiError(response)
                }

                Log.d(TAG, "========== FETCH DEVICES END ==========")
            } catch (e: IOException) {
                Log.e(TAG, "Network error fetching devices", e)
                Log.e(TAG, "  - Exception: ${e.javaClass.simpleName}")
                Log.e(TAG, "  - Message: ${e.message}")
                _error.value = ApiError.NetworkError(e)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error fetching devices", e)
                Log.e(TAG, "  - Exception: ${e.javaClass.simpleName}")
                Log.e(TAG, "  - Message: ${e.message}")
                Log.e(TAG, "  - Stack trace:", e)
                _error.value = ApiError.Unknown(0, e.message)
            } finally {
                _isLoading.value = false
                _isRefreshing.value = false
                Log.d(TAG, "Fetch devices completed - loading states reset")
            }
        }
    }

    /**
     * Remove a single device (logout device).
     * Uses optimistic UI update with rollback on failure.
     *
     * @param deviceId Device ID to remove
     */
    fun removeDevice(deviceId: Int) {
        Log.d(TAG, "========== REMOVE DEVICE START ==========")
        Log.d(TAG, "Device ID to remove: $deviceId")

        val device = _devices.value.find { it.id == deviceId }
        if (device == null) {
            Log.w(TAG, "Device $deviceId not found in list")
            return
        }

        Log.d(TAG, "Found device: deviceId=${device.deviceId.takeLast(8)}, isActive=${device.isActive}")

        viewModelScope.launch {
            try {
                Log.d(TAG, "Optimistic update: removing device from UI")
                // Optimistic update: remove from UI immediately
                optimisticRemovals[deviceId] = device
                _devices.value = _devices.value.filter { it.id != deviceId }
                _error.value = null

                val token = RetrofitClient.getTokenManager().getAccessToken()
                Log.d(TAG, "Token retrieved: ${if (token.isNullOrBlank()) "NULL/BLANK" else "EXISTS (${token.take(20)}...)"}")

                if (token.isNullOrBlank()) {
                    Log.e(TAG, "ERROR: No token - rolling back removal")
                    rollbackRemoval(deviceId, device)
                    _error.value = ApiError.Unauthorized("No authentication token available")
                    return@launch
                }

                Log.d(TAG, "Making API call to POST /api/devices/remove/$deviceId")
                val response = RetrofitClient.instance.removeDevice("Bearer $token", deviceId)

                Log.d(TAG, "API Response:")
                Log.d(TAG, "  - Status Code: ${response.code()}")
                Log.d(TAG, "  - Is Successful: ${response.isSuccessful}")

                if (response.isSuccessful) {
                    Log.d(TAG, "SUCCESS: Device removed")
                    optimisticRemovals.remove(deviceId)
                    _successMessage.value = "Device logged out successfully"
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "ERROR: Failed to remove device")
                    Log.e(TAG, "  - Error Body: $errorBody")
                    rollbackRemoval(deviceId, device)
                    _error.value = handleApiError(response)
                }

                Log.d(TAG, "========== REMOVE DEVICE END ==========")
            } catch (e: IOException) {
                Log.e(TAG, "Network error removing device", e)
                rollbackRemoval(deviceId, device)
                _error.value = ApiError.NetworkError(e)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error removing device", e)
                rollbackRemoval(deviceId, device)
                _error.value = ApiError.Unknown(0, e.message)
            }
        }
    }

    /**
     * Delete a device record (destructive operation).
     *
     * @param deviceId Device ID to delete
     */
    fun deleteDevice(deviceId: Int) {
        val device = _devices.value.find { it.id == deviceId }
        if (device == null) {
            Log.w(TAG, "Device $deviceId not found in list")
            return
        }

        viewModelScope.launch {
            try {
                _isLoading.value = true
                _error.value = null

                val token = RetrofitClient.getTokenManager().getAccessToken()
                if (token.isNullOrBlank()) {
                    _error.value = ApiError.Unauthorized("No authentication token available")
                    return@launch
                }

                val response = RetrofitClient.instance.deleteDevice("Bearer $token", deviceId)

                if (response.isSuccessful) {
                    _devices.value = _devices.value.filter { it.id != deviceId }
                    _successMessage.value = "Device deleted successfully"
                    Log.d(TAG, "Device $deviceId deleted successfully")
                } else {
                    _error.value = handleApiError(response)
                    Log.e(TAG, "Failed to delete device: ${response.code()}")
                }
            } catch (e: IOException) {
                _error.value = ApiError.NetworkError(e)
                Log.e(TAG, "Network error deleting device", e)
            } catch (e: Exception) {
                _error.value = ApiError.Unknown(0, e.message)
                Log.e(TAG, "Unexpected error deleting device", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Remove all devices except the current one.
     */
    fun removeAllOtherDevices() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _error.value = null

                val token = RetrofitClient.getTokenManager().getAccessToken()
                if (token.isNullOrBlank()) {
                    _error.value = ApiError.Unauthorized("No authentication token available")
                    return@launch
                }

                val response = RetrofitClient.instance.removeAllOtherDevices("Bearer $token")

                if (response.isSuccessful) {
                    val count = response.body()?.devicesRemoved ?: 0
                    _successMessage.value = "Logged out $count device(s)"
                    Log.d(TAG, "Removed $count other devices")
                    // Refresh list to show updated state
                    fetchDevices(isRefresh = true)
                } else {
                    _error.value = handleApiError(response)
                    Log.e(TAG, "Failed to remove all other devices: ${response.code()}")
                }
            } catch (e: IOException) {
                _error.value = ApiError.NetworkError(e)
                Log.e(TAG, "Network error removing all other devices", e)
            } catch (e: Exception) {
                _error.value = ApiError.Unknown(0, e.message)
                Log.e(TAG, "Unexpected error removing all other devices", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Remove all inactive devices.
     */
    fun removeAllInactiveDevices() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _error.value = null

                val token = RetrofitClient.getTokenManager().getAccessToken()
                if (token.isNullOrBlank()) {
                    _error.value = ApiError.Unauthorized("No authentication token available")
                    return@launch
                }

                val response = RetrofitClient.instance.removeAllInactiveDevices("Bearer $token")

                if (response.isSuccessful) {
                    val count = response.body()?.devicesRemoved ?: 0
                    _successMessage.value = "Cleaned up $count inactive device(s)"
                    Log.d(TAG, "Removed $count inactive devices")
                    // Refresh list to show updated state
                    fetchDevices(isRefresh = true)
                } else {
                    _error.value = handleApiError(response)
                    Log.e(TAG, "Failed to remove inactive devices: ${response.code()}")
                }
            } catch (e: IOException) {
                _error.value = ApiError.NetworkError(e)
                Log.e(TAG, "Network error removing inactive devices", e)
            } catch (e: Exception) {
                _error.value = ApiError.Unknown(0, e.message)
                Log.e(TAG, "Unexpected error removing inactive devices", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Rollback optimistic removal on failure
     */
    private fun rollbackRemoval(deviceId: Int, device: DeviceDto) {
        optimisticRemovals.remove(deviceId)
        val currentList = _devices.value.toMutableList()
        // Re-insert device in original position if possible
        currentList.add(device)
        _devices.value = currentList.sortedBy { it.id }
        Log.d(TAG, "Rolled back removal of device $deviceId")
    }

    /**
     * Undo the last optimistic removal (for snackbar undo action)
     */
    fun undoLastRemoval() {
        val lastRemoval = optimisticRemovals.entries.lastOrNull()
        if (lastRemoval != null) {
            rollbackRemoval(lastRemoval.key, lastRemoval.value)
            _successMessage.value = "Undo successful"
        }
    }

    /**
     * Handle API error responses and convert to ApiError types
     */
    private fun handleApiError(response: Response<*>): ApiError {
        return when (response.code()) {
            429 -> {
                // Extract Retry-After header (seconds)
                val retryAfter = response.headers()
                    .get("Retry-After")
                    ?.toIntOrNull()
                    ?: RateLimitConfig.DEFAULT_RETRY_AFTER_SECONDS

                // Start countdown
                startRateLimitCountdown(retryAfter)

                ApiError.RateLimited(retryAfter)
            }
            401 -> ApiError.Unauthorized("Session expired. Please log in again.")
            403 -> {
                val errorBody = response.errorBody()?.string()
                val message = errorBody ?: "Not authorized to perform this action."
                // Check if this is a "current device" or "active device" protection error
                if (errorBody?.contains("current device", ignoreCase = true) == true ||
                    errorBody?.contains("active device", ignoreCase = true) == true) {
                    ApiError.DeviceProtected(message)
                } else {
                    ApiError.Unauthorized(message)
                }
            }
            in 500..599 -> ApiError.ServerError(response.code(), "Server error. Please try again later.")
            else -> {
                val errorBody = response.errorBody()?.string()
                ApiError.Unknown(response.code(), errorBody)
            }
        }
    }

    /**
     * Start countdown timer for rate limit
     */
    private fun startRateLimitCountdown(seconds: Int) {
        viewModelScope.launch {
            var remaining = seconds
            _rateLimitCountdown.value = remaining

            while (remaining > 0) {
                delay(1000)
                remaining--
                _rateLimitCountdown.value = remaining
            }

            _rateLimitCountdown.value = null
            Log.d(TAG, "Rate limit countdown completed")
        }
    }

    /**
     * Clear error state
     */
    fun clearError() {
        _error.value = null
    }

    /**
     * Clear success message
     */
    fun clearSuccessMessage() {
        _successMessage.value = null
    }

    /**
     * Load devices from pre-login device list (from 409 error response)
     */
    fun loadDevicesFromErrorResponse(devices: List<DeviceDto>) {
        _devices.value = devices
        Log.d(TAG, "Loaded ${devices.size} devices from error response")
    }
}

