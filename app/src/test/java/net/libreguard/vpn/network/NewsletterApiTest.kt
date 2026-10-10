package net.libreguard.vpn.network

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import net.libreguard.vpn.util.AccountSessionContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class NewsletterApiTest {
    @Test fun account_preference_writes_send_revision_and_exact_explicit_consent() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val api = Retrofit.Builder().baseUrl(server.url("/"))
                .addConverterFactory(GsonConverterFactory.create()).build().create(ApiService::class.java)
            val session = AccountSessionContext("account-a", 1)
            val settings = NewsletterSettingsResponse("account-a", false, true, true,
                "LibreGuard news and offers by email.", "v1", "https://libreguard.net/privacy",
                "3fea7638-e486-46cc-9c12-93863a70611c")
            repeat(3) { server.enqueue(MockResponse().setBody(Gson().toJson(settings))) }
            assertEquals(settings, api.getNewsletter("Bearer owned-token", session).body())
            var request = server.takeRequest()
            assertEquals("/api/account/newsletter", request.path)
            assertEquals("Bearer owned-token", request.getHeader("Authorization"))

            api.updateNewsletter("Bearer owned-token", session, UpdateNewsletterRequest(false, settings.revision))
            request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/api/account/newsletter", request.path)
            assertEquals("Bearer owned-token", request.getHeader("Authorization"))
            var fields = Gson().fromJson(request.body.readUtf8(), Map::class.java)
            assertEquals(setOf("subscribed", "expectedRevision"), fields.keys)
            assertEquals(false, fields["subscribed"])

            api.completeNewsletterOnboarding("Bearer owned-token", session,
                NewsletterOnboardingRequest("subscribe", settings.revision, settings.consentTextVersion))
            request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/account/newsletter/onboarding", request.path)
            fields = Gson().fromJson(request.body.readUtf8(), Map::class.java)
            assertEquals(setOf("decision", "expectedRevision", "consentTextVersion"), fields.keys)
            assertEquals("subscribe", fields["decision"])
            assertEquals("v1", fields["consentTextVersion"])
            assertFalse(isPublicAuthEndpoint("/api/account/newsletter"))
        } finally { server.shutdown() }
    }

    @Test fun secure_google_begin_uses_post_auth_flow_without_legacy_opt_in() {
        val json = Gson().toJson(ClientDeviceRequest("device", "1", "key", "key-id", "RSA-OAEP-256"))
        val fields = Gson().fromJson(json, Map::class.java)
        assertEquals("postAuth", fields["newsletterConsentFlow"])
        assertFalse(fields.containsKey("newsletterConsent"))
    }
}
