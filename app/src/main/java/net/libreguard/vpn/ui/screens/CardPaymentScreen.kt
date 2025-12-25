package net.libreguard.vpn.ui.screens

import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private const val TAG = "CardPaymentScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardPaymentScreen(
    checkoutUrl: String,
    isLoading: Boolean,
    onClose: () -> Unit,
    onSuccess: (() -> Unit)? = null
) {
    var showWebView by remember { mutableStateOf(false) }
    var webViewError by remember { mutableStateOf<String?>(null) }
    var isWebViewLoading by remember { mutableStateOf(false) }

    LaunchedEffect(checkoutUrl) {
        Log.d(TAG, "LaunchedEffect triggered - checkoutUrl changed to: ${if (checkoutUrl.isNotEmpty()) checkoutUrl.take(100) + "..." else "EMPTY"}")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Secure Checkout") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading && !showWebView) {
                // Initial loading state before showing WebView
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Loading secure checkout...")
                }
            } else if (showWebView && checkoutUrl.isNotEmpty()) {
                // WebView is active - show loading indicator while page loads
                if (isWebViewLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

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
                                    Log.d(TAG, "WebView page started loading: $url")
                                    isWebViewLoading = true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    Log.d(TAG, "WebView page finished loading: $url")
                                    isWebViewLoading = false
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: android.webkit.WebResourceRequest?,
                                    error: android.webkit.WebResourceError?
                                ) {
                                    super.onReceivedError(view, request, error)
                                    val errorMsg = "WebView error: ${error?.description}"
                                    Log.e(TAG, errorMsg)
                                    webViewError = errorMsg
                                    isWebViewLoading = false
                                }
                            }

                            Log.d(TAG, "Loading checkout URL in WebView: ${checkoutUrl.take(100)}")
                            loadUrl(checkoutUrl)
                        }
                    }
                )
            } else {
                // Button screen - before user clicks "Proceed to Payment"
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (webViewError != null) {
                            Text(
                                "Error loading checkout: $webViewError",
                                color = Color.Red,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            Button(
                                onClick = {
                                    Log.d(TAG, "User clicked Try Again")
                                    webViewError = null
                                    showWebView = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Try Again")
                            }
                        } else if (checkoutUrl.isEmpty()) {
                            Text("Loading checkout URL...", color = Color.Gray)
                            Spacer(modifier = Modifier.height(16.dp))
                            CircularProgressIndicator()
                        } else {
                            Text("Opening LemonSqueezy Checkout")
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    Log.d(TAG, "Proceed to Payment button clicked")
                                    if (checkoutUrl.isNotEmpty() && (checkoutUrl.startsWith("https://") || checkoutUrl.startsWith("http://"))) {
                                        Log.d(TAG, "Valid checkout URL detected, showing WebView")
                                        showWebView = true
                                    } else {
                                        Log.e(TAG, "Invalid checkout URL format: $checkoutUrl")
                                        webViewError = "Invalid checkout URL format"
                                    }
                                }
                            ) {
                                Text("Proceed to Payment")
                            }
                        }
                    }
                }
            }
        }
    }
}

