package net.libreguard.vpn.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import net.libreguard.vpn.ui.theme.Foreground
import net.libreguard.vpn.ui.theme.MutedForeground
import net.libreguard.vpn.ui.theme.Primary
import net.libreguard.vpn.ui.theme.PrimaryForeground

const val NEWSLETTER_CONSENT_TEXT =
    "Send me LibreGuard news, product updates and occasional offers by email."

const val REGISTRATION_NEWSLETTER_CONSENT_TEST_TAG = "registrationNewsletterConsentCheckbox"
const val GOOGLE_NEWSLETTER_CONSENT_TEST_TAG = "googleNewsletterConsentCheckbox"

/**
 * Optional marketing consent kept separate from legal/account-creation acceptance.
 */
@Composable
fun NewsletterConsentCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
            colors = CheckboxDefaults.colors(
                checkedColor = Primary,
                uncheckedColor = MutedForeground,
                checkmarkColor = PrimaryForeground
            )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = NEWSLETTER_CONSENT_TEXT,
            style = MaterialTheme.typography.bodySmall,
            color = Foreground
        )
    }
}
