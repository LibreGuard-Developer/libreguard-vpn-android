package net.libreguard.vpn.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.util.Log
import net.libreguard.vpn.BuildConfig
import net.libreguard.vpn.util.CrashlyticsReporter
import java.io.File
import java.security.MessageDigest
import java.util.Locale

object AppIntegrityChecker {
    private const val TAG = "AppIntegrityChecker"

    @Volatile
    private var lastStartupReport: IntegrityReport? = null

    private val rootIndicatorPaths = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/vendor/bin/su",
        "/system/app/Superuser.apk",
        "/system/bin/magisk",
        "/sbin/magisk"
    )

    fun runStartupChecks(context: Context): IntegrityReport {
        val report = collectReport(context)
        publishReport(report)
        lastStartupReport = report
        return report
    }

    fun getLastStartupReport(): IntegrityReport? = lastStartupReport

    private fun collectReport(context: Context): IntegrityReport {
        val signingDigests = getSigningCertificateDigests(context.packageManager, context.packageName)
        val normalizedExpectedDigest = normalizeDigest(BuildConfig.APP_SIGNING_SHA256)
        val normalizedCurrentDigests = signingDigests.map(::normalizeDigest)
        val configuredSigningAllowlist = parseDigestAllowlist(
            BuildConfig.APP_SIGNING_SHA256_ALLOWLIST.ifBlank { BuildConfig.APP_SIGNING_SHA256 }
        )
        val matchedSigningDigest = signingDigests.firstOrNull {
            normalizeDigest(it) in configuredSigningAllowlist
        }
        val issues = mutableListOf<String>()

        val isDebuggerAttached = Debug.isDebuggerConnected() || Debug.waitingForDebugger()
        val hasTestKeys = Build.TAGS?.contains("test-keys", ignoreCase = true) == true
        val hasRootIndicators = rootIndicatorPaths.any { File(it).exists() }
        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val installerPackage = getInstallerPackage(context.packageManager, context.packageName)

        if (isDebuggerAttached) issues += "debugger_attached"
        if (isDebuggable && !BuildConfig.DEBUG) issues += "debuggable_release"
        if (hasTestKeys) issues += "test_keys_build"
        if (hasRootIndicators) issues += "root_indicators_present"
        if (normalizedCurrentDigests.isEmpty()) issues += "signing_digest_unavailable"
        if (normalizedExpectedDigest.isNotEmpty() && normalizedExpectedDigest !in normalizedCurrentDigests) {
            issues += "signing_digest_mismatch"
        }
        if (configuredSigningAllowlist.isNotEmpty() && matchedSigningDigest == null && normalizedCurrentDigests.isNotEmpty()) {
            issues += "signing_allowlist_mismatch"
        }

        val enforcementDecision = evaluateEnforcement(
            isDebugBuild = BuildConfig.DEBUG,
            enforcementEnabled = BuildConfig.ENABLE_RELEASE_SIGNING_ENFORCEMENT,
            configuredSigningAllowlist = configuredSigningAllowlist,
            currentSigningDigests = signingDigests
        )

        return IntegrityReport(
            installerPackage = installerPackage,
            signingDigests = signingDigests,
            configuredSigningAllowlist = configuredSigningAllowlist.toList(),
            matchedSigningDigest = matchedSigningDigest,
            debuggerAttached = isDebuggerAttached,
            testKeysDetected = hasTestKeys,
            rootIndicatorsDetected = hasRootIndicators,
            debuggableFlag = isDebuggable,
            issues = issues.distinct(),
            enforcementEnabled = BuildConfig.ENABLE_RELEASE_SIGNING_ENFORCEMENT,
            enforcementDecision = enforcementDecision
        )
    }

    private fun publishReport(report: IntegrityReport) {
        CrashlyticsReporter.setCustomKey("integrity_issue_count", report.issues.size)
        CrashlyticsReporter.setCustomKey("integrity_installer", report.installerPackage ?: "unknown")
        CrashlyticsReporter.setCustomKey("integrity_debugger_attached", report.debuggerAttached)
        CrashlyticsReporter.setCustomKey("integrity_test_keys_detected", report.testKeysDetected)
        CrashlyticsReporter.setCustomKey("integrity_root_indicators_detected", report.rootIndicatorsDetected)
        CrashlyticsReporter.setCustomKey("integrity_debuggable_flag", report.debuggableFlag)
        CrashlyticsReporter.setCustomKey("integrity_release_signing_enforcement_enabled", report.enforcementEnabled)
        CrashlyticsReporter.setCustomKey("integrity_signing_allowlist_configured", report.configuredSigningAllowlist.isNotEmpty())
        CrashlyticsReporter.setCustomKey("integrity_startup_blocked", report.enforcementDecision.shouldBlock)
        CrashlyticsReporter.setCustomKey(
            "integrity_startup_block_reason",
            report.enforcementDecision.reason ?: "none"
        )
        CrashlyticsReporter.setCustomKey(
            "integrity_signing_sha256",
            report.signingDigests.joinToString(",").ifBlank { "unavailable" }
        )
        CrashlyticsReporter.setCustomKey(
            "integrity_signing_allowlist",
            report.configuredSigningAllowlist.joinToString(",").ifBlank { "unconfigured" }
        )
        CrashlyticsReporter.setCustomKey(
            "integrity_matched_signing_sha256",
            report.matchedSigningDigest ?: "none"
        )
        CrashlyticsReporter.setCustomKey(
            "integrity_issues",
            report.issues.joinToString(",").ifBlank { "none" }
        )

        if (report.issues.isEmpty()) {
            val okMessage = "Integrity checks passed installer=${report.installerPackage ?: "unknown"} signingDigests=${report.signingDigests.joinToString(",").ifBlank { "unavailable" }}"
            Log.i(TAG, okMessage)
            CrashlyticsReporter.log(okMessage)
            return
        }

        val message = buildString {
            append("Integrity warnings: ")
            append(report.issues.joinToString(","))
            append(" installer=")
            append(report.installerPackage ?: "unknown")
            append(" signingDigests=")
            append(report.signingDigests.joinToString(",").ifBlank { "unavailable" })
        }
        Log.w(TAG, message)
        CrashlyticsReporter.log(message)

        if (
            "signing_digest_mismatch" in report.issues ||
            "signing_allowlist_mismatch" in report.issues ||
            report.enforcementDecision.shouldBlock
        ) {
            CrashlyticsReporter.recordHandledException(
                IllegalStateException(message),
                "Signing digest mismatch detected during app startup integrity checks."
            )
        }

        if (report.enforcementDecision.shouldBlock) {
            val blockMessage = buildString {
                append("Integrity enforcement blocking startup: ")
                append(report.enforcementDecision.reason ?: "unknown_reason")
                append(" installer=")
                append(report.installerPackage ?: "unknown")
                append(" signingDigests=")
                append(report.signingDigests.joinToString(",").ifBlank { "unavailable" })
            }
            Log.e(TAG, blockMessage)
            CrashlyticsReporter.log(blockMessage)
        }
    }

    private fun getInstallerPackage(packageManager: PackageManager, packageName: String): String? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                packageManager.getInstallSourceInfo(packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstallerPackageName(packageName)
            }
        }.getOrNull()
    }

    private fun getSigningCertificateDigests(
        packageManager: PackageManager,
        packageName: String
    ): List<String> {
        return runCatching {
            val packageInfo = getPackageInfo(packageManager, packageName)
            val signingInfo = packageInfo.signingInfo
            val signatures = if (signingInfo == null) {
                emptyArray()
            } else if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }

            signatures.map { signature ->
                val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                digest.joinToString(":") { byte -> "%02X".format(Locale.US, byte) }
            }.distinct().sorted()
        }.getOrElse { error ->
            Log.w(TAG, "Unable to read signing certificate digests", error)
            CrashlyticsReporter.recordHandledException(error, "Failed to inspect app signing certificate during startup integrity checks.")
            emptyList()
        }
    }

    private fun getPackageInfo(packageManager: PackageManager, packageName: String): PackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
    }

    fun normalizeDigest(value: String): String {
        return value
            .trim()
            .filter(Char::isLetterOrDigit)
            .uppercase(Locale.US)
    }

    fun parseDigestAllowlist(value: String): Set<String> {
        return value
            .split(',', ';', '\n', '\r')
            .map(::normalizeDigest)
            .filter { it.isNotEmpty() }
            .toSet()
    }

    fun evaluateEnforcement(
        isDebugBuild: Boolean,
        enforcementEnabled: Boolean,
        configuredSigningAllowlist: Set<String>,
        currentSigningDigests: List<String>
    ): EnforcementDecision {
        if (isDebugBuild) {
            return EnforcementDecision(shouldBlock = false, reason = null)
        }
        if (!enforcementEnabled) {
            return EnforcementDecision(shouldBlock = false, reason = null)
        }
        if (configuredSigningAllowlist.isEmpty()) {
            return EnforcementDecision(
                shouldBlock = false,
                reason = "signing_allowlist_missing"
            )
        }

        val normalizedCurrentDigests = currentSigningDigests
            .map(::normalizeDigest)
            .filter { it.isNotEmpty() }

        if (normalizedCurrentDigests.isEmpty()) {
            return EnforcementDecision(
                shouldBlock = false,
                reason = "signing_digest_unavailable"
            )
        }

        val matchesAllowlist = normalizedCurrentDigests.any { it in configuredSigningAllowlist }
        return if (matchesAllowlist) {
            EnforcementDecision(shouldBlock = false, reason = null)
        } else {
            EnforcementDecision(
                shouldBlock = true,
                reason = "signing_allowlist_mismatch"
            )
        }
    }

    data class IntegrityReport(
        val installerPackage: String?,
        val signingDigests: List<String>,
        val configuredSigningAllowlist: List<String>,
        val matchedSigningDigest: String?,
        val debuggerAttached: Boolean,
        val testKeysDetected: Boolean,
        val rootIndicatorsDetected: Boolean,
        val debuggableFlag: Boolean,
        val issues: List<String>,
        val enforcementEnabled: Boolean,
        val enforcementDecision: EnforcementDecision
    )

    data class EnforcementDecision(
        val shouldBlock: Boolean,
        val reason: String?
    )
}


