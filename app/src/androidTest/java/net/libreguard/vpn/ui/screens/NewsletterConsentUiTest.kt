package net.libreguard.vpn.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import net.libreguard.vpn.ui.components.NEWSLETTER_CONSENT_TEXT
import net.libreguard.vpn.ui.components.REGISTRATION_NEWSLETTER_CONSENT_TEST_TAG
import net.libreguard.vpn.ui.theme.LibreGuardVPNTheme
import org.junit.Rule
import org.junit.Test

class NewsletterConsentUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun registrationNewsletterConsentIsUncheckedAndSeparateFromLegalNotice() {
        composeRule.setContent {
            LibreGuardVPNTheme {
                RegisterScreen(
                    onBack = {},
                    onRegistrationNeedsConfirmation = { _, _, _, _ -> }
                )
            }
        }

        // A checked-by-default control would toggle off here and fail assertIsOn.
        composeRule.onNodeWithTag(REGISTRATION_NEWSLETTER_CONSENT_TEST_TAG).performClick().assertIsOn()
        composeRule.onNodeWithText(NEWSLETTER_CONSENT_TEXT).assertExists()
        composeRule.onNodeWithText(
            "By creating an account, you agree to our Terms of Service and Privacy Policy"
        ).assertExists()
    }

    @Test
    fun postLoginNewsletterChoiceIsUncheckedAndSubscribeNeedsExplicitOptIn() {
        var state by mutableStateOf(net.libreguard.vpn.viewmodel.NewsletterUiState(
            settings = net.libreguard.vpn.network.NewsletterSettingsResponse(
                "account", false, true, true, NEWSLETTER_CONSENT_TEXT, "v1",
                "https://libreguard.net/privacy", "3fea7638-e486-46cc-9c12-93863a70611c"
            ), requiresRefresh = false, promptVisible = true
        ))
        var subscriptions = 0
        composeRule.setContent {
            LibreGuardVPNTheme {
                net.libreguard.vpn.ui.components.NewsletterConsentDialog(
                    state = state,
                    onConsentChange = { state = state.copy(consentChecked = it) },
                    onSubscribe = { subscriptions++ },
                    onSkip = {},
                    onDismiss = {},
                    onRefresh = {}
                )
            }
        }
        composeRule.onNodeWithTag("newsletterOnboardingConsent").assertIsOff()
        composeRule.onNodeWithTag("newsletterSubscribe").assertIsNotEnabled()
        composeRule.onNodeWithTag("newsletterSkip").assertIsEnabled()
        composeRule.onNodeWithTag("newsletterOnboardingConsent").performClick().assertIsOn()
        composeRule.onNodeWithTag("newsletterSubscribe").performClick()
        org.junit.Assert.assertEquals(1, subscriptions)
    }

    @Test
    fun settingsKeepsConfirmedValueWhileSavingAndDisablesDuplicateWrites() {
        var writes = 0
        composeRule.setContent {
            LibreGuardVPNTheme {
                net.libreguard.vpn.ui.components.NewsletterPreferencesCard(
                    state = net.libreguard.vpn.viewmodel.NewsletterUiState(
                        settings = net.libreguard.vpn.network.NewsletterSettingsResponse(
                            "account", true, true, false, NEWSLETTER_CONSENT_TEXT, "v1",
                            "https://libreguard.net/privacy", "3fea7638-e486-46cc-9c12-93863a70611c"
                        ), requiresRefresh = false, isSaving = true
                    ),
                    onToggle = { writes++ }, onRefresh = {}
                )
            }
        }
        composeRule.onNodeWithTag("accountNewsletterToggle").assertIsOn().assertIsNotEnabled()
        org.junit.Assert.assertEquals(0, writes)
    }
}
