package net.libreguard.vpn.network

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GooglePlayContractModelsTest {
    private val gson = Gson()

    @Test
    fun verifyGooglePlayRequest_serializesBackendFieldNames() {
        val json = gson.toJson(
            VerifyGooglePlayRequest(
                subscriptionId = "pro_monthly",
                purchaseToken = "purchase_token_from_google_play"
            )
        )
        val obj = JsonParser.parseString(json).asJsonObject

        assertEquals("pro_monthly", obj.get("subscriptionId").asString)
        assertEquals("purchase_token_from_google_play", obj.get("purchaseToken").asString)
        assertFalse(obj.has("SubscriptionId"))
        assertFalse(obj.has("PurchaseToken"))
    }

    @Test
    fun verifyGooglePlayResponse_deserializesBackendSuccessShape() {
        val response = gson.fromJson(
            """
            {
              "status": "success",
              "isPro": true,
              "currentPeriodEnd": "2026-05-01T12:34:56Z"
            }
            """.trimIndent(),
            VerifyGooglePlayResponse::class.java
        )

        assertEquals("success", response.status)
        assertTrue(response.isPro)
        assertEquals("2026-05-01T12:34:56Z", response.currentPeriodEnd)
    }

    @Test
    fun verifyGooglePlayResponse_deserializesPendingShape() {
        val response = gson.fromJson(
            """
            {
              "status": "pending",
              "message": "Payment is pending. Please check back later."
            }
            """.trimIndent(),
            VerifyGooglePlayResponse::class.java
        )

        assertEquals("pending", response.status)
        assertEquals("Payment is pending. Please check back later.", response.message)
        assertFalse(response.isPro)
    }

    @Test
    fun verifyGooglePlayResponse_supportsLegacyFieldNames() {
        val request = gson.fromJson(
            """
            {
              "SubscriptionId": "legacy_plan",
              "PurchaseToken": "legacy_token"
            }
            """.trimIndent(),
            VerifyGooglePlayRequest::class.java
        )
        val response = gson.fromJson(
            """
            {
              "status": "success",
              "isPro": true,
              "expiryDate": "2026-06-01T00:00:00Z"
            }
            """.trimIndent(),
            VerifyGooglePlayResponse::class.java
        )

        assertEquals("legacy_plan", request.subscriptionId)
        assertEquals("legacy_token", request.purchaseToken)
        assertEquals("2026-06-01T00:00:00Z", response.currentPeriodEnd)
    }
}

