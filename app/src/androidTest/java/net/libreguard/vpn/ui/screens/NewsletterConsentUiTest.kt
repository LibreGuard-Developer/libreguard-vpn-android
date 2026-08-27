package net.libreguard.vpn.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import net.libreguard.vpn.ui.components.GOOGLE_NEWSLETTER_CONSENT_TEST_TAG
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
    fun googleNewsletterConsentSheetIsUncheckedAndRequiresExplicitOptIn() {
        var consent by mutableStateOf(false)

        composeRule.setContent {
            LibreGuardVPNTheme {
                GoogleNewsletterConsentSheet(
                    newsletterConsent = consent,
                    onNewsletterConsentChange = { consent = it },
                    onDismiss = {},
                    onContinue = {}
                )
            }
        }

        composeRule.onNodeWithText(NEWSLETTER_CONSENT_TEXT).assertExists()
        composeRule.onNodeWithText(
            "This choice applies only if Google creates a new LibreGuard account. Existing accounts will not be subscribed or have their preferences changed."
        ).assertExists()

        // A checked-by-default control would toggle off here and fail assertIsOn.
        composeRule.onNodeWithTag(GOOGLE_NEWSLETTER_CONSENT_TEST_TAG).performClick().assertIsOn()
    }
}
