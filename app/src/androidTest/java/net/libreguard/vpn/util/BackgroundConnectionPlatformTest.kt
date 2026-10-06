package net.libreguard.vpn.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.strongswan.android.logic.Scheduler

class BackgroundConnectionPlatformTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun verifiedExactAccessMatchesTheDeviceTestConfiguration() {
        val expected = InstrumentationRegistry.getArguments().getString("expectedExactAccess")
        if (expected != null) {
            assertEquals(expected.toBoolean(), BackgroundConnectionSupport(context).readStatus().exactAlarmAvailable)
        }
    }

    @Test fun androidSettingsIntentsUseTheAppPackageAndMissingActivitiesFallBack() {
        val launched = mutableListOf<Intent>()
        val missingContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun startActivity(intent: Intent) {
                launched += intent
                if (intent.action != Settings.ACTION_APPLICATION_DETAILS_SETTINGS) throw ActivityNotFoundException()
            }
        }
        val support = BackgroundConnectionSupport(missingContext)
        assertTrue(support.openBatterySettings())
        assertEquals(listOf(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        ), launched.map { it.action })
        assertEquals("package:${context.packageName}", launched.first().dataString)
        assertEquals("package:${context.packageName}", launched.last().dataString)
        if (Build.VERSION.SDK_INT >= 31) {
            launched.clear()
            assertTrue(support.openExactAlarmSettings())
            assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, launched.first().action)
            assertEquals("package:${context.packageName}", launched.first().dataString)
        }
    }

    @Test fun schedulerHandlesCurrentPlatformAccessWithoutLoadingOrStartingATunnel() {
        val completed = mutableListOf<String>()
        val scheduler = object : Scheduler(context) {
            override fun executeJob(id: String) { synchronized(completed) { completed += id } }
        }
        try {
            scheduler.scheduleJob("future", 60_000)
            scheduler.scheduleJob("due", 0)
            scheduler.onReceive(context, Intent("org.strongswan.android.Scheduler.EXECUTE_JOB"))
            assertEquals(listOf("due"), synchronized(completed) { completed.toList() })
            if (Build.VERSION.SDK_INT >= 31) {
                scheduler.onReceive(context, Intent(android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED))
                assertEquals(listOf("due"), synchronized(completed) { completed.toList() })
            }
        } finally {
            scheduler.Terminate()
        }
        scheduler.Terminate()
        scheduler.onReceive(context, Intent("org.strongswan.android.Scheduler.EXECUTE_JOB"))
        assertEquals(listOf("due"), synchronized(completed) { completed.toList() })
    }

    @Test fun android10AccessIsAvailableWithoutAnExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < 31) {
            assertEquals(true, BackgroundConnectionSupport(context).readStatus().exactAlarmAvailable)
        }
    }
}
