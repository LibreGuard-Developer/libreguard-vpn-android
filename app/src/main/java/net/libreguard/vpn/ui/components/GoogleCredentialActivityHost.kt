package net.libreguard.vpn.ui.components

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import net.libreguard.vpn.util.GoogleCredentialSource

/** Own the shared provider above navigation, so retiring login screens cannot detach its Activity. */
@Composable
fun GoogleCredentialActivityHost(credentials: GoogleCredentialSource) {
    val activity = LocalActivity.current
    DisposableEffect(activity, credentials) {
        activity?.let(credentials::attach)
        onDispose { activity?.let(credentials::detach) }
    }
}
