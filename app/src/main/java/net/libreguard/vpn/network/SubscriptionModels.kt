package net.libreguard.vpn.network

import com.google.gson.annotations.SerializedName

// ===== SUBSCRIPTION STATUS =====
data class SubscriptionStatusResponse(
    @SerializedName("plan")
    val plan: String, // "Free" | "Pro"
    @SerializedName("isPro")
    val isPro: Boolean,
    @SerializedName("status")
    val status: String, // "Active" | "Canceled" | "Trialing" | "PastDue"
    @SerializedName("paymentType")
    val paymentType: String?, // "Card" | "Monero" | null
    @SerializedName("currentPeriodEnd")
    val currentPeriodEnd: String?, // ISO 8601 datetime
    @SerializedName("cancelAtPeriodEnd")
    val cancelAtPeriodEnd: Boolean = false,
    @SerializedName("activeDevices")
    val activeDevices: Int,
    @SerializedName("maxDevices")
    val maxDevices: Int,
    @SerializedName("canAddDevice")
    val canAddDevice: Boolean
)

// ===== DEVICE REGISTRATION =====
data class RegisterDeviceRequest(
    @SerializedName("deviceId")
    val deviceId: String,
    @SerializedName("deviceName")
    val deviceName: String,
    @SerializedName("deviceType")
    val deviceType: String,
    @SerializedName("osVersion")
    val osVersion: String,
    @SerializedName("appVersion")
    val appVersion: String
)

data class DeviceResponse(
    @SerializedName("message")
    val message: String,
    @SerializedName("deviceId")
    val deviceId: String,
    @SerializedName("isNewDevice")
    val isNewDevice: Boolean
)

// ===== SERVER ACCESS CHECK =====
data class AccessCheckResponse(
    @SerializedName("canAccess")
    val canAccess: Boolean,
    @SerializedName("serverTier")
    val serverTier: String,
    @SerializedName("requiresPro")
    val requiresPro: Boolean
)

// ===== CARD PAYMENT (LEMONSQUEEZY) =====
data class CheckoutUrlResponse(
    @SerializedName("checkoutUrl")
    val checkoutUrl: String,
    @SerializedName("userId")
    val userId: String
)

data class PaymentStatusResponse(
    @SerializedName("found")
    val found: Boolean,
    @SerializedName("status")
    val status: String,
    @SerializedName("paidAt")
    val paidAt: String?,
    @SerializedName("message")
    val message: String?,
    @SerializedName("recovered")
    val recovered: Boolean
)

// ===== MONERO PAYMENT =====
data class MoneroPriceResponse(
    @SerializedName("xmrAmount")
    val xmrAmount: Double,
    @SerializedName("usdAmount")
    val usdAmount: Double,
    @SerializedName("xmrPriceUsd")
    val xmrPriceUsd: Double,
    @SerializedName("currency")
    val currency: String,
    @SerializedName("product")
    val product: String
)

data class MoneroInvoiceResponse(
    @SerializedName("invoiceId")
    val invoiceId: String,
    @SerializedName("paymentAddress")
    val paymentAddress: String,
    @SerializedName("amount")
    val amount: Double,
    @SerializedName("currency")
    val currency: String,
    @SerializedName("status")
    val status: String, // "Pending" | "Completed" | "Failed"
    @SerializedName("description")
    val description: String,
    @SerializedName("createdAt")
    val createdAt: String,
    @SerializedName("expiresAt")
    val expiresAt: String? = null
)

data class MoneroStatusResponse(
    @SerializedName("invoiceId")
    val invoiceId: String,
    @SerializedName("status")
    val status: String, // "Pending" | "Completed" | "Failed"
    @SerializedName("amountRequired")
    val amountRequired: Double,
    @SerializedName("amountReceived")
    val amountReceived: Double,
    @SerializedName("confirmations")
    val confirmations: Int,
    @SerializedName("requiredConfirmations")
    val requiredConfirmations: Int,
    @SerializedName("createdAt")
    val createdAt: String,
    @SerializedName("expiresAt")
    val expiresAt: String
)

// ===== DATA USAGE QUOTA =====
data class QuotaResponse(
    @SerializedName("bytesUsed")
    val bytesUsed: Long,
    @SerializedName("bytesLimit")
    val bytesLimit: Long?,
    @SerializedName("bytesRemaining")
    val bytesRemaining: Long?,
    @SerializedName("usagePercentage")
    val usagePercentage: Double?,
    @SerializedName("isUnlimited")
    val isUnlimited: Boolean,
    @SerializedName("isOverLimit")
    val isOverLimit: Boolean = false,
    @SerializedName("formattedUsed")
    val formattedUsed: String? = null,
    @SerializedName("formattedLimit")
    val formattedLimit: String? = null,
    @SerializedName("formattedRemaining")
    val formattedRemaining: String? = null,
    @SerializedName("cycleStart")
    val cycleStart: String? = null,
    @SerializedName("cycleEnd")
    val cycleEnd: String? = null,
    @SerializedName("resetDate")
    val resetDate: String? = null
)

// ===== CAN CONNECT PRE-FLIGHT CHECK =====
data class CanConnectResponse(
    @SerializedName("allowed")
    val allowed: Boolean,
    @SerializedName("reason")
    val reason: String? = null,
    @SerializedName("bytesUsed")
    val bytesUsed: Long? = null,
    @SerializedName("bytesLimit")
    val bytesLimit: Long? = null,
    @SerializedName("resetDate")
    val resetDate: String? = null,
    @SerializedName("isUnlimited")
    val isUnlimited: Boolean = false,
    @SerializedName("message")
    val message: String? = null
)

// ===== GOOGLE PLAY BILLING =====
data class VerifyGooglePlayRequest(
    @SerializedName(value = "subscriptionId", alternate = ["SubscriptionId"])
    val subscriptionId: String,
    @SerializedName(value = "purchaseToken", alternate = ["PurchaseToken"])
    val purchaseToken: String
)

data class VerifyGooglePlayResponse(
    @SerializedName("status")
    val status: String,          // "success" | "pending" | "error"
    @SerializedName("message")
    val message: String? = null,
    @SerializedName("isPro")
    val isPro: Boolean = false,
    @SerializedName(value = "currentPeriodEnd", alternate = ["expiryDate"])
    val currentPeriodEnd: String? = null
)

