package com.example.shadowlinkvpn.network

import com.google.gson.annotations.SerializedName

// Updated to match the C# LoginModel
data class AuthRequest(
    @SerializedName("Email") // Use SerializedName to ensure the JSON key is "Email"
    val email: String,
    @SerializedName("Password") // Match the C# model property name
    val password: String
)

data class AuthResponse(
    @SerializedName("token")
    val token: String?, // Token might not be present in an error response
    @SerializedName("message")
    val message: String? // Message might not be present in a success response
)