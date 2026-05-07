package net.libreguard.vpn.ui.theme

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

object ThemePreferences {
    private const val PREFS_NAME = "appearance_prefs"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_DARK_MODE = "dark_mode_enabled"

    fun getThemeMode(context: Context): ThemeMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedThemeMode = prefs.getString(KEY_THEME_MODE, null)

        storedThemeMode?.let { value ->
            return runCatching { ThemeMode.valueOf(value) }.getOrDefault(ThemeMode.SYSTEM)
        }

        val migratedMode = if (prefs.contains(KEY_DARK_MODE)) {
            if (prefs.getBoolean(KEY_DARK_MODE, false)) ThemeMode.DARK else ThemeMode.LIGHT
        } else {
            ThemeMode.SYSTEM
        }

        prefs.edit()
            .putString(KEY_THEME_MODE, migratedMode.name)
            .remove(KEY_DARK_MODE)
            .apply()

        return migratedMode
    }

    fun setThemeMode(context: Context, themeMode: ThemeMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_MODE, themeMode.name)
            .remove(KEY_DARK_MODE)
            .apply()
    }

    fun applyThemeMode(context: Context) {
        AppCompatDelegate.setDefaultNightMode(toNightMode(getThemeMode(context)))
    }

    fun applyThemeMode(themeMode: ThemeMode) {
        AppCompatDelegate.setDefaultNightMode(toNightMode(themeMode))
    }

    private fun toNightMode(themeMode: ThemeMode): Int {
        return when (themeMode) {
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
    }
}

