package net.libreguard.vpn.network

import android.content.Context
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import net.libreguard.vpn.util.TokenManager

object RetrofitClient {
    private const val BASE_URL = "https://management.libreguard.net/"
    private var tokenManager: TokenManager? = null
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        tokenManager = TokenManager(context.applicationContext)
    }

    private fun newSecureOkHttpClientBuilder(): OkHttpClient.Builder {
        // Use the platform defaults so Android validates the server certificate
        // chain and hostname against the system trust store.
        return OkHttpClient.Builder()
    }

    // Auth API Service (No Authenticator) - used for refreshing tokens
    val authApiService: ApiService by lazy {
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(newSecureOkHttpClientBuilder().build()) // No interceptors/authenticators
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        retrofit.create(ApiService::class.java)
    }

    val instance: ApiService by lazy {
        if (tokenManager == null || appContext == null) {
            throw IllegalStateException("RetrofitClient must be initialized with context before use.")
        }

        val clientBuilder = newSecureOkHttpClientBuilder()

        // REMOVED: BASIC console logging of all API calls
        // clientBuilder.addInterceptor(loggingInterceptor)

        // Add Authenticator and Interceptor
        clientBuilder.authenticator(TokenAuthenticator(appContext!!, tokenManager!!, authApiService))
        clientBuilder.addInterceptor(AuthInterceptor(tokenManager!!, appContext!!))

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(clientBuilder.build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        retrofit.create(ApiService::class.java)
    }

    fun getTokenManager(): TokenManager {
        return tokenManager ?: throw IllegalStateException("RetrofitClient not initialized")
    }
}
