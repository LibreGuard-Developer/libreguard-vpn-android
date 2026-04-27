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

    private sealed class BackendVerificationResult {
        data class Success(val currentPeriodEnd: String?) : BackendVerificationResult()
        data class Pending(val message: String) : BackendVerificationResult()
        data class Error(val message: String) : BackendVerificationResult()
    }

    data class SubscriptionOption(
        val productDetails: ProductDetails,
        val offerToken: String,
        val basePlanId: String,
        val offerId: String?,
        val formattedPrice: String,
        val title: String
    )

    // ── Public state ─────────────────────────────────────────────────────────

    sealed class BillingState {
        object Idle : BillingState()
        object Connecting : BillingState()
        object Connected : BillingState()
        data class PurchasePending(val message: String) : BillingState()
        /** Purchase acknowledged and verified by backend — user is now Pro. */
        object PurchaseSuccess : BillingState()
        data class Error(val message: String) : BillingState()
    }

    private val _billingState = MutableStateFlow<BillingState>(BillingState.Idle)
    val billingState: StateFlow<BillingState> = _billingState

    private val _productDetailsList = MutableStateFlow<List<ProductDetails>>(emptyList())
    val productDetailsList: StateFlow<List<ProductDetails>> = _productDetailsList

    private val _subscriptionOptions = MutableStateFlow<List<SubscriptionOption>>(emptyList())
    val subscriptionOptions: StateFlow<List<SubscriptionOption>> = _subscriptionOptions

    // ── Internal ──────────────────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        Log.d(TAG, "PurchasesUpdatedListener called: responseCode=${billingResult.responseCode}, debugMessage=${billingResult.debugMessage}")
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (purchases != null) {
                    purchases.forEach { purchase ->
                        Log.d(
                            TAG,
                            "Purchase callback: token=${purchase.purchaseToken}, products=${purchase.products}"
                        )
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
        val productIds = listOf(
            BuildConfig.GOOGLE_PLAY_PRODUCT_ID
        ).distinct()

        val productList = productIds.map { productId ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        val result = billingClient.queryProductDetails(params)
        if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            val detailsList = result.productDetailsList ?: emptyList()
            _productDetailsList.value = detailsList

            val options = mutableListOf<SubscriptionOption>()
            detailsList.forEach { product ->
                product.subscriptionOfferDetails?.forEach { offer ->
                    val isTrial = offer.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L }
                    val recurringPhase = offer.pricingPhases.pricingPhaseList.lastOrNull()
                    val price = recurringPhase?.formattedPrice ?: ""
                    
                    val title = when {
                        offer.basePlanId.contains("yearly") -> if (isTrial) "Yearly Pro (Trial)" else "Yearly Pro"
                        else -> if (isTrial) "Monthly Pro (Trial)" else "Monthly Pro"
                    }

                    // Avoid duplicate base plans without offers if an offer exists for the same duration
                    options.add(
                        SubscriptionOption(
                            productDetails = product,
                            offerToken = offer.offerToken,
                            basePlanId = offer.basePlanId,
                            offerId = offer.offerId,
                            formattedPrice = price,
                            title = title
                        )
                    )
                }
            }

            // Filter options to show the best ones (prefer v2/offers)
            val filteredOptions = options.groupBy { 
                if (it.basePlanId.contains("yearly")) "yearly" else "monthly" 
            }.map { (_, group) ->
                // Prefer offers with trials or discounts over just base plans
                group.maxByOrNull { it.offerId?.length ?: 0 } ?: group.first()
            }.sortedBy { it.formattedPrice.replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: 0.0 }

            _subscriptionOptions.value = filteredOptions

            if (detailsList.isEmpty()) {
                Log.w(TAG, "No ProductDetails returned for products $productIds — ensure they are active in Play Console")
            } else {
                Log.d(TAG, "Loaded ${detailsList.size} ProductDetails, Extracted ${filteredOptions.size} Offers")
            }
        } else {
            Log.e(TAG, "queryProductDetails failed: ${result.billingResult.responseCode}")
        }
    }

    // ── Purchase flow ─────────────────────────────────────────────────────────

    /**
     * Opens the Google Play subscription purchase sheet for a specific product and offer.
     * Must be called from the UI thread with a valid [Activity].
     */
    fun launchPurchaseFlow(activity: Activity, productDetails: ProductDetails, offerToken: String? = null) {
        val selectedOfferToken = offerToken ?: productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken
        
        if (selectedOfferToken == null) {
            _billingState.value = BillingState.Error("No valid offer found for this product.")
            return
        }

        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(productDetails)
                .setOfferToken(selectedOfferToken)
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

    /**
     * Legacy method for backward compatibility if needed, though we should update callers.
     */
    fun launchPurchaseFlow(activity: Activity) {
        val details = _productDetailsList.value.firstOrNull()
        if (details == null) {
            _billingState.value = BillingState.Error("Product details not yet loaded. Please try again.")
            return
        }
        launchPurchaseFlow(activity, details)
    }

    // ── Handle purchase ───────────────────────────────────────────────────────

    private suspend fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            Log.d(TAG, "Purchase is pending approval/payment")
            _billingState.value = BillingState.PurchasePending(
                "Payment is pending. Please check back later."
            )
            return
        }

        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
            Log.d(TAG, "Purchase state is ${purchase.purchaseState} — waiting for PURCHASED")
            _billingState.value = BillingState.PurchasePending(
                "Purchase is not complete yet. Please try again in a moment."
            )
            return
        }

        Log.d(TAG, "Processing purchase: token=${purchase.purchaseToken.take(30)}…")
        Log.d(TAG, "Backend verification payload: subscriptionId=${purchase.products.firstOrNull() ?: BuildConfig.GOOGLE_PLAY_BACKEND_SUBSCRIPTION_ID}, purchaseToken=${purchase.purchaseToken}, products=${purchase.products}")

        // 1. Verify with backend
        val actualSubscriptionId = purchase.products.firstOrNull() ?: BuildConfig.GOOGLE_PLAY_BACKEND_SUBSCRIPTION_ID
        when (val verificationResult = verifyWithBackend(
            actualSubscriptionId,
            purchase.purchaseToken
        )) {
            is BackendVerificationResult.Success -> {
                Log.d(TAG, "Purchase verified by backend; currentPeriodEnd=${verificationResult.currentPeriodEnd}")

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
            }
            is BackendVerificationResult.Pending -> {
                Log.i(TAG, "Purchase verification pending: ${verificationResult.message}")
                _billingState.value = BillingState.PurchasePending(verificationResult.message)
            }
            is BackendVerificationResult.Error -> {
                Log.e(TAG, "Purchase verification failed: ${verificationResult.message}")
                _billingState.value = BillingState.Error(verificationResult.message)
            }
        }
    }

    /**
     * Sends the purchase token to the LibreGuard backend for server-side validation.
     * Returns true if the backend confirms the purchase as valid.
     */
    private suspend fun verifyWithBackend(
        subscriptionId: String,
        purchaseToken: String
    ): BackendVerificationResult {
        return withContext(Dispatchers.IO) {
            try {
                val token = RetrofitClient.getTokenManager().getAccessToken()
                if (token.isNullOrBlank()) {
                    Log.e(TAG, "verifyWithBackend: no auth token available")
                    return@withContext BackendVerificationResult.Error("Authentication required. Please sign in again.")
                }
                val response = RetrofitClient.instance.verifyGooglePlayPurchase(
                    authorization = "Bearer $token",
                    request = VerifyGooglePlayRequest(
                        subscriptionId = subscriptionId,
                        purchaseToken = purchaseToken
                    )
                )

                val body = response.body()
                val status = body?.status?.lowercase()

                if (response.isSuccessful && status == "success") {
                    Log.d(TAG, "Backend verification succeeded")
                    BackendVerificationResult.Success(body.currentPeriodEnd)
                } else if (response.isSuccessful && status == "pending") {
                    BackendVerificationResult.Pending(
                        body.message ?: "Payment is pending. Please check back later."
                    )
                } else if (response.code() == 400) {
                    BackendVerificationResult.Error(
                        body?.message
                            ?: "Invalid purchase token or failed to verify with Google Play."
                    )
                } else {
                    val message = body?.message
                        ?: "Purchase verification failed (${response.code()}). Please try again."
                    Log.e(TAG, "Backend verification failed: ${response.code()} $message")
                    BackendVerificationResult.Error(message)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Backend verification exception: ${e.message}", e)
                BackendVerificationResult.Error(
                    e.localizedMessage ?: "Unable to verify purchase right now. Please try again."
                )
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
                scope.launch { handlePurchase(purchase) }
            }
        } else {
            Log.w(TAG, "queryPurchasesAsync failed: ${result.billingResult.responseCode}")
        }
    }
}
