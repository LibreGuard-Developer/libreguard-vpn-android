package net.libreguard.vpn
import org.strongswan.android.logic.StrongSwanApplication
import net.libreguard.vpn.network.RetrofitClient

class LibreGuardVpnApp : StrongSwanApplication() {
    override fun onCreate() {
        super.onCreate()
        // Application-specific initialization can go here
        RetrofitClient.init(this)
    }
}
