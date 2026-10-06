package net.libreguard.vpn.core

import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.tasks.Task
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallException
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallErrorCode
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun interface AppUpdateFlowStarter {
    fun launch(launcher: ActivityResultLauncher<IntentSenderRequest>): Boolean
}

data class PreparedAppUpdate(val snapshot: AppUpdateSnapshot, val starter: AppUpdateFlowStarter? = null)

interface AppUpdateClient {
    val installUpdates: Flow<AppUpdateSnapshot>
    suspend fun check(): AppUpdateSnapshot
    suspend fun prepareUpdate(): PreparedAppUpdate
    suspend fun completeUpdate()
    fun close()
}

/** Owns Play's application-context client; it never retains an Activity or launcher. */
class GooglePlayUpdateClient(private val manager: AppUpdateManager) : AppUpdateClient {
    constructor(context: Context) : this(AppUpdateManagerFactory.create(context.applicationContext))

    private var lastVersionCode: Int? = null
    private val updates = MutableStateFlow<AppUpdateSnapshot?>(null)
    override val installUpdates: Flow<AppUpdateSnapshot> = updates.filterNotNull()

    private val listener = InstallStateUpdatedListener { state ->
        val phase = when (state.installStatus()) {
            InstallStatus.PENDING -> AppUpdatePhase.PENDING
            InstallStatus.DOWNLOADING -> AppUpdatePhase.DOWNLOADING
            InstallStatus.DOWNLOADED -> AppUpdatePhase.READY_TO_INSTALL
            InstallStatus.INSTALLING -> AppUpdatePhase.INSTALLING
            InstallStatus.INSTALLED -> AppUpdatePhase.CURRENT
            InstallStatus.CANCELED -> AppUpdatePhase.AVAILABLE
            InstallStatus.FAILED -> AppUpdatePhase.FAILED
            else -> null
        }
        if (phase != null) {
            val failure = if (phase == AppUpdatePhase.FAILED) {
                if (updates.value?.phase == AppUpdatePhase.INSTALLING) {
                    AppUpdateProblem.INSTALL_FAILED
                } else AppUpdateProblem.DOWNLOAD_FAILED
            } else null
            updates.value = AppUpdateSnapshot(
                phase = phase,
                versionCode = lastVersionCode,
                flexibleAllowed = true,
                bytesDownloaded = state.bytesDownloaded(),
                totalBytes = state.totalBytesToDownload(),
                problem = failure
            )
        }
    }

    init { manager.registerListener(listener) }

    override suspend fun check(): AppUpdateSnapshot = getInfo()?.toSnapshot()
        ?: AppUpdateSnapshot(AppUpdatePhase.UNAVAILABLE)

    override suspend fun prepareUpdate(): PreparedAppUpdate {
        val info = getInfo() ?: return PreparedAppUpdate(AppUpdateSnapshot(AppUpdatePhase.UNAVAILABLE))
        val snapshot = info.toSnapshot()
        val canStart = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
            snapshot.phase in setOf(AppUpdatePhase.AVAILABLE, AppUpdatePhase.FAILED) &&
            snapshot.flexibleAllowed
        val starter = if (canStart) {
            // AppUpdateInfo is single-use. Each attempt fetches a new instance.
            AppUpdateFlowStarter { launcher ->
                manager.startUpdateFlowForResult(
                    info, launcher, AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build()
                )
            }
        } else null
        return PreparedAppUpdate(snapshot, starter)
    }

    override suspend fun completeUpdate() { manager.completeUpdate().awaitResult() }
    override fun close() { manager.unregisterListener(listener) }

    private suspend fun getInfo(): AppUpdateInfo? = try {
        manager.appUpdateInfo.awaitResult()
    } catch (error: InstallException) {
        if (error.errorCode in setOf(
                InstallErrorCode.ERROR_API_NOT_AVAILABLE,
                InstallErrorCode.ERROR_APP_NOT_OWNED,
                InstallErrorCode.ERROR_PLAY_STORE_NOT_FOUND,
                InstallErrorCode.ERROR_INSTALL_UNAVAILABLE
            )) null else throw error
    }

    private fun AppUpdateInfo.toSnapshot(): AppUpdateSnapshot {
        lastVersionCode = availableVersionCode().takeIf { it > 0 }
        val phase = when (installStatus()) {
            InstallStatus.PENDING -> AppUpdatePhase.PENDING
            InstallStatus.DOWNLOADING -> AppUpdatePhase.DOWNLOADING
            InstallStatus.DOWNLOADED -> AppUpdatePhase.READY_TO_INSTALL
            InstallStatus.INSTALLING -> AppUpdatePhase.INSTALLING
            InstallStatus.FAILED -> AppUpdatePhase.FAILED
            else -> when (updateAvailability()) {
                UpdateAvailability.UPDATE_AVAILABLE -> AppUpdatePhase.AVAILABLE
                UpdateAvailability.UPDATE_NOT_AVAILABLE -> AppUpdatePhase.CURRENT
                else -> AppUpdatePhase.UNAVAILABLE
            }
        }
        return AppUpdateSnapshot(
            phase = phase,
            versionCode = lastVersionCode,
            flexibleAllowed = isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
            bytesDownloaded = bytesDownloaded(),
            totalBytes = totalBytesToDownload(),
            problem = if (phase == AppUpdatePhase.FAILED) {
                updates.value?.problem ?: AppUpdateProblem.DOWNLOAD_FAILED
            } else null
        )
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}

class AppUpdatePreferences(context: Context) : AppUpdateSnoozeStore {
    private val preferences = context.applicationContext.getSharedPreferences("app_update_prefs", Context.MODE_PRIVATE)
    override fun read(): AppUpdateSnooze? {
        val version = preferences.getInt("dismissed_version", 0)
        val until = preferences.getLong("dismissed_until", 0)
        return if (version > 0 && until > 0) AppUpdateSnooze(version, until) else null
    }

    override fun write(snooze: AppUpdateSnooze) {
        preferences.edit().putInt("dismissed_version", snooze.versionCode)
            .putLong("dismissed_until", snooze.untilMillis).apply()
    }
}