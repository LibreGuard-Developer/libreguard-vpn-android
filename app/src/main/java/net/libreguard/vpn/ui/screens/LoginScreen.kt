package net.libreguard.vpn.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.launch
import net.libreguard.vpn.R
import net.libreguard.vpn.network.RetrofitClient
import net.libreguard.vpn.network.AuthRequest
import kotlin.math.cos

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoginSuccess: (String) -> Unit,
    onRequires2FA: (String) -> Unit,
    onNavigateToRegister: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var isEmailFocused by remember { mutableStateOf(false) }
    var isPasswordFocused by remember { mutableStateOf(false) }
    val isAnyFieldFocused = isEmailFocused || isPasswordFocused

    val coroutineScope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val screenWidth = configuration.screenWidthDp.dp
    val scrollState = rememberScrollState()
    val keyboardController = LocalSoftwareKeyboardController.current

    // Animation states
    val infiniteTransition = rememberInfiniteTransition(label = "background_animation")

    val redStripeOffset by infiniteTransition.animateFloat(
        initialValue = -screenWidth.value,
        targetValue = screenWidth.value * 2,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "red_stripe"
    )

    val shadowFigureOffset by infiniteTransition.animateFloat(
        initialValue = -200f,
        targetValue = screenWidth.value + 200f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shadow_figure"
    )

    val shadowFigureBob by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shadow_bob"
    )

    // Auto-scroll when fields are focused - scroll input fields to upper part
    LaunchedEffect(isAnyFieldFocused) {
        if (isAnyFieldFocused) {
            scrollState.animateScrollTo(800) // Scroll input fields to upper part
        } else {
            scrollState.animateScrollTo(0)
        }
    }

    // Very dark gradient background
    val gradientColors = listOf(
        Color(0xFF000000), // Pure black
        Color(0xFF0A0A0A), // Almost black
        Color(0xFF1A1A1A), // Very dark gray
        Color(0xFF000000)  // Pure black
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = gradientColors,
                    startY = 0f,
                    endY = Float.POSITIVE_INFINITY
                )
            )
    ) {
        // Animated background with red stripe and shadow figure
        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            drawAnimatedBackground(
                drawScope = this,
                width = screenWidth.toPx(),
                height = screenHeight.toPx(),
                redStripeOffset = redStripeOffset,
                shadowFigureOffset = shadowFigureOffset,
                shadowBob = shadowFigureBob
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    // Only make scrollable when fields are focused
                    if (isAnyFieldFocused) {
                        Modifier.verticalScroll(scrollState)
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Responsive spacing
            val topSpacing = if (screenHeight < 700.dp) 40.dp else 60.dp
            val logoSize = if (screenHeight < 700.dp) 60.dp else 80.dp
            val titleSize = if (screenHeight < 700.dp) 24.sp else 28.sp

            Spacer(modifier = Modifier.height(topSpacing))

            // Enhanced logo with green accent
            Card(
                modifier = Modifier.size(logoSize),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1A1A1A).copy(alpha = 0.8f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(
                        modifier = Modifier.size(40.dp)
                    ) {
                        // Simple shield icon with green accent
                        val center = Offset(size.width / 2, size.height / 2)
                        val radius = size.minDimension / 3

                        // Draw shield outline in green
                        drawCircle(
                            color = Color(0xFF00FF88), // Bright green
                            radius = radius,
                            center = center,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                        )

                        // Inner dot in bright green
                        drawCircle(
                            color = Color(0xFF00DD77),
                            radius = radius * 0.3f,
                            center = center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Enhanced title with gradients - "LibreGuard VPN" with green gradient
            Text(
                text = "LibreGuard VPN",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = titleSize,
                    fontWeight = FontWeight.Bold,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF00FF88), // Bright green
                            Color(0xFF00DD77), // Green
                            Color(0xFF00BB66), // Dark green
                            Color(0xFF00FF88)  // Bright green
                        )
                    )
                ),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(40.dp))

            // Login form card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1A1A1A).copy(alpha = 0.9f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Welcome Back",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        text = "Sign in to continue",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF888888),
                        modifier = Modifier.padding(bottom = 24.dp)
                    )

                    // Email field with gradient border when focused
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email", color = Color(0xFFBBBBBB)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Email,
                                contentDescription = "Email",
                                tint = if (isEmailFocused) Color(0xFF00FF88) else Color(0xFF666666)
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focusState ->
                                isEmailFocused = focusState.isFocused
                            }
                            .then(
                                if (isEmailFocused) {
                                    Modifier.border(
                                        width = 2.dp,
                                        brush = Brush.linearGradient(
                                            colors = listOf(
                                                Color(0xFF00FF88),
                                                Color(0xFF00CCFF),
                                                Color(0xFF9966FF),
                                                Color(0xFF00FF88)
                                            )
                                        ),
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                } else Modifier
                            ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color(0xFFCCCCCC),
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color(0xFF333333),
                            cursorColor = Color(0xFF00FF88)
                        ),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Password field with gradient border when focused
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password", color = Color(0xFFBBBBBB)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Password",
                                tint = if (isPasswordFocused) Color(0xFF00FF88) else Color(0xFF666666)
                            )
                        },
                        trailingIcon = {
                            TextButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                Text(if (isPasswordVisible) "Hide" else "Show", color = Color(0xFFBBBBBB))
                            }
                        },
                        visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focusState ->
                                isPasswordFocused = focusState.isFocused
                            }
                            .then(
                                if (isPasswordFocused) {
                                    Modifier.border(
                                        width = 2.dp,
                                        brush = Brush.linearGradient(
                                            colors = listOf(
                                                Color(0xFF00FF88),
                                                Color(0xFF00CCFF),
                                                Color(0xFF9966FF),
                                                Color(0xFF00FF88)
                                            )
                                        ),
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                } else Modifier
                            ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color(0xFFCCCCCC),
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color(0xFF333333),
                            cursorColor = Color(0xFF00FF88)
                        ),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Enhanced login button with psychologically appealing orange-red gradient
                    Button(
                        onClick = {
                            keyboardController?.hide()
                            coroutineScope.launch {
                                isLoading = true
                                errorMessage = null
                                try {
                                    val response = RetrofitClient.instance.login(
                                        AuthRequest(email = email, password = password)
                                    )
                                    if (response.isSuccessful) {
                                        val authResponse = response.body()

                                        // Check if 2FA is required
                                        if (authResponse?.requiresTwoFactor == true) {
                                            // Navigate to 2FA verification screen
                                            onRequires2FA(email)
                                        } else {
                                            // Normal login flow
                                            val token = authResponse?.token
                                            if (!token.isNullOrBlank()) {
                                                onLoginSuccess(token)
                                            } else {
                                                errorMessage = authResponse?.message ?: "Login failed"
                                            }
                                        }
                                    } else {
                                        errorMessage = "Login failed: ${response.code()}"
                                    }
                                } catch (e: Exception) {
                                    errorMessage = "Network error: ${e.localizedMessage}"
                                } finally {
                                    isLoading = false
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent
                        ),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(
                                            Color(0xFFFF6B35), // Vibrant orange
                                            Color(0xFFFF4757), // Coral red
                                            Color(0xFFFF3838), // Bright red
                                            Color(0xFFFF6B35)  // Back to orange
                                        )
                                    ),
                                    shape = RoundedCornerShape(28.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            } else {
                                Text(
                                    text = "Connect Securely",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Error message
                    errorMessage?.let { message ->
                        Spacer(modifier = Modifier.height(16.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0x33FF4444)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = message,
                                modifier = Modifier.padding(12.dp),
                                color = Color(0xFFFFAAAA),
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    // Register prompt
                    Spacer(modifier = Modifier.height(16.dp))
                    TextButton(onClick = { onNavigateToRegister() }) {
                        Text(
                            text = stringResource(id = R.string.new_here_create_account),
                            color = Color(0xFF00FF88)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // Footer text
            Text(
                text = "Secure • Private • Anonymous",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF555555),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

// Enhanced animated background function
private fun drawAnimatedBackground(
    drawScope: DrawScope,
    width: Float,
    height: Float,
    redStripeOffset: Float,
    shadowFigureOffset: Float,
    shadowBob: Float
) {
    with(drawScope) {
        // Moving red diagonal stripe
        val stripePath = Path().apply {
            moveTo(redStripeOffset - 100f, 0f)
            lineTo(redStripeOffset + 200f, 0f)
            lineTo(redStripeOffset + 100f, height)
            lineTo(redStripeOffset - 200f, height)
            close()
        }

        drawPath(
            path = stripePath,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    Color(0x22FF0000),
                    Color(0x44FF4444),
                    Color(0x22FF0000),
                    Color.Transparent
                ),
                start = Offset(redStripeOffset - 150f, height * 0.3f),
                end = Offset(redStripeOffset + 150f, height * 0.7f)
            )
        )

        // Floating shadow figure
        rotate(degrees = 15f, pivot = Offset(shadowFigureOffset, height * 0.2f + shadowBob)) {
            val shadowPath = Path().apply {
                moveTo(shadowFigureOffset, height * 0.15f + shadowBob)
                lineTo(shadowFigureOffset + 60f, height * 0.1f + shadowBob)
                lineTo(shadowFigureOffset + 80f, height * 0.25f + shadowBob)
                lineTo(shadowFigureOffset + 40f, height * 0.3f + shadowBob)
                lineTo(shadowFigureOffset - 20f, height * 0.25f + shadowBob)
                close()
            }

            drawPath(
                path = shadowPath,
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0x44000000),
                        Color(0x22000000),
                        Color.Transparent
                    ),
                    center = Offset(shadowFigureOffset + 30f, height * 0.2f + shadowBob),
                    radius = 80f
                )
            )
        }

        // Subtle grid pattern
        for (i in 0..10) {
            val x = (width / 10) * i
            drawLine(
                color = Color(0x08FFFFFF),
                start = Offset(x, 0f),
                end = Offset(x, height),
                strokeWidth = 1.dp.toPx()
            )
        }

        for (i in 0..15) {
            val y = (height / 15) * i
            drawLine(
                color = Color(0x08FFFFFF),
                start = Offset(0f, y),
                end = Offset(width, y),
                strokeWidth = 1.dp.toPx()
            )
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun PreviewLoginScreen() {
    LoginScreen(
        onLoginSuccess = { },
        onRequires2FA = { },
        onNavigateToRegister = { }
    )
}
