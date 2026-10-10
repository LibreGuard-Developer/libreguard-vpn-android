package net.libreguard.vpn.util

import kotlinx.coroutines.CancellationException

internal data class SavedSessionRestoration(val token: String?, val validation: TokenValidationResult)

/** Network failure keeps saved credentials; only confirmed rejection clears a session. */
internal suspend fun restoreSavedSession(
    readToken: () -> String?,
    validate: suspend () -> TokenValidationResult,
    onRevoked: () -> Unit,
    onFailure: (Exception) -> Unit = {}
): SavedSessionRestoration {
    if (readToken().isNullOrBlank()) return SavedSessionRestoration(null, TokenValidationResult.REVOKED)
    val validation = try {
        validate()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onFailure(e)
        TokenValidationResult.UNAVAILABLE
    }
    if (validation == TokenValidationResult.REVOKED) {
        onRevoked()
        return SavedSessionRestoration(null, validation)
    }
    // Read again after refresh. A concurrent logout must not resurrect the original token.
    return SavedSessionRestoration(readToken(), validation)
}
