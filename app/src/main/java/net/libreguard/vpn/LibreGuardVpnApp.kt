package net.libreguard.vpn

import android.os.Build
import android.util.Log
import org.strongswan.android.logic.StrongSwanApplication
import net.libreguard.vpn.network.RetrofitClient
import net.libreguard.vpn.ui.theme.ThemePreferences
import net.libreguard.vpn.util.CrashlyticsReporter
import net.libreguard.vpn.util.DeviceKeyManager
import net.libreguard.vpn.util.PassphraseDecryptor

class LibreGuardVpnApp : StrongSwanApplication() {
    override fun onCreate() {
        super.onCreate()
        ThemePreferences.applyThemeMode(this)
        // Application-specific initialization can go here
        CrashlyticsReporter.setCollectionEnabled(BuildConfig.DEBUG)
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
