package net.libreguard.vpn.core

enum class AppUpdatePhase {
    CHECKING, CURRENT, AVAILABLE, STARTING, PENDING, DOWNLOADING,
    READY_TO_INSTALL, INSTALLING, FAILED, UNAVAILABLE
}

enum class AppUpdateProblem { CHECK_FAILED, START_FAILED, DOWNLOAD_FAILED, INSTALL_FAILED }

data class AppUpdateSnapshot(
    val phase: AppUpdatePhase = AppUpdatePhase.CHECKING,
    val versionCode: Int? = null,
    val flexibleAllowed: Boolean = false,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = 0,
    val problem: AppUpdateProblem? = null
) {
    val progress: Float?
        get() = if (totalBytes > 0) {
            (bytesDownloaded.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()
        } else null

    val isInProgress: Boolean
        get() = phase in setOf(
            AppUpdatePhase.STARTING, AppUpdatePhase.PENDING, AppUpdatePhase.DOWNLOADING,
            AppUpdatePhase.READY_TO_INSTALL, AppUpdatePhase.INSTALLING
        )
}

data class AppUpdateUiState(
    val snapshot: AppUpdateSnapshot = AppUpdateSnapshot(),
    val installedVersion: String = "",
    val isChecking: Boolean = false,
    val bannerSnoozed: Boolean = false,
    val foregroundSession: Int = 0
) {
    val showHomeBanner: Boolean
        get() = snapshot.isInProgress ||
            (snapshot.phase == AppUpdatePhase.AVAILABLE && !bannerSnoozed)
}

data class AppUpdateSnooze(val versionCode: Int, val untilMillis: Long)

interface AppUpdateSnoozeStore {
    fun read(): AppUpdateSnooze?
    fun write(snooze: AppUpdateSnooze)
}