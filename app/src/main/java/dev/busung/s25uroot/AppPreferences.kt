package dev.busung.s25uroot

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList

enum class AccentColor(val storedValue: String) {
    Dynamic("dynamic"),
    Blue("blue"),
    Violet("violet"),
    Green("green"),
    Orange("orange"),
    Purple("purple"),
    Red("red"),
    Pink("pink"),
    Teal("teal"),
    Yellow("yellow"),
    Monochrome("monochrome");

    companion object {
        fun fromStoredValue(value: String?): AccentColor =
            entries.firstOrNull { it.storedValue == value } ?: Dynamic
    }
}

enum class AppThemeMode(val storedValue: String) {
    System("system"),
    Light("light"),
    Dark("dark");

    companion object {
        fun fromStoredValue(value: String?): AppThemeMode =
            entries.firstOrNull { it.storedValue == value } ?: System
    }
}

object AppPreferences {
    private const val PREFERENCES = "appearance"
    private const val ACCENT_COLOR = "accent_color"
    private const val THEME_MODE = "theme_mode"
    private const val ADVANCED_MODE = "advanced_mode"
    private const val SHIZUKU_MODE = "shizuku_mode"
    private const val LOCAL_PAYLOAD_MODE = "local_payload_mode"
    private const val AUTO_REROOT = "auto_reroot"
    private const val REBOOT_AFTER_INSTALL = "reboot_after_install"
    private const val CONSUMED_INSTALL_REQUEST = "consumed_install_request"
    private const val DEBUG_LOG = "debug_log"

    fun accentColor(context: Context): AccentColor = AccentColor.fromStoredValue(
        prefs(context).getString(ACCENT_COLOR, null),
    )

    fun setAccentColor(context: Context, color: AccentColor) {
        prefs(context).edit()
            .putString(ACCENT_COLOR, color.storedValue)
            .apply()
    }

    fun themeMode(context: Context): AppThemeMode = AppThemeMode.fromStoredValue(
        prefs(context).getString(THEME_MODE, null),
    )

    fun setThemeMode(context: Context, themeMode: AppThemeMode) {
        prefs(context).edit()
            .putString(THEME_MODE, themeMode.storedValue)
            .apply()
    }

    fun advancedMode(context: Context): Boolean =
        prefs(context).getBoolean(ADVANCED_MODE, true)

    fun setAdvancedMode(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(ADVANCED_MODE, enabled)
            .apply()
    }

    fun shizukuMode(context: Context): Boolean =
        prefs(context).getBoolean(SHIZUKU_MODE, false)

    fun setShizukuMode(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(SHIZUKU_MODE, enabled)
            .apply()
    }

    fun localPayloadMode(context: Context): Boolean =
        prefs(context).getBoolean(LOCAL_PAYLOAD_MODE, false)

    fun setLocalPayloadMode(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(LOCAL_PAYLOAD_MODE, enabled)
            .apply()
    }

    /** Whether the app should automatically re-root the device on every boot. */
    fun autoReroot(context: Context): Boolean =
        prefs(context).getBoolean(AUTO_REROOT, true)

    fun setAutoReroot(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(AUTO_REROOT, enabled)
            .apply()
    }

    fun rebootAfterInstall(context: Context): Boolean =
        prefs(context).getBoolean(REBOOT_AFTER_INSTALL, false)

    fun setRebootAfterInstall(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(REBOOT_AFTER_INSTALL, enabled)
            .apply()
    }

    /**
     * When enabled, verbose [DBG] lines are emitted into the install log
     * (environment variables, staged paths, raw command output, timing data).
     * Off by default; surfaced under Advanced options.
     */
    fun debugLog(context: Context): Boolean =
        prefs(context).getBoolean(DEBUG_LOG, false)

    fun setDebugLog(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(DEBUG_LOG, enabled)
            .apply()
    }

    fun consumePendingInstallRequest(context: Context): Boolean {
        val prefs = prefs(context)
        val consumed = prefs.getBoolean(CONSUMED_INSTALL_REQUEST, false)
        if (!consumed) {
            prefs.edit().putBoolean(CONSUMED_INSTALL_REQUEST, true).apply()
        }
        return consumed
    }

    fun resetPendingInstallRequest(context: Context) {
        prefs(context).edit().putBoolean(CONSUMED_INSTALL_REQUEST, false).apply()
    }

    fun languageTag(context: Context): String {
        val localeManager = context.getSystemService(LocaleManager::class.java)
        return localeManager?.applicationLocales?.toLanguageTags().orEmpty()
    }

    fun setLanguageTag(context: Context, tag: String) {
        val localeManager = context.getSystemService(LocaleManager::class.java) ?: return
        val locales = if (tag.isBlank()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        localeManager.applicationLocales = locales
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
