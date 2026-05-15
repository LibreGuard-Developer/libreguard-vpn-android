package net.libreguard.vpn.util

import com.google.firebase.crashlytics.FirebaseCrashlytics

object CrashlyticsReporter {
    private val crashlytics: FirebaseCrashlytics?
        get() = runCatching { FirebaseCrashlytics.getInstance() }.getOrNull()

    fun setCollectionEnabled(enabled: Boolean) {
        crashlytics?.setCrashlyticsCollectionEnabled(enabled)
    }

    fun log(message: String) {
        crashlytics?.log(message)
    }

    fun setCustomKey(key: String, value: String) {
        crashlytics?.setCustomKey(key, value)
    }

    fun setCustomKey(key: String, value: Boolean) {
        crashlytics?.setCustomKey(key, value)
    }

    fun setCustomKey(key: String, value: Int) {
        crashlytics?.setCustomKey(key, value)
    }

    fun recordHandledException(throwable: Throwable, message: String? = null) {
        crashlytics?.also { reporter ->
            message?.let(reporter::log)
            reporter.recordException(throwable)
        }
    }
}

