package net.libreguard.vpn.core

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.libreguard.vpn.BuildConfig
import net.libreguard.vpn.network.RetrofitClient
import net.libreguard.vpn.network.VerifyGooglePlayRequest

private const val TAG = "GooglePlayBillingMgr"

/**
 * Manages all interactions with the Google Play Billing Library.
 *
 * Usage:
 *  1. Call [connect] once (e.g. in Activity.onStart or ViewModel init).
 *  2. Observe [billingState] to react to purchase results.
 *  3. Call [launchPurchaseFlow] from a UI event to open the Play Store sheet.
 *  4. Call [disconnect] when the lifecycle owner is destroyed.
 */
class GooglePlayBillingManager(private val context: Context) {

    // ── Public state ─────────────────────────────────────────────────────────

    sealed class BillingState {
        object Idle : BillingState()
        object Connecting : BillingState()
        object Connected : BillingState()
        object PurchasePending : BillingState()
        /** Purchase acknowledged and verified by backend — user is now Pro. */
        object PurchaseSuccess : BillingState()
        data class Error(val message: String) : BillingState()
    }

    private val _billingState = MutableStateFlow<BillingState>(BillingState.Idle)
    val billingState: StateFlow<BillingState> = _billingState

    private val _productDetails = MutableStateFlow<ProductDetails?>(null)
    val productDetails: StateFlow<ProductDetails?> = _productDetails

    // ── Internal ──────────────────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (purchases != null) {
                    purchases.forEach { purchase ->
                        scope.launch { handlePurchase(purchase) }
                    }
                } else {
                    Log.w(TAG, "OK response but null purchases list")
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.i(TAG, "User cancelled the purchase flow")
                _billingState.value = BillingState.Idle
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                Log.i(TAG, "Item already owned — querying existing purchases")
                scope.launch { queryExistingPurchases() }
            }
            else -> {
                val msg = "Billing error: ${billingResult.responseCode} — ${billingResult.debugMessage}"
                Log.e(TAG, msg)
                _billingState.value = BillingState.Error(msg)
            }
        }
    }

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Connect to Google Play. Safe to call multiple times — re-connects only if disconnected.
     */
    fun connect() {
        if (billingClient.isReady) {
            _billingState.value = BillingState.Connected
            scope.launch { loadProductDetails() }
            return
        }
        _billingState.value = BillingState.Connecting
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "BillingClient connected")
                    _billingState.value = BillingState.Connected
                    scope.launch {
                        loadProductDetails()
                        queryExistingPurchases()
                    }
                } else {
                    val msg = "BillingClient setup failed: ${billingResult.responseCode}"
                    Log.e(TAG, msg)
                    _billingState.value = BillingState.Error(msg)
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "BillingClient disconnected — will retry on next connect()")
                _billingState.value = BillingState.Idle
            }
        })
    }

    fun disconnect() {
        billingClient.endConnection()
        scope.cancel()
    }

    // ── Product details ───────────────────────────────────────────────────────

    private suspend fun loadProductDetails() {
        val productId = BuildConfig.GOOGLE_PLAY_PRODUCT_ID
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        val result = billingClient.queryProductDetails(params)
        if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            val details = result.productDetailsList?.firstOrNull()
            _productDetails.value = details
            if (details == null) {
                Log.w(TAG, "No ProductDetails returned for $productId — ensure it is active in Play Console")
            } else {
                Log.d(TAG, "ProductDetails loaded: ${details.title}")
            }
        } else {
            Log.e(TAG, "queryProductDetails failed: ${result.billingResult.responseCode}")
        }
    }

    // ── Purchase flow ─────────────────────────────────────────────────────────

    /**
     * Opens the Google Play subscription purchase sheet.
     * Must be called from the UI thread with a valid [Activity].
     */
    fun launchPurchaseFlow(activity: Activity) {
        val details = _productDetails.value
        if (details == null) {
            _billingState.value = BillingState.Error("Product details not yet loaded. Please try again.")
            return
        }

        val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken
        if (offerToken == null) {
            _billingState.value = BillingState.Error("No subscription offer available.")
            return
        }

        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .setOfferToken(offerToken)
                .build()
        )

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        val result = billingClient.launchBillingFlow(activity, billingFlowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            val msg = "launchBillingFlow failed: ${result.responseCode} — ${result.debugMessage}"
            Log.e(TAG, msg)
            _billingState.value = BillingState.Error(msg)
        }
    }

    // ── Handle purchase ───────────────────────────────────────────────────────

    private suspend fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
            Log.d(TAG, "Purchase state is ${purchase.purchaseState} — waiting for PURCHASED")
            _billingState.value = BillingState.PurchasePending
            return
        }

        Log.d(TAG, "Processing purchase: token=${purchase.purchaseToken.take(30)}…")

        // 1. Verify with backend
        val productId = purchase.products.firstOrNull() ?: BuildConfig.GOOGLE_PLAY_PRODUCT_ID
        val verified = verifyWithBackend(productId, purchase.purchaseToken)

        if (verified) {
            // 2. Acknowledge to Google (required within 3 days or purchase is refunded)
            if (!purchase.isAcknowledged) {
                val ackParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                val ackResult = billingClient.acknowledgePurchase(ackParams)
                if (ackResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "Purchase acknowledged with Google Play")
                } else {
                    Log.e(TAG, "Acknowledgement failed: ${ackResult.responseCode} — ${ackResult.debugMessage}")
                    // Backend is already updated; acknowledgement will be retried on next app open
                }
            }
            _billingState.value = BillingState.PurchaseSuccess
        } else {
            _billingState.value = BillingState.Error("Purchase verification failed. Please contact support.")
        }
    }

    /**
     * Sends the purchase token to the LibreGuard backend for server-side validation.
     * Returns true if the backend confirms the purchase as valid.
     */
    private suspend fun verifyWithBackend(subscriptionId: String, purchaseToken: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val token = RetrofitClient.getTokenManager().getAccessToken()
                if (token.isNullOrBlank()) {
                    Log.e(TAG, "verifyWithBackend: no auth token available")
                    return@withContext false
                }
                val response = RetrofitClient.instance.verifyGooglePlayPurchase(
                    authorization = "Bearer $token",
                    request = VerifyGooglePlayRequest(
                        subscriptionId = subscriptionId,
                        purchaseToken = purchaseToken
                    )
                )
                if (response.isSuccessful && response.body()?.status == "success") {
                    Log.d(TAG, "Backend verification succeeded")
                    true
                } else {
                    Log.e(TAG, "Backend verification failed: ${response.code()} ${response.body()?.message}")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Backend verification exception: ${e.message}", e)
                false
            }
        }
    }

    // ── Existing purchases ────────────────────────────────────────────────────

    /**
     * Checks for purchases the user already owns (e.g. app re-install, ITEM_ALREADY_OWNED).
     * Re-verifies any unacknowledged purchases.
     */
    private suspend fun queryExistingPurchases() {
        val result = billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )
        if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            val activePurchases = result.purchasesList.filter { p ->
                p.purchaseState == Purchase.PurchaseState.PURCHASED
            }
            if (activePurchases.isEmpty()) {
                Log.d(TAG, "No active Google Play subscriptions found")
                return
            }
            Log.d(TAG, "Found ${activePurchases.size} active purchase(s) — verifying…")
            activePurchases.forEach { purchase ->
                if (!purchase.isAcknowledged) {
                    scope.launch { handlePurchase(purchase) }
                } else {
                    // Already acknowledged — silently emit success so UI reflects Pro status
                    _billingState.value = BillingState.PurchaseSuccess
                }
            }
        } else {
            Log.w(TAG, "queryPurchasesAsync failed: ${result.billingResult.responseCode}")
        }
    }
}

