package net.libreguard.vpn.ui.screens

import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import net.libreguard.vpn.ui.components.LogoWithGradient
import net.libreguard.vpn.ui.theme.*

private const val TAG = "CardPaymentScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardPaymentScreen(
    checkoutUrl: String,
    isLoading: Boolean,
    onClose: () -> Unit,
    onCheckPayment: ((String) -> Unit)? = null,
    onSuccess: (() -> Unit)? = null
) {
    var showWebView by remember { mutableStateOf(false) }
    var webViewError by remember { mutableStateOf<String?>(null) }
    var isWebViewLoading by remember { mutableStateOf(false) }
    var loadingProgress by remember { mutableStateOf(0f) }

    // Animate progress
    val animatedProgress by animateFloatAsState(
        targetValue = loadingProgress,
        animationSpec = tween(300),
        label = "progress"
    )

    LaunchedEffect(checkoutUrl) {
        Log.d(TAG, "LaunchedEffect triggered - checkoutUrl changed")
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = MutedForeground
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    LogoWithGradient(size = 40.dp)
                    Text(
                        text = "Card Payment",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Foreground
                    )
                }

                Text(
                    text = "Complete your Pro subscription payment",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedForeground,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // Content
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isLoading && !showWebView -> {
                        // Loading state
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                color = Primary,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = "Loading secure checkout...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MutedForeground
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            LinearProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier
                                    .width(200.dp)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = Primary,
                                trackColor = Secondary,
                            )
                        }
                    }

                    showWebView && checkoutUrl.isNotEmpty() -> {
                        // WebView container
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(12.dp),
                            color = CardBackground,
                            border = ButtonDefaults.outlinedButtonBorder(enabled = true)
                        ) {
                            Column {
                                // Mock browser bar
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Secondary)
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Traffic lights
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .background(Destructive, RoundedCornerShape(6.dp))
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .background(StatusConnecting, RoundedCornerShape(6.dp))
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .background(StatusConnected, RoundedCornerShape(6.dp))
                                    )

                                    // URL bar
                                    Surface(
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(4.dp),
                                        color = Background
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .background(StatusConnected, RoundedCornerShape(4.dp))
                                            )
                                            Text(
                                                text = "secure-checkout.libreguard.com",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MutedForeground
                                            )
                                        }
                                    }
                                }

                                // WebView loading indicator
                                if (isWebViewLoading) {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = Primary,
                                        trackColor = Secondary
                                    )
                                }

                                // Actual WebView
                                AndroidView(
                                    modifier = Modifier.fillMaxSize(),
                                    factory = { context ->
                                        WebView(context).apply {
                                            settings.apply {
                                                javaScriptEnabled = true
                                                domStorageEnabled = true
                                                databaseEnabled = true
                                                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                                userAgentString = "LibreGuardVPN/1.0 Android"
                                            }

                                            webViewClient = object : WebViewClient() {
                                                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                                    super.onPageStarted(view, url, favicon)
                                                    Log.d(TAG, "WebView page started: $url")
                                                    isWebViewLoading = true

                                                    // Check for Lemon Squeezy order_id in URL
                                                    if (url != null && onCheckPayment != null) {
                                                        try {
                                                            val uri = android.net.Uri.parse(url)
                                                            val orderId = uri.getQueryParameter("order_id")
                                                            if (!orderId.isNullOrBlank()) {
                                                                Log.d(TAG, "Detected order_id in URL: $orderId")
                                                                onCheckPayment(orderId)
                                                            }
                                                            // Also check for success/thank-you path which might indicate payment done
                                                            // Some implementations might rely on different params
                                                            if (url.contains("/checkout/success") || url.contains("/thank-you")) {
                                                                Log.d(TAG, "Detected success URL pattern")
                                                                onSuccess?.invoke()
                                                            }
                                                        } catch (e: Exception) {
                                                            Log.e(TAG, "Error parsing URL: $url", e)
                                                        }
                                                    }
                                                }

                                                override fun onPageFinished(view: WebView?, url: String?) {
                                                    super.onPageFinished(view, url)
                                                    Log.d(TAG, "WebView page finished: $url")
                                                    isWebViewLoading = false
                                                }

                                                override fun onReceivedError(
                                                    view: WebView?,
                                                    request: android.webkit.WebResourceRequest?,
                                                    error: android.webkit.WebResourceError?
                                                ) {
                                                    super.onReceivedError(view, request, error)
                                                    val errorMsg = "Error: ${error?.description}"
                                                    Log.e(TAG, errorMsg)
                                                    webViewError = errorMsg
                                                    isWebViewLoading = false
                                                }
                                            }

                                            loadUrl(checkoutUrl)
                                        }
                                    }
                                )
                            }
                        }
                    }

                    else -> {
                        // Initial state / error state
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = CardBackground,
                            border = ButtonDefaults.outlinedButtonBorder(enabled = true)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (webViewError != null) {
                                    Text(
                                        text = "Error loading checkout",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Destructive
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = webViewError!!,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MutedForeground
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(
                                        onClick = {
                                            webViewError = null
                                            showWebView = false
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                                    ) {
                                        Text("Try Again")
                                    }
                                } else if (checkoutUrl.isEmpty()) {
                                    CircularProgressIndicator(
                                        color = Primary,
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "Loading checkout URL...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MutedForeground
                                    )
                                } else {
                                    Text(
                                        text = "Secure Payment",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Foreground
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "You'll be redirected to our secure payment processor",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MutedForeground
                                    )
                                    Spacer(modifier = Modifier.height(24.dp))
                                    Button(
                                        onClick = {
                                            if (checkoutUrl.isNotEmpty() && (checkoutUrl.startsWith("https://") || checkoutUrl.startsWith("http://"))) {
                                                showWebView = true
                                            } else {
                                                webViewError = "Invalid checkout URL"
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                                    ) {
                                        Text("Proceed to Payment")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Info notice at bottom
            if (!showWebView) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = CardBackground,
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true)
                ) {
                    Text(
                        text = "Your payment information is encrypted and never stored on LibreGuard servers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedForeground,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

