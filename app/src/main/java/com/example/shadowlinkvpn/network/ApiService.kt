package com.example.shadowlinkvpn.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {
    @POST("api/login") // IMPORTANT: Replace with your actual login endpoint
    suspend fun login(@Body request: AuthRequest): Response<AuthResponse>
}