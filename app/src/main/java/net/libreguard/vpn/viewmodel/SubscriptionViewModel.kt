package net.libreguard.vpn.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import net.libreguard.vpn.network.RetrofitClient
import net.libreguard.vpn.network.SubscriptionStatusResponse
import net.libreguard.vpn.network.MoneroInvoiceResponse
import net.libreguard.vpn.network.MoneroStatusResponse
import net.libreguard.vpn.core.GooglePlayBillingManager
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

    // Google Play Billing
    val billingManager = GooglePlayBillingManager(application)

    // Emits true once a Google Play purchase is verified by the backend
    private val _googlePlayPurchaseSuccess = MutableStateFlow(false)
    val googlePlayPurchaseSuccess: StateFlow<Boolean> = _googlePlayPurchaseSuccess

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

    private val _paymentVerificationResult = MutableStateFlow<Boolean?>(null)
    val paymentVerificationResult: StateFlow<Boolean?> = _paymentVerificationResult

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

    private val _secondsRemaining = MutableStateFlow(0)
    val secondsRemaining: StateFlow<Int> = _secondsRemaining

    private var currentUserId: String? = null
    private var moneroPollingJob: Job? = null
    private var subscriptionPollingJob: Job? = null
    private var timerJob: Job? = null
    private var lastSubscriptionCheckTime = 0L

    // Track in-flight API request to prevent duplicate calls
    private var fetchJob: Job? = null

    init {
        // CRITICAL FIX: Load cached subscription status immediately on ViewModel creation
        // This prevents the UI from showing "Free" while waiting for API response
        restoreCachedSubscriptionStatus()
        Log.d(TAG, "SubscriptionViewModel initialized with cached status: isPro=${_isPro.value}")

        // Observe billing manager: when a Google Play purchase is verified, refresh subscription
        viewModelScope.launch {
            billingManager.billingState.collect { state ->
                if (state is GooglePlayBillingManager.BillingState.PurchaseSuccess) {
                    Log.d(TAG, "Google Play purchase verified — refreshing subscription status")
                    _googlePlayPurchaseSuccess.value = true
                    lastSubscriptionCheckTime = 0
                    fetchSubscriptionStatus(force = true)
                }
            }
        }
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
    fun fetchSubscriptionStatus(force: Boolean = false) {
        val authHeader = getAuthHeaderOrNull()
        if (authHeader == null) {
            _errorMessage.value = "Authentication required"
            return
        }

        // Check cache validity
        val now = System.currentTimeMillis()
        if (!force && now - lastSubscriptionCheckTime < SUBSCRIPTION_CACHE_TTL_MS) {
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
        Log.d(TAG, "fetchCheckoutUrl() called")
        if (authHeader == null) {
            _errorMessage.value = "Authentication required"
            Log.e(TAG, "fetchCheckoutUrl() failed: authToken is null")
            return
        }

        _isLoading.value = true
        _errorMessage.value = null
        Log.d(TAG, "Starting API call to fetch checkout URL")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Calling RetrofitClient.getCheckoutUrl()...")
                val response = RetrofitClient.instance.getCheckoutUrl(
                    authorization = authHeader
                )
                Log.d(TAG, "API response received")

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && response.body() != null) {
                        val checkoutUrl = response.body()!!.checkoutUrl
                        Log.d(TAG, "Checkout URL received")
                        _checkoutUrl.value = checkoutUrl
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
     * Verify payment with Lemon Squeezy using order ID
     * This triggers the "Self-Healing" mechanism on the backend
     */
    fun verifyPayment(orderId: String) {
        val authHeader = getAuthHeaderOrNull()
        if (authHeader == null) {
            _errorMessage.value = "Authentication required"
            return
        }

        _isLoading.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Verifying payment")
                val response = RetrofitClient.instance.checkPaymentStatus(
                    authorization = authHeader,
                    orderId = orderId
                )

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && response.body() != null) {
                        val status = response.body()!!
                        Log.d(TAG, "Payment verification result")

                        if (status.found && status.status == "paid") {
                            _paymentVerificationResult.value = true
                            // Refresh subscription status immediately
                            fetchSubscriptionStatus()
                        } else if (status.found && status.status == "Succeeded") { // Handle Lemon Squeezy typical status
                            _paymentVerificationResult.value = true
                            fetchSubscriptionStatus()
                        } else {
                            _errorMessage.value = "Payment status: ${status.status}"
                            _paymentVerificationResult.value = false
                        }
                    } else {
                        Log.w(TAG, "Payment verification failed: ${response.code()}")
                        _errorMessage.value = "Verification failed: ${response.code()}"
                        _paymentVerificationResult.value = false
                    }
                    _isLoading.value = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error verifying payment", e)
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Error: ${e.localizedMessage}"
                    _isLoading.value = false
                    _paymentVerificationResult.value = false
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

                        // CRITICAL: Fetch status to get expiresAt (create-invoice doesn't return it per API spec)
                        fetchMoneroStatusAndStartTimer(invoice.invoiceId)
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
     * Since latest-invoice endpoint now returns expiresAt directly, we can start the timer immediately
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

                        // If server ever returns an already-expired invoice, immediately request a fresh one
                        val isExpired = try {
                            invoice.expiresAt?.let {
                                val expirationTime = java.time.Instant.parse(it).toEpochMilli()
                                expirationTime <= System.currentTimeMillis()
                            } ?: false
                        } catch (_: Exception) { false }

                        if (isExpired) {
                            Log.w(TAG, "latest-invoice returned expired invoice ${invoice.invoiceId}; requesting new one")
                            stopMoneroPolling()
                            createMoneroInvoice()
                            return@withContext
                        }

                        _moneroInvoice.value = invoice
                        Log.d(TAG, "Latest Monero invoice fetched: ${invoice.invoiceId}, expiresAt: ${invoice.expiresAt}")

                        // Latest-invoice now returns expiresAt, so we can start timer directly
                        if (invoice.expiresAt != null) {
                            startExpirationTimer(invoice.expiresAt)
                            fetchMoneroStatusForPolling(invoice.invoiceId)
                        } else {
                            Log.w(TAG, "expiresAt missing from latest-invoice, fetching status")
                            fetchMoneroStatusAndStartTimer(invoice.invoiceId)
                        }
                        _isLoading.value = false
                    } else if (response.code() == 404) {
                        Log.d(TAG, "No pending invoice found, creating new one")
                        createMoneroInvoice()
                    } else {
                        _errorMessage.value = "Failed to fetch invoice: ${response.code()}"
                        _isLoading.value = false
                    }
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
     * Fetch Monero payment status only for confirmations and start polling
     * Used when we already have expiresAt from latest-invoice
     */
    private suspend fun fetchMoneroStatusForPolling(invoiceId: String) {
        try {
            val response = RetrofitClient.instance.getMoneroPaymentStatus(
                authorization = getAuthHeaderOrNull()!!,
                invoiceId = invoiceId
            )

            if (response.isSuccessful && response.body() != null) {
                val statusResponse = response.body()!!
                withContext(Dispatchers.Main) {
                    _moneroPaymentStatus.value = statusResponse
                    Log.d(TAG, "Monero status: ${statusResponse.status}, confirmations: ${statusResponse.confirmations}/${statusResponse.requiredConfirmations}")

                    // Start polling for payment updates (timer already started from latest-invoice expiresAt)
                    startMoneroPaymentPolling(invoiceId)
                }
            } else {
                Log.e(TAG, "Failed to fetch Monero status: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Monero status for polling", e)
        }
    }

    /**
     * Fetch Monero payment status, start timer, and begin polling
     * Called after creating an invoice (create-invoice doesn't return expiresAt)
     */
    private suspend fun fetchMoneroStatusAndStartTimer(invoiceId: String) {
        try {
            val response = RetrofitClient.instance.getMoneroPaymentStatus(
                authorization = getAuthHeaderOrNull()!!,
                invoiceId = invoiceId
            )

            if (response.isSuccessful && response.body() != null) {
                val statusResponse = response.body()!!

                // CRITICAL: Check if invoice is already expired before starting timer
                val isExpired = try {
                    statusResponse.expiresAt?.let {
                        val expirationTime = java.time.Instant.parse(it).toEpochMilli()
                        expirationTime <= System.currentTimeMillis()
                    } ?: false
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking status expiration: ${e.message}")
                    false
                }

                withContext(Dispatchers.Main) {
                    _moneroPaymentStatus.value = statusResponse
                    Log.d(TAG, "Monero status: ${statusResponse.status}, confirmations: ${statusResponse.confirmations}/${statusResponse.requiredConfirmations}, expiresAt: ${statusResponse.expiresAt}, isExpired: $isExpired")

                    if (isExpired) {
                        Log.w(TAG, "Status shows invoice expired; requesting new invoice")
                        stopMoneroPolling()
                        _isLoading.value = false
                        createMoneroInvoice()
                    } else {
                        startExpirationTimer(statusResponse.expiresAt)
                        startMoneroPaymentPolling(invoiceId)
                    }
                }
            } else {
                Log.e(TAG, "Failed to fetch Monero status: ${response.code()}")
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Failed to verify invoice status"
                    _isLoading.value = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Monero status", e)
            withContext(Dispatchers.Main) {
                _errorMessage.value = "Error: ${e.localizedMessage}"
                _isLoading.value = false
            }
        }
    }

    /**
     * Start countdown timer for invoice expiration
     * Updates every 1 second to display real-time HH:MM:SS countdown
     * Server-provided expiresAt is createdAt + 24 hours (per API spec)
     */
    private fun startExpirationTimer(expiresAt: String?) {
        timerJob?.cancel()

        if (expiresAt == null) {
            _hoursRemaining.value = 0
            _minutesRemaining.value = 0
            _secondsRemaining.value = 0
            return
        }

        timerJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val expirationTime = java.time.Instant.parse(expiresAt).toEpochMilli()
                Log.d(TAG, "Timer started for expiration")

                while (true) {
                    val remainingMs = expirationTime - System.currentTimeMillis()

                    // Stop immediately if already expired
                    if (remainingMs <= 0) {
                        withContext(Dispatchers.Main) {
                            _hoursRemaining.value = 0
                            _minutesRemaining.value = 0
                            _secondsRemaining.value = 0
                            Log.d(TAG, "Invoice expiration timer completed")
                        }
                        break
                    }

                    val totalSeconds = (remainingMs / 1000).toInt().coerceAtLeast(0)
                    val hours = totalSeconds / 3600
                    val minutes = (totalSeconds % 3600) / 60
                    val seconds = totalSeconds % 60

                    withContext(Dispatchers.Main) {
                        _hoursRemaining.value = hours
                        _minutesRemaining.value = minutes
                        _secondsRemaining.value = seconds
                        Log.d(TAG, "Timer tick: ${String.format("%02d:%02d:%02d", hours, minutes, seconds)}")
                    }

                    delay(1000L)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing expiration time or running timer", e)
                withContext(Dispatchers.Main) {
                    _hoursRemaining.value = 0
                    _minutesRemaining.value = 0
                    _secondsRemaining.value = 0
                }
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

            try {
                while (System.currentTimeMillis() - startTime < maxPollingDuration) {
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
                        val isSucceeded = statusResponse.status.equals("Succeeded", ignoreCase = true)
                        val isTerminalFailure = statusResponse.status.equals("Expired", ignoreCase = true) ||
                            statusResponse.status.equals("Failed", ignoreCase = true)
                        val shouldStop = isSucceeded || isTerminalFailure

                        withContext(Dispatchers.Main) {
                            _moneroPaymentStatus.value = statusResponse
                            Log.d(TAG, "Monero payment status: ${statusResponse.status}, confirmations: ${statusResponse.confirmations}/${statusResponse.requiredConfirmations}")

                            if (shouldStop) {
                                _isMoneroPolling.value = false
                                if (isSucceeded) {
                                    // Refresh subscription status after successful payment
                                    lastSubscriptionCheckTime = 0 // Force cache refresh
                                    fetchSubscriptionStatus()
                                }
                            }
                        }

                        if (shouldStop) break
                    } else {
                        Log.w(TAG, "Failed to check payment status: ${response.code()}")
                    }
                }

                // Polling timeout (only if not already stopped)
                if (_isMoneroPolling.value) {
                    withContext(Dispatchers.Main) {
                        _isMoneroPolling.value = false
                        _errorMessage.value = "Payment verification timeout. Please check your transaction."
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normal cancellation when job is stopped - don't log as error
                Log.d(TAG, "Monero polling cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error polling payment status", e)
                withContext(Dispatchers.Main) {
                    _isMoneroPolling.value = false
                }
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

                        if (statusResponse.status.equals("Succeeded", ignoreCase = true)) {
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
                // Redacted plan and status storage
                putBoolean("subscription_is_pro", status.isPro)
                // Redacted and masked other fields
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

            // Redacted plan/status/dates from restoration

            val isPro = sharedPrefs.getBoolean("subscription_is_pro", false)
            val activeDevices = sharedPrefs.getInt("subscription_active_devices", 0)
            val maxDevices = sharedPrefs.getInt("subscription_max_devices", 1)

            val cached = SubscriptionStatusResponse(
                plan = "Restored", // Masked
                isPro = isPro,
                status = "Active", // Generic
                paymentType = null,
                currentPeriodEnd = null,
                activeDevices = activeDevices,
                maxDevices = maxDevices,
                canAddDevice = activeDevices < maxDevices
            )

            _subscriptionStatus.value = cached
            _isPro.value = isPro
            Log.d(TAG, "Restored cached subscription status (isPro=$isPro)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore cached subscription")
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
     * Mark the user as Pro immediately after a verified purchase so UI/access checks update
     * before the follow-up subscription fetch completes.
     */
    fun markPurchaseVerified() {
        _isPro.value = true
        try {
            sharedPrefs.edit().putBoolean("subscription_is_pro", true).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist optimistic Pro state: ${e.message}")
        }
        Log.d(TAG, "Marked subscription as Pro locally after verified purchase")
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
     * Start polling subscription status
     * Used during payment flows to detect upgrade completion
     */
    fun startPollingSubscriptionStatus() {
        if (subscriptionPollingJob?.isActive == true) return

        subscriptionPollingJob = viewModelScope.launch {
            Log.d(TAG, "Starting subscription status polling")
            while (true) {
                // Force fetch ignoring cache TTL
                fetchSubscriptionStatus(force = true)
                // Stop if upgraded to Pro
                if (_isPro.value) {
                    Log.d(TAG, "User is PRO, stopping polling")
                    break
                }
                delay(5000) // Poll every 5 seconds
            }
        }
    }

    /**
     * Stop subscription status polling
     */
    fun stopPollingSubscriptionStatus() {
        if (subscriptionPollingJob?.isActive == true) {
            Log.d(TAG, "Stopping subscription status polling")
            subscriptionPollingJob?.cancel()
            subscriptionPollingJob = null
        }
    }

    /**
     * Clear all subscription data and cached state
     */
    fun clearSubscriptionData() {
        // Redacted resetting fields
        _isPro.value = false
        _checkoutUrl.value = null
        _moneroInvoice.value = null
        _moneroPaymentStatus.value = null
        _moneroPrice.value = null
        _hoursRemaining.value = 0
        _minutesRemaining.value = 0
        _secondsRemaining.value = 0
        _errorMessage.value = null
        _googlePlayPurchaseSuccess.value = false
        moneroPollingJob?.cancel()
        subscriptionPollingJob?.cancel()
        timerJob?.cancel()
        _isMoneroPolling.value = false
        lastSubscriptionCheckTime = 0

        sharedPrefs.edit().clear().apply()
        Log.d(TAG, "Subscription data cleared")
    }

    override fun onCleared() {
        super.onCleared()
        billingManager.disconnect()
    }
}
