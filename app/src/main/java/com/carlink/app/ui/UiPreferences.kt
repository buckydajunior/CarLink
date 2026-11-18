package com.carlink.app.ui

import android.content.Context
import com.carlink.app.ui.theme.RenderingMode
import com.carlink.app.ui.theme.ThemeMode
import com.carlink.app.ui.theme.UiProfileMode

/** The display preferences retained by the dedicated wired receiver. */
class UiPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("carlink", Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = enumOr(prefs.getString("theme_mode", null), ThemeMode.FOLLOW_SYSTEM)
        set(value) = prefs.edit().putString("theme_mode", value.name).apply()
    var uiProfileMode: UiProfileMode
        get() = enumOr(prefs.getString("ui_profile_mode", null), UiProfileMode.CAR)
        set(value) { prefs.edit().putString("ui_profile_mode", value.name).apply(); applyScreenType(value) }
    var interfaceScale: Float
        get() = prefs.getFloat("interface_scale", 1.15f).coerceIn(0.85f, 1.35f)
        set(value) = prefs.edit().putFloat("interface_scale", value.coerceIn(0.85f, 1.35f)).apply()
    var renderingMode: RenderingMode
        get() = enumOr(prefs.getString("rendering_mode", null), RenderingMode.LITE)
        set(value) = prefs.edit().putString("rendering_mode", value.name).apply()

    private inline fun <reified T : Enum<T>> enumOr(value: String?, default: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
}

internal fun applyScreenType(mode: UiProfileMode) {
    com.carlink.core.detect.ScreenType.forcedHandheld = when (mode) {
        UiProfileMode.AUTO -> null
        UiProfileMode.CAR -> false
        UiProfileMode.HANDHELD -> true
    }
}
