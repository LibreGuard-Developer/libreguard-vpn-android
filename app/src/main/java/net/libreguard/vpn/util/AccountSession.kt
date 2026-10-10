package net.libreguard.vpn.util

/** Local request ownership only. The server still validates every credential. */
data class AccountSessionContext(val accountId: String, val epoch: Long)
data class AccountSessionSnapshot(val context: AccountSessionContext, val accessToken: String)

interface AccountSessionSource {
    fun sessionSnapshot(): AccountSessionSnapshot?
    fun isSessionCurrent(context: AccountSessionContext): Boolean
}

/** Shared across TokenManager instances. Refresh cannot resurrect a replaced session. */
internal class AccountSessionGate {
    private var epoch = 0L
    private var current: AccountSessionContext? = null

    @Synchronized fun begin(accountId: String): AccountSessionContext {
        require(accountId.isNotBlank())
        return AccountSessionContext(accountId, ++epoch).also { current = it }
    }

    @Synchronized fun restore(accountId: String): AccountSessionContext =
        current?.takeIf { it.accountId == accountId } ?: begin(accountId)

    @Synchronized fun clear() { epoch++; current = null }
    @Synchronized fun isCurrent(context: AccountSessionContext): Boolean = current == context
    @Synchronized fun <T> withCurrent(context: AccountSessionContext, action: () -> T): T? =
        if (current == context) action() else null

    @Synchronized fun <T> locked(action: () -> T): T = action()
}
