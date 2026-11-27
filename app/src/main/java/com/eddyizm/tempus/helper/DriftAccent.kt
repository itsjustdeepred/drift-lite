package com.eddyizm.tempus.helper

import android.content.Context
import android.graphics.Color
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils
import com.eddyizm.tempus.App
import com.eddyizm.tempus.R
import com.google.android.material.color.ColorResourcesOverride

/**
 * Drift: user-chosen accent color (Spotify-style green by default). Compose screens read it from here;
 * the XML theme gets it by overriding the primary/secondary colors at runtime (API 30+).
 */
object DriftAccent {
    const val KEY = "drift_accent"
    const val DEFAULT = 0xFF1FDF64.toInt()

    data class Preset(@StringRes val nameRes: Int, val color: Int) {
        val name: String get() = App.getContext().getString(nameRes)
    }

    @JvmField
    val PRESETS = listOf(
        Preset(R.string.drift_accent_green, DEFAULT),
        Preset(R.string.drift_accent_teal, 0xFF2DD4BF.toInt()),
        Preset(R.string.drift_accent_blue, 0xFF3D8BFF.toInt()),
        Preset(R.string.drift_accent_purple, 0xFFA970FF.toInt()),
        Preset(R.string.drift_accent_pink, 0xFFFF5FA2.toInt()),
        Preset(R.string.drift_accent_red, 0xFFFF4D5E.toInt()),
        Preset(R.string.drift_accent_orange, 0xFFFF9F1C.toInt()),
        Preset(R.string.drift_accent_yellow, 0xFFFFD23F.toInt()),
        Preset(R.string.drift_accent_white, 0xFFF2F2F2.toInt()),
    )

    @JvmStatic
    val color: Int get() = App.getInstance().preferences.getInt(KEY, DEFAULT)

    val compose get() = androidx.compose.ui.graphics.Color(color)

    @JvmStatic
    fun set(c: Int) { App.getInstance().preferences.edit().putInt(KEY, c or 0xFF000000.toInt()).apply() }

    @JvmStatic
    fun name(c: Int = color) = PRESETS.firstOrNull { it.color == c }?.name ?: "#%06X".format(c and 0xFFFFFF)

    /** Readable text color on top of the accent. */
    @JvmStatic
    fun onAccent(c: Int = color) = if (ColorUtils.calculateLuminance(c) > 0.4) Color.BLACK else Color.WHITE

    @JvmStatic
    fun apply(context: Context) {
        val c = color
        if (c == DEFAULT) return
        val on = onAccent(c)
        val container = ColorUtils.blendARGB(0xFF121212.toInt(), c, 0.22f)
        val onContainer = ColorUtils.blendARGB(c, Color.WHITE, 0.7f)
        ColorResourcesOverride.getInstance()?.applyIfPossible(context, mapOf(
            R.color.md_theme_dark_primary to c, R.color.md_theme_dark_secondary to c,
            R.color.md_theme_dark_onPrimary to on, R.color.md_theme_dark_onSecondary to on,
            R.color.md_theme_dark_primaryContainer to container, R.color.md_theme_dark_onPrimaryContainer to onContainer,
            R.color.md_theme_light_primary to c, R.color.md_theme_light_onPrimary to on,
        ))
    }
}
