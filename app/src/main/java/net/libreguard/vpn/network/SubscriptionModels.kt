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

// ===== MONERO PAYMENT =====
data class MoneroPriceResponse(
    @SerializedName("xmrAmount")
    val xmrAmount: Double,
    @SerializedName("usdAmount")
    val usdAmount: Double,
    @SerializedName("xmrPriceUsd")
    val xmrPriceUsd: Double?,
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
    val createdAt: String
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

