package net.libreguard.vpn.util

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import net.libreguard.vpn.BuildConfig

private fun Context.getInstalledPackageInfo(): PackageInfo {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0)
    }
}

fun Context.getInstalledAppVersionName(): String {
    return runCatching { getInstalledPackageInfo().versionName }
        .getOrNull()
        .orEmpty()
        .ifBlank { BuildConfig.VERSION_NAME }
}

fun Context.getInstalledAppVersionCode(): Long {
    return runCatching {
        getInstalledPackageInfo().longVersionCode
    }.getOrElse { BuildConfig.VERSION_CODE.toLong() }
}
