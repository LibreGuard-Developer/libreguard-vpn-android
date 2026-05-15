package net.libreguard.vpn

import android.os.Build
import android.util.Log
import net.libreguard.vpn.network.RetrofitClient
import net.libreguard.vpn.security.AppIntegrityChecker
import net.libreguard.vpn.ui.theme.ThemePreferences
import net.libreguard.vpn.util.BouncyCastleBootstrap
import net.libreguard.vpn.util.CrashlyticsReporter
import net.libreguard.vpn.util.DeviceKeyManager
import net.libreguard.vpn.util.PassphraseDecryptor
import org.strongswan.android.logic.StrongSwanApplication

class LibreGuardVpnApp : StrongSwanApplication() {
    override fun onCreate() {
        super.onCreate()
        BouncyCastleBootstrap.ensureExternalProviderRegistered()
        ThemePreferences.applyThemeMode(this)
        // Application-specific initialization can go here
        CrashlyticsReporter.setCollectionEnabled(!BuildConfig.DEBUG)
        AppIntegrityChecker.runStartupChecks(this)
        DeviceKeyManager.init(this)
        RetrofitClient.init(this)

        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        CrashlyticsReporter.log("App startup package=$packageName versionName=${packageInfo.versionName}")
        Log.i(
            "LibreGuardVpnApp",
            "App startup package=$packageName versionName=${packageInfo.versionName} versionCode=${packageInfo.longVersionCode} sdk=${Build.VERSION.SDK_INT} decryptRevision=${PassphraseDecryptor.revision()}"
        )
    }
}
