package net.libreguard.vpn.network

internal fun isPublicAuthEndpoint(path: String): Boolean {
    val normalizedPath = path.substringBefore('?')
    return normalizedPath.startsWith("/api/login") ||
        normalizedPath.startsWith("/api/register") ||
        normalizedPath.startsWith("/api/account/forgot-password") ||
        normalizedPath.startsWith("/api/account/reset-password") ||
        normalizedPath.contains("/pre-auth/")
}

