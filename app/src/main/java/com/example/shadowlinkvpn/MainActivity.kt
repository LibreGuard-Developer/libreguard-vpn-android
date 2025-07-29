package com.example.shadowlinkvpn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.shadowlinkvpn.ui.screens.LoginScreen
import com.example.shadowlinkvpn.ui.screens.MainScreen
import com.example.shadowlinkvpn.ui.theme.ShadowLinkVPNTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShadowLinkVPNTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppNavigation(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun AppNavigation(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    var authToken by remember { mutableStateOf<String?>(null) }

    NavHost(
        navController = navController,
        startDestination = "login",
        modifier = modifier
    ) {
        composable("login") {
            LoginScreen(onLoginSuccess = { token ->
                authToken = token
                navController.navigate("main") {
                    popUpTo("login") { inclusive = true }
                }
            })
        }
        composable("main") {
            authToken?.let { token ->
                MainScreen(authToken = token)
            } ?: run {
                // If token is null, navigate back to login
                LaunchedEffect(Unit) {
                    navController.navigate("login") {
                        popUpTo("main") { inclusive = true }
                    }
                }
            }
        }
    }
}