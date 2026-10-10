package net.libreguard.vpn.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

internal data class RecoveryAction(val label: String, val tag: String, val onClick: () -> Unit)

/** Equal-height action slots keep wrapped labels aligned and touch targets usable. */
@Composable
internal fun RecoveryActionRow(actions: List<RecoveryAction>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        actions.forEachIndexed { index, action ->
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = when (index) {
                actions.lastIndex -> Alignment.CenterEnd
                0 -> Alignment.CenterStart
                else -> Alignment.Center
            }) {
                TextButton(onClick = action.onClick,
                    modifier = Modifier.fillMaxHeight().heightIn(min = 48.dp).testTag(action.tag),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                    Text(action.label, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
