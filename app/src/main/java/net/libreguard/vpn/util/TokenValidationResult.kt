package net.libreguard.vpn.util

import kotlinx.coroutines.CancellationException
import net.libreguard.vpn.network.TokenCheckResponse
import retrofit2.Response

/** A failed network request is not evidence that a session was revoked. */
enum class TokenValidationResult { VALID, REVOKED, UNAVAILABLE }

internal fun TokenRefreshResult.validationResult(): TokenValidationResult = when (this) {
    TokenRefreshResult.Skipped, is TokenRefreshResult.Success -> TokenValidationResult.VALID
    is TokenRefreshResult.Failure -> if (statusCode == 401 || statusCode == 403) {
        TokenValidationResult.REVOKED
    } else {
        TokenValidationResult.UNAVAILABLE
    }
    is TokenRefreshResult.RateLimited -> TokenValidationResult.UNAVAILABLE
    is TokenRefreshResult.ExceptionFailure -> {
        if (throwable is CancellationException) throw throwable
        TokenValidationResult.UNAVAILABLE
    }
}

internal suspend fun validateTokenRequest(
    request: suspend () -> Response<TokenCheckResponse>,
    onFailure: (Exception) -> Unit = {}
): TokenValidationResult = try {
    val response = request()
    when {
        response.code() == 401 || response.code() == 403 -> TokenValidationResult.REVOKED
        response.isSuccessful -> when (response.body()?.isValid) {
            true -> TokenValidationResult.VALID
            false -> TokenValidationResult.REVOKED
            null -> TokenValidationResult.UNAVAILABLE
        }
        else -> TokenValidationResult.UNAVAILABLE
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    onFailure(e)
    TokenValidationResult.UNAVAILABLE
}
