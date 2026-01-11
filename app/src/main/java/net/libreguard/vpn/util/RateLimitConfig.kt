package net.libreguard.vpn.util

/**
 * Configuration for rate limiting and retry logic for API operations.
 * Defines retry behavior, backoff strategy, and operation classification.
 */
object RateLimitConfig {

    // Retry configuration
    const val MAX_RETRIES = 3
    const val INITIAL_BACKOFF_MS = 1000L
    const val MAX_BACKOFF_MS = 30000L
    const val BACKOFF_MULTIPLIER = 2.0

    // Default retry-after value when header is missing (in seconds)
    const val DEFAULT_RETRY_AFTER_SECONDS = 60

    /**
     * Operations that are safe to auto-retry on rate limit (idempotent reads).
     * These will automatically retry with exponential backoff.
     */
    val AUTO_RETRY_OPERATIONS = setOf(
        "GET /api/devices", // Safe - idempotent read
    )

    /**
     * Operations that need user confirmation before retry (destructive writes).
     * These will show rate limit dialog and require manual retry.
     */
    val MANUAL_RETRY_OPERATIONS = setOf(
        "POST /api/devices/remove/{id}", // User explicitly requested
        "DELETE /api/devices/{id}", // Destructive operation
        "POST /api/devices/remove-all-others", // Bulk destructive operation
        "POST /api/devices/remove-all-inactive" // Bulk cleanup operation
    )

    /**
     * Calculate backoff delay for retry attempt.
     *
     * @param attempt Current retry attempt (0-based)
     * @return Delay in milliseconds
     */
    fun calculateBackoffDelay(attempt: Int): Long {
        val delay = (INITIAL_BACKOFF_MS * Math.pow(BACKOFF_MULTIPLIER, attempt.toDouble())).toLong()
        return minOf(delay, MAX_BACKOFF_MS)
    }
}

