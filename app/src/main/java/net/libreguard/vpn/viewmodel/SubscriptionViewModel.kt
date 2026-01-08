package net.libreguard.vpn.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import net.libreguard.vpn.network.RetrofitClient
import net.libreguard.vpn.network.SubscriptionStatusResponse
import net.libreguard.vpn.network.MoneroInvoiceResponse
import net.libreguard.vpn.network.MoneroStatusResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Context

private const val TAG = "SubscriptionViewModel"
private const val SUBSCRIPTION_CACHE_TTL_MS = 30 * 1000L // 30 seconds (reduced from 5 minutes for better responsiveness)

class SubscriptionViewModel(application: Application) : AndroidViewModel(application) {

    private val sharedPrefs by lazy {
        application.getSharedPreferences("vpn_subscription_prefs", Context.MODE_PRIVATE)
    }

    // Subscription state
    private val _subscriptionStatus = MutableStateFlow<SubscriptionStatusResponse?>(null)
    val subscriptionStatus: StateFlow<SubscriptionStatusResponse?> = _subscriptionStatus

    private val _isPro = MutableStateFlow(false)
    val isPro: StateFlow<Boolean> = _isPro

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    // Payment states
    private val _checkoutUrl = MutableStateFlow<String?>(null)
    val checkoutUrl: StateFlow<String?> = _checkoutUrl

    private val _moneroInvoice = MutableStateFlow<MoneroInvoiceResponse?>(null)
    val moneroInvoice: StateFlow<MoneroInvoiceResponse?> = _moneroInvoice

    private val _moneroPaymentStatus = MutableStateFlow<MoneroStatusResponse?>(null)
    val moneroPaymentStatus: StateFlow<MoneroStatusResponse?> = _moneroPaymentStatus

    private val _moneroPrice = MutableStateFlow<net.libreguard.vpn.network.MoneroPriceResponse?>(null)
    val moneroPrice: StateFlow<net.libreguard.vpn.network.MoneroPriceResponse?> = _moneroPrice

    private val _isMoneroPolling = MutableStateFlow(false)
    val isMoneroPolling: StateFlow<Boolean> = _isMoneroPolling

    private val _hoursRemaining = MutableStateFlow(0)
    val hoursRemaining: StateFlow<Int> = _hoursRemaining

    private val _minutesRemaining = MutableStateFlow(0)
    val minutesRemaining: StateFlow<Int> = _minutesRemaining

    private var currentUserId: String? = null
    private var moneroPollingJob: Job? = null
    private var timerJob: Job? = null
    private var lastSubscriptionCheckTime = 0L

    // Track in-flight API request to prevent duplicate calls
    private var fetchJob: Job? = null

    init {
        // CRITICAL FIX: Load cached subscription status immediately on ViewModel creation
        // This prevents the UI from showing "Free" while waiting for API response
        restoreCachedSubscriptionStatus()
        Log.d(TAG, "SubscriptionViewModel initialized with cached status: isPro=${_isPro.value}")
    }

    /**
     * Keep API cache scoping behavior, but do not store auth token here.
     * TokenManager is the single source of truth for Authorization.
     */
    fun setAuthToken(token: String, userId: String? = null) {
        currentUserId = userId

        // Store user ID for cache versioning
        if (userId != null) {
            sharedPrefs.edit().putString("subscription_user_id", userId).apply()
        }
    }

    private fun getAuthHeaderOrNull(): String? {
        val token = RetrofitClient.getTokenManager().getAccessToken()
        return if (token.isNullOrBlank()) null else "Bearer $token"
    }

    /**
     * Fetch subscription status from backend
     */
    fun fetchSubscriptionStatus() {
        val authHeader = getAuthHeaderOrNull()
        if (authHeader == null) {
            _errorMessage.value = "Authentication required"
            return
        }

        // Check cache validity
        val now = System.currentTimeMillis()
        if (now - lastSubscriptionCheckTime < SUBSCRIPTION_CACHE_TTL_MS) {
            Log.d(TAG, "Using cached subscription status (TTL valid)")
            return
        }

        // OPTIMIZATION: Prevent duplicate in-flight requests
        if (fetchJob?.isActive == true) {
            Log.d(TAG, "Subscription fetch already in progress, skipping duplicate request")
            return
        }

        _isLoading.value = true
        _errorMessage.value = null

        fetchJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = RetrofitClient.instance.getSubscriptionStatus(
                    authorization = authHeader
                )

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && response.body() != null) {
                        val status = response.body()!!
                        _subscriptionStatus.value = status
                        _isPro.value = status.isPro
                        lastSubscriptionCheckTime = System.currentTimeMillis()

                        // Cache to SharedPreferences
                        cacheSubscriptionStatus(status)

                        // Persist quick 'isPro' flag for other components to read synchronously
                        try {
                            sharedPrefs.edit().putBoolean("subscription_is_pro", status.isPro).apply()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to persist isPro flag: ${e.message}")
                        }

                        Log.d(TAG, "Subscription status fetched: ${status.plan} (isPro=${status.isPro})")
                    } else {
                        _errorMessage.value = "Failed to fetch subscription status: ${response.code()}"
                        Log.w(TAG, "Failed to fetch subscription: ${response.code()}")
                    }
                    _isLoading.value = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching subscription status", e)
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Error: ${e.localizedMessage}"
                    _isLoading.value = false
                    // Try to restore from cache on error
                    restoreCachedSubscriptionStatus()
                }
            }
        }
    }

    /**
     * Check if user has access to a specific server tier
     */
    fun checkServerAccess(tierNumber: Int): Job {
        return viewModelScope.launch(Dispatchers.IO) {
            val authHeader = getAuthHeaderOrNull()
            if (authHeader == null) {
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Authentication required"
                }
                return@launch
            }

            try {
                val response = RetrofitClient.instance.canAccessServer(
                    authorization = authHeader,
                    tierNumber = tierNumber
                )

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && response.body() != null) {
                        val access = response.body()!!
                        Log.d(TAG, "Server access check: canAccess=${access.canAccess}, tier=${access.serverTier}")
                    } else {
                        Log.w(TAG, "Failed to check server access: ${response.code()}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking server access", e)
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Error: ${e.localizedMessage}"
                }
            }
        }
    }

    /**
     * Fetch checkout URL for card payment (LemonSqueezy)
     */
    fun fetchCheckoutUrl() {
        val authHeader = getAuthHeaderOrNull()
        Log.d(TAG, "fetchCheckoutUrl() called - auth is ${if (authHeader != null) "SET" else "NULL"}")
        if (authHeader == null) {
            _errorMessage.value = "Authentication required"
            Log.e(TAG, "fetchCheckoutUrl() failed: authToken is null")
            return
        }

        _isLoading.value = true
        _errorMessage.value = null
        Log.d(TAG, "Starting API call to fetch checkout URL with token: ${authHeader.take(20)}...")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Calling RetrofitClient.getCheckoutUrl()...")
                val response = RetrofitClient.instance.getCheckoutUrl(
                    authorization = authHeader
                )
                Log.d(TAG, "API response received - isSuccessful: ${response.isSuccessful}, code: ${response.code()}")

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && response.body() != null) {
                        val checkoutUrl = response.body()!!.checkoutUrl
                        Log.d(TAG, "Checkout URL received: ${checkoutUrl.take(100)}...")
                        _checkoutUrl.value = checkoutUrl
                        Log.d(TAG, "Checkout URL set in StateFlow: ${_checkoutUrl.value?.take(50)}...")
                    } else {
                        val errorMsg = "Failed to get checkout URL: ${response.code()} - ${response.message()}"
                        Log.e(TAG, errorMsg)
                        _errorMessage.value = errorMsg
                    }
                    _isLoading.value = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching checkout URL: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Error: ${e.localizedMessage}"
                    _isLoading.value = false
                }
            }
        }
    }

    /**
     * Fetch Monero price for Pro subscription
     */
    fun fetchMoneroPrice() {
        val authHeader = getAuthHeaderOrNull()
        if (authHeader == null) {
            _errorMessage.value = "Authentication required"
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = RetrofitClient.instance.getMoneroPrice(
                    authorization = authHeader
                )

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && response.body() != null) {
                        _moneroPrice.value = response.body()
                        Log.d(TAG, "Monero price fetched: ${response.body()?.xmrAmount} XMR = $${response.body()?.usdAmount} USD")
                    } else {
                        Log.w(TAG, "Failed to fetch Monero price: ${response.code()}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching Monero price", e)
            }
        }
    }

    /**
     * Create Monero invoice for payment
     */
    fun createMoneroInvoice() {
        val authHeader = getAuthHeaderOrNull()
        if (authHeader == null) {
            _errorMessage.value = "Authentication required"
            return
        }

        _isLoading.value = true
        _errorMessage.value = null

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = RetrofitClient.instance.createMoneroInvoice(
                    authorization = authHeader
                )

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && response.body() != null) {
                        val invoice = response.body()!!
                        _moneroInvoice.value = invoice
                        Log.d(TAG, "Monero invoice created: ${invoice.invoiceId}")

                        // Fetch initial status with confirmations
                        fetchMoneroStatus(invoice.invoiceId)

                        // Start timer countdown
                        startExpirationTimer(invoice.expiresAt)

                        // Start polling for payment status
                        startMoneroPaymentPolling(invoice.invoiceId)
                    } else {
                        _errorMessage.value = "Failed to create invoice: ${response.code()}"
                    }
                    _isLoading.value = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating Monero invoice", e)
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Error: ${e.localizedMessage}"
                    _isLoading.value = false
                }
            }
        }
    }

    /**
     * Fetch latest pending Monero invoice (to resume payment)
     */
    fun fetchLatestMoneroInvoice() {
        val authHeader = getAuthHeaderOrNull()
        if (authHeader == null) {
            _errorMessage.value = "Authentication required"
            return
        }

        _isLoading.value = true
        _errorMessage.value = null

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = RetrofitClient.instance.getLatestMoneroInvoice(
                    authorization = authHeader
                )

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && response.body() != null) {
                        val invoice = response.body()!!
                        _moneroInvoice.value = invoice
                        Log.d(TAG, "Latest Monero invoice fetched: ${invoice.invoiceId}")

                        // Fetch current status with confirmations
                        fetchMoneroStatus(invoice.invoiceId)

                        // Start timer countdown
                        startExpirationTimer(invoice.expiresAt)

                        // Start polling for payment status
                        startMoneroPaymentPolling(invoice.invoiceId)
                    } else if (response.code() == 404) {
                        // No pending invoice, create a new one
                        Log.d(TAG, "No pending invoice found, creating new one")
                        createMoneroInvoice()
                    } else {
                        _errorMessage.value = "Failed to fetch invoice: ${response.code()}"
                    }
                    _isLoading.value = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching latest Monero invoice", e)
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Error: ${e.localizedMessage}"
                    _isLoading.value = false
                }
            }
        }
    }

    /**
     * Fetch Monero payment status (with confirmations)
     */
    private suspend fun fetchMoneroStatus(invoiceId: String) {
        try {
            val response = RetrofitClient.instance.getMoneroPaymentStatus(
                authorization = getAuthHeaderOrNull()!!,
                invoiceId = invoiceId
            )

            if (response.isSuccessful && response.body() != null) {
                withContext(Dispatchers.Main) {
                    _moneroPaymentStatus.value = response.body()
                    Log.d(TAG, "Monero status: ${response.body()?.status}, confirmations: ${response.body()?.confirmations}/${response.body()?.requiredConfirmations}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Monero status", e)
        }
    }

    /**
     * Start countdown timer for invoice expiration
     */
    private fun startExpirationTimer(expiresAt: String?) {
        timerJob?.cancel()

        if (expiresAt == null) {
            Log.w(TAG, "No expiration time provided for invoice")
            return
        }

        timerJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val expirationTime = java.time.Instant.parse(expiresAt).toEpochMilli()

                while (true) {
                    val now = System.currentTimeMillis()
                    val remaining = expirationTime - now

                    if (remaining <= 0) {
                        withContext(Dispatchers.Main) {
                            _hoursRemaining.value = 0
                            _minutesRemaining.value = 0
                        }
                        break
                    }

                    val hours = (remaining / (1000 * 60 * 60)).toInt()
                    val minutes = ((remaining % (1000 * 60 * 60)) / (1000 * 60)).toInt()

                    withContext(Dispatchers.Main) {
                        _hoursRemaining.value = hours
                        _minutesRemaining.value = minutes
                    }

                    delay(60000) // Update every minute
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in expiration timer", e)
            }
        }
    }

    /**
     * Start polling for Monero payment status (every 30 seconds, max 1 hour)
     */
    private fun startMoneroPaymentPolling(invoiceId: String) {
        // Cancel any existing polling job
        moneroPollingJob?.cancel()

        _isMoneroPolling.value = true

        moneroPollingJob = viewModelScope.launch(Dispatchers.IO) {
            val maxPollingDuration = 3600000L // 1 hour
            val pollInterval = 30000L // 30 seconds
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < maxPollingDuration) {
                var shouldStopPolling = false
                try {
                    delay(pollInterval)

                    val authHeader = getAuthHeaderOrNull()
                    if (authHeader == null) {
                        withContext(Dispatchers.Main) {
                            _isMoneroPolling.value = false
                        }
                        return@launch
                    }

                    val response = RetrofitClient.instance.getMoneroPaymentStatus(
                        authorization = authHeader,
                        invoiceId = invoiceId
                    )

                    if (response.isSuccessful && response.body() != null) {
                        val statusResponse = response.body()!!
                        withContext(Dispatchers.Main) {
                            _moneroPaymentStatus.value = statusResponse
                            Log.d(TAG, "Monero payment status: ${statusResponse.status}, confirmations: ${statusResponse.confirmations}/${statusResponse.requiredConfirmations}")

                            // Stop polling if completed or failed
                            if (statusResponse.status.equals("Completed", ignoreCase = true) ||
                                statusResponse.status.equals("Paid", ignoreCase = true) ||
                                statusResponse.status.equals("Failed", ignoreCase = true)) {
                                _isMoneroPolling.value = false
                                if (statusResponse.status.equals("Completed", ignoreCase = true) ||
                                    statusResponse.status.equals("Paid", ignoreCase = true)) {
                                    // Refresh subscription status after successful payment
                                    lastSubscriptionCheckTime = 0 // Force cache refresh
                                    fetchSubscriptionStatus()
                                }
                                shouldStopPolling = true
                            }
                        }
                    } else {
                        Log.w(TAG, "Failed to check payment status: ${response.code()}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error polling payment status", e)
                }

                if (shouldStopPolling) break
            }

            // Polling timeout
            withContext(Dispatchers.Main) {
                _isMoneroPolling.value = false
                _errorMessage.value = "Payment verification timeout. Please check your transaction."
            }
        }
    }

    /**
     * Stop Monero payment polling
     */
    fun stopMoneroPolling() {
        moneroPollingJob?.cancel()
        timerJob?.cancel()
        _isMoneroPolling.value = false
    }

    /**
     * Manually check Monero payment status
     */
    fun checkMoneroPaymentStatus(invoiceId: String) {
        val authHeader = getAuthHeaderOrNull()
        if (authHeader == null) {
            _errorMessage.value = "Authentication required"
            return
        }

        _isLoading.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = RetrofitClient.instance.getMoneroPaymentStatus(
                    authorization = authHeader,
                    invoiceId = invoiceId
                )

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && response.body() != null) {
                        val statusResponse = response.body()!!
                        _moneroPaymentStatus.value = statusResponse
                        Log.d(TAG, "Monero payment status checked: ${statusResponse.status}, confirmations: ${statusResponse.confirmations}/${statusResponse.requiredConfirmations}")

                        // If completed, refresh subscription status
                        if (statusResponse.status.equals("Completed", ignoreCase = true) ||
                            statusResponse.status.equals("Paid", ignoreCase = true)) {
                            lastSubscriptionCheckTime = 0 // Force cache refresh
                            fetchSubscriptionStatus()
                        }
                    } else {
                        _errorMessage.value = "Failed to check payment status: ${response.code()}"
                    }
                    _isLoading.value = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking Monero payment status", e)
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Error: ${e.localizedMessage}"
                    _isLoading.value = false
                }
            }
        }
    }

    /**
     * Cache subscription status to SharedPreferences
     */
    private fun cacheSubscriptionStatus(status: SubscriptionStatusResponse) {
        try {
            sharedPrefs.edit().apply {
                putString("subscription_plan", status.plan)
                putBoolean("subscription_is_pro", status.isPro)
                putString("subscription_status", status.status)
                putString("subscription_payment_type", status.paymentType ?: "")
                putString("subscription_period_end", status.currentPeriodEnd ?: "")
                putInt("subscription_active_devices", status.activeDevices)
                putInt("subscription_max_devices", status.maxDevices)
                putLong("subscription_cache_time", System.currentTimeMillis())
                apply()
            }
            Log.d(TAG, "Subscription status cached")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache subscription status: ${e.message}")
        }
    }

    /**
     * Restore cached subscription status from SharedPreferences
     */
    private fun restoreCachedSubscriptionStatus() {
        try {
            // SECURITY: Validate cache belongs to current user to prevent cross-user contamination
            if (currentUserId != null && !isCacheValidForCurrentUser()) {
                Log.w(TAG, "Cache belongs to different user, skipping restore")
                return
            }

            val plan = sharedPrefs.getString("subscription_plan", null) ?: return
            val isPro = sharedPrefs.getBoolean("subscription_is_pro", false)
            val status = sharedPrefs.getString("subscription_status", "Active") ?: "Active"
            val paymentType = sharedPrefs.getString("subscription_payment_type", null)
            val periodEnd = sharedPrefs.getString("subscription_period_end", null)
            val activeDevices = sharedPrefs.getInt("subscription_active_devices", 0)
            val maxDevices = sharedPrefs.getInt("subscription_max_devices", 1)

            val cached = SubscriptionStatusResponse(
                plan = plan,
                isPro = isPro,
                status = status,
                paymentType = paymentType,
                currentPeriodEnd = periodEnd,
                activeDevices = activeDevices,
                maxDevices = maxDevices,
                canAddDevice = activeDevices < maxDevices
            )

            _subscriptionStatus.value = cached
            _isPro.value = isPro
            Log.d(TAG, "Restored cached subscription status: $plan (isPro=$isPro)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore cached subscription: ${e.message}")
        }
    }

    /**
     * Refresh subscription status (bypass cache)
     */
    fun refreshSubscriptionStatus() {
        lastSubscriptionCheckTime = 0
        fetchJob?.cancel() // Cancel any in-flight request
        fetchSubscriptionStatus()
    }

    /**
     * Force immediate subscription status update
     * Call this after payment completion or subscription changes
     */
    fun subscriptionUpdated() {
        Log.d(TAG, "Subscription updated - forcing immediate refresh")
        lastSubscriptionCheckTime = 0
        fetchJob?.cancel()
        fetchSubscriptionStatus()
    }

    /**
     * Validate cache belongs to current user
     * Returns true if cache is valid for current user
     */
    private fun isCacheValidForCurrentUser(): Boolean {
        val cachedUserId = sharedPrefs.getString("subscription_user_id", null)
        return cachedUserId == null || cachedUserId == currentUserId
    }

    /**
     * Clear all subscription data and cached state
     */
    fun clearSubscriptionData() {
        _subscriptionStatus.value = null
        _isPro.value = false
        _checkoutUrl.value = null
        _moneroInvoice.value = null
        _moneroPaymentStatus.value = null
        _moneroPrice.value = null
        _hoursRemaining.value = 0
        _minutesRemaining.value = 0
        _errorMessage.value = null
        moneroPollingJob?.cancel()
        timerJob?.cancel()
        _isMoneroPolling.value = false
        lastSubscriptionCheckTime = 0

        sharedPrefs.edit().clear().apply()
        Log.d(TAG, "Subscription data cleared")
    }
}
