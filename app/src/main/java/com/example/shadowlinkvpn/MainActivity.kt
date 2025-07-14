package com.example.shadowlinkvpn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
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
                AppNavigation()
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "login") {
        composable("login") {
            LoginScreen(onLoginSuccess = {
                navController.navigate("main") {
                    // Prevents going back to login screen
                    popUpTo("login") { inclusive = true }
                }
            })
        }
        composable("main") {
            MainScreen()
        }
    }
}