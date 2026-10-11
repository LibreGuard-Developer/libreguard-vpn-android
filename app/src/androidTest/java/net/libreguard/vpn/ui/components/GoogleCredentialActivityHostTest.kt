package net.libreguard.vpn.ui.components

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import net.libreguard.vpn.network.*
import net.libreguard.vpn.ui.screens.LoginScreen
import net.libreguard.vpn.ui.theme.LibreGuardVPNTheme
import net.libreguard.vpn.util.GoogleCredentialProvider
import net.libreguard.vpn.util.GoogleCredentialSource
import net.libreguard.vpn.viewmodel.ClientLoginViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import retrofit2.Response
import java.lang.reflect.Proxy

class GoogleCredentialActivityHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val store = ViewModelStore()
    private val saved = mutableListOf<ClientLoginResponse>()

    @After fun clearViewModels() {
        compose.runOnIdle { store.clear() }
    }

    @Test fun retiring_login_screen_cannot_detach_a_new_login_screen_using_the_same_view_model() {
        val credentials = AttachedCredentials()
        val vm = loginViewModel(credentials)
        var retiring by mutableStateOf(true)
        var returning by mutableStateOf(false)
        compose.setContent {
            LibreGuardVPNTheme {
                GoogleCredentialActivityHost(credentials)
                Box {
                    if (retiring) key("retiring") { TestLogin(vm) }
                    if (returning) key("returning") { TestLogin(vm) }
                }
            }
        }
        compose.runOnIdle { returning = true }
        compose.waitForIdle()
        compose.runOnIdle { retiring = false }
        compose.waitForIdle()
        // Reproduce an old screen leaving after the replacement has entered the composition.
        compose.runOnIdle { vm.google() }
        compose.waitUntil { saved.size == 1 }
        compose.runOnIdle {
            assertEquals(1, credentials.tokenRequests)
            assertSame(compose.activity, credentials.current)
            assertEquals(1, credentials.attachments)
            assertEquals(0, credentials.detachments)
        }
    }

    @Test fun google_login_logout_and_return_to_login_preserve_the_activity_across_navigation() {
        val credentials = AttachedCredentials()
        val vm = loginViewModel(credentials)
        compose.setContent {
            LibreGuardVPNTheme {
                GoogleCredentialActivityHost(credentials)
                val nav = rememberNavController()
                var signedIn by remember { mutableStateOf(false) }
                NavHost(nav, startDestination = "login") {
                    composable("login") {
                        TestLogin(vm) {
                            signedIn = true
                            nav.navigate("main") { popUpTo("login") { inclusive = true }; launchSingleTop = true }
                        }
                    }
                    composable("main") {
                        if (signedIn) {
                            Button(onClick = {
                                vm.cancel()
                                signedIn = false
                                nav.navigate("login") { popUpTo(0) { inclusive = true }; launchSingleTop = true }
                            }) { Text("Log out") }
                        } else {
                            // AppNavigation also redirects unauthenticated destinations to login.
                            LaunchedEffect(Unit) {
                                nav.navigate("login") { popUpTo(0) { inclusive = true }; launchSingleTop = true }
                            }
                        }
                    }
                }
            }
        }
        repeat(3) { iteration ->
            compose.onNodeWithText("Sign in with Google").performClick()
            compose.waitUntil { saved.size == iteration + 1 }
            compose.waitForIdle()
            compose.onNodeWithText("Log out").performClick()
            compose.waitForIdle()
        }
        compose.runOnIdle {
            assertEquals(3, credentials.tokenRequests)
            assertSame(compose.activity, credentials.current)
            assertEquals(1, credentials.attachments)
            assertEquals(0, credentials.detachments)
        }
    }

    @Test fun host_rebinds_when_activity_changes_and_releases_the_activity_on_disposal() {
        val credentials = AttachedCredentials()
        lateinit var replacement: Activity
        compose.runOnIdle { replacement = Activity() }
        var hostActivity by mutableStateOf<Activity?>(compose.activity)
        var mounted by mutableStateOf(true)
        compose.setContent {
            if (mounted) CompositionLocalProvider(LocalActivity provides hostActivity) {
                GoogleCredentialActivityHost(credentials)
            }
        }
        compose.runOnIdle { assertSame(compose.activity, credentials.current); hostActivity = replacement }
        compose.waitForIdle()
        compose.runOnIdle { assertSame(replacement, credentials.current); mounted = false }
        compose.waitForIdle()
        compose.runOnIdle {
            assertNull(credentials.current)
            assertEquals(2, credentials.attachments)
            assertEquals(2, credentials.detachments)
        }
    }

    @Test fun provider_rejects_a_missing_host_and_stale_old_activity_disposal_does_not_clear_a_new_host() {
        val provider = GoogleCredentialProvider(compose.activity.applicationContext)
        compose.runOnIdle {
            assertThrows(IllegalStateException::class.java) { provider.ensureActivityAttached() }
            provider.attach(compose.activity)
            provider.ensureActivityAttached()
            val replacement = Activity()
            provider.attach(replacement)
            provider.detach(compose.activity)
            provider.ensureActivityAttached()
            provider.detach(replacement)
            assertThrows(IllegalStateException::class.java) { provider.ensureActivityAttached() }
        }
    }

    @Composable private fun TestLogin(vm: ClientLoginViewModel, onSuccess: (String) -> Unit = {}) {
        LoginScreen(onLoginSuccess = onSuccess, onNavigateToRegister = {},
            onNavigateToEmailVerification = { _, _ -> }, loginViewModel = vm)
    }

    private fun loginViewModel(credentials: GoogleCredentialSource): ClientLoginViewModel {
        val device = ClientDeviceRequest("test-device", "test", "key", "key-id", "RSA-OAEP-256")
        val api = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, method, _ ->
            when (method.name) {
                "beginClientGoogle" -> Response.success(ClientGoogleBeginResponse("test-attempt", "r".repeat(43),
                    "n".repeat(43), "1234567890-web.apps.googleusercontent.com", "2099-01-01T00:00:00Z"))
                "completeClientGoogle" -> Response.success(ClientLoginResponse(outcome = "signedIn", token = "test-token",
                    refreshToken = "test-refresh", userId = "test-user", deviceId = device.deviceId))
                else -> error("Unexpected backend call: ${method.name}")
            }
        } as ApiService
        return ClientLoginViewModel(api, device, credentials, saved::add).also {
            store.put("login", it)
        }
    }

    private class AttachedCredentials : GoogleCredentialSource {
        var current: Activity? = null
        var attachments = 0
        var detachments = 0
        var tokenRequests = 0
        override fun attach(activity: Activity) { current = activity; attachments++ }
        override fun detach(activity: Activity) {
            if (current === activity) current = null
            detachments++
        }
        override fun ensureActivityAttached() { checkNotNull(current) { "Google sign-in requires a live Activity." } }
        override suspend fun getToken(begin: ClientGoogleBeginResponse): String {
            ensureActivityAttached()
            tokenRequests++
            return "test-google-proof"
        }
    }
}
