package net.libreguard.vpn.network

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import android.content.Context
import net.libreguard.vpn.util.TokenManager

object RetrofitClient {
    private const val BASE_URL = "https://management.libreguard.net/"
    private var tokenManager: TokenManager? = null
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        tokenManager = TokenManager(context.applicationContext)
    }

    private fun getUnsafeOkHttpClient(): OkHttpClient.Builder {
        try {
            // Create a trust manager that does not validate certificate chains
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            // Install the all-trusting trust manager
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())

            // Create an ssl socket factory with our all-trusting manager
            val sslSocketFactory = sslContext.socketFactory

            return OkHttpClient.Builder()
                .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    // Auth API Service (No Authenticator) - used for refreshing tokens
    val authApiService: ApiService by lazy {
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(getUnsafeOkHttpClient().build()) // No interceptors/authenticators
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        retrofit.create(ApiService::class.java)
    }

    val instance: ApiService by lazy {
        if (tokenManager == null || appContext == null) {
            throw IllegalStateException("RetrofitClient must be initialized with context before use.")
        }

        val clientBuilder = getUnsafeOkHttpClient()

        // Add HTTP logging interceptor for debugging
        val loggingInterceptor = okhttp3.logging.HttpLoggingInterceptor().apply {
            level = okhttp3.logging.HttpLoggingInterceptor.Level.BASIC
            redactHeader("Authorization")
        }
        clientBuilder.addInterceptor(loggingInterceptor)

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
