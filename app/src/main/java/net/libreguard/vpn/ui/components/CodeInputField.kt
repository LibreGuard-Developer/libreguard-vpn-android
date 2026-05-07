package net.libreguard.vpn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.libreguard.vpn.ui.theme.*

@Composable
fun CodeInputField(
    code: String,
    onCodeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    length: Int = 6
) {
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var textFieldValue by remember { mutableStateOf(TextFieldValue(code, TextRange(code.length))) }

    // External code changes sync
    LaunchedEffect(code) {
        if (textFieldValue.text != code) {
            textFieldValue = TextFieldValue(code, TextRange(code.length))
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                // Use a coroutine to delay focus request slightly so parents can place
                scope.launch {
                    delay(50)
                    try { focusRequester.requestFocus() } catch (_: Exception) {}
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Invisible but placed input to receive the keyboard and focus
        BasicTextField(
            value = textFieldValue,
            onValueChange = { newValue ->
                val filteredText = newValue.text.filter { it.isDigit() }.take(length)
                textFieldValue = TextFieldValue(
                    text = filteredText,
                    selection = TextRange(filteredText.length)
                )
                onCodeChange(filteredText)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .alpha(0.003f)
                .focusRequester(focusRequester),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true
        )

        // Visual representation of the code boxes
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(length) { index ->
                CodeDigitBox(
                    digit = code.getOrNull(index)?.toString() ?: "",
                    isFocused = code.length == index,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun CodeDigitBox(
    digit: String,
    isFocused: Boolean,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isFocused) Primary.copy(alpha = 0.12f) else CardBackground
    val borderColor = when {
        isFocused -> Primary
        digit.isNotEmpty() -> StatusConnected
        else -> Border
    }
    val textColor = if (digit.isNotEmpty()) Foreground else MutedForeground

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(12.dp)
            )
            .border(
                width = 2.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = digit,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = textColor,
            textAlign = TextAlign.Center
        )
    }
}
