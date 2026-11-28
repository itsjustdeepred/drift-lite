package com.eddyizm.tempus.helper

import android.content.Context
import android.graphics.Color
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils
import com.eddyizm.tempus.App
import com.eddyizm.tempus.R

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
        // ordered by hue, so the grid reads as a color wheel
        Preset(R.string.drift_accent_green, DEFAULT),
        Preset(R.string.drift_accent_mint, 0xFF5EEAD4.toInt()),
        Preset(R.string.drift_accent_teal, 0xFF2DD4BF.toInt()),
        Preset(R.string.drift_accent_cyan, 0xFF22D3EE.toInt()),
        Preset(R.string.drift_accent_sky, 0xFF7CC4FF.toInt()),
        Preset(R.string.drift_accent_blue, 0xFF3D8BFF.toInt()),
        Preset(R.string.drift_accent_indigo, 0xFF7C7CFF.toInt()),
        Preset(R.string.drift_accent_purple, 0xFFA970FF.toInt()),
        Preset(R.string.drift_accent_lilac, 0xFFD8B4FE.toInt()),
        Preset(R.string.drift_accent_magenta, 0xFFE040FB.toInt()),
        Preset(R.string.drift_accent_pink, 0xFFFF5FA2.toInt()),
        Preset(R.string.drift_accent_red, 0xFFFF4D5E.toInt()),
        Preset(R.string.drift_accent_coral, 0xFFFF7F66.toInt()),
        Preset(R.string.drift_accent_orange, 0xFFFF9F1C.toInt()),
        Preset(R.string.drift_accent_gold, 0xFFF5C451.toInt()),
        Preset(R.string.drift_accent_yellow, 0xFFFFE14D.toInt()),
        Preset(R.string.drift_accent_lime, 0xFFB8F34A.toInt()),
        Preset(R.string.drift_accent_white, 0xFFF2F2F2.toInt()),
    )

    @JvmStatic
    val color: Int get() = App.getInstance().preferences.getInt(KEY, DEFAULT)

    val compose get() = androidx.compose.ui.graphics.Color(color)

    /** The accent for text and thin lines: on a light background light accents (yellow, gold, lime, white) get darker to stay readable. */
    @JvmStatic
    fun textColor(): Int {
        val c = color
        if (com.eddyizm.tempus.ui.components.DriftColors.dark || ColorUtils.calculateLuminance(c) < 0.35) return c
        return ColorUtils.blendARGB(c, Color.BLACK, 0.45f)
    }

    val composeText: androidx.compose.ui.graphics.Color get() = androidx.compose.ui.graphics.Color(textColor())

    @JvmStatic
    fun set(c: Int) { App.getInstance().preferences.edit().putInt(KEY, c or 0xFF000000.toInt()).apply() }

    @JvmStatic
    fun name(c: Int = color): String {
        // the nearest preset by name; "Custom" only for a color far from all of them (never a hex code)
        val p = PRESETS.minByOrNull { distance(it.color, c) } ?: return App.getContext().getString(R.string.drift_accent_custom_name)
        return if (distance(p.color, c) < 9000) p.name else App.getContext().getString(R.string.drift_accent_custom_name)
    }

    /** Readable text color on top of the accent. */
    @JvmStatic
    fun onAccent(c: Int = color) = if (ColorUtils.calculateLuminance(c) > 0.4) Color.BLACK else Color.WHITE

    // Theme overlays for the presets, same order as PRESETS (res/values*/drift_accents.xml, light and dark variants)
    private val OVERLAYS = listOf(
        R.style.DriftAccent_Green,
        R.style.DriftAccent_Mint,
        R.style.DriftAccent_Teal,
        R.style.DriftAccent_Cyan,
        R.style.DriftAccent_Sky,
        R.style.DriftAccent_Blue,
        R.style.DriftAccent_Indigo,
        R.style.DriftAccent_Purple,
        R.style.DriftAccent_Lilac,
        R.style.DriftAccent_Magenta,
        R.style.DriftAccent_Pink,
        R.style.DriftAccent_Red,
        R.style.DriftAccent_Coral,
        R.style.DriftAccent_Orange,
        R.style.DriftAccent_Gold,
        R.style.DriftAccent_Yellow,
        R.style.DriftAccent_Lime,
        R.style.DriftAccent_White,
    )

    /**
     * Applies the accent to the XML screens (settings, dialogs, switches) through a theme overlay, before the
     * activity inflates its views. A custom color uses the nearest preset there; Compose screens use the exact color.
     * (Rewriting the color resources at runtime turned whole screens white on some phones.)
     */
    @JvmStatic
    fun apply(context: Context) {
        val c = color
        val i = PRESETS.indices.minByOrNull { distance(PRESETS[it].color, c) } ?: return
        context.theme.applyStyle(OVERLAYS[i], true)

    }

    /** The preset closest to [c] (the color itself when it is a preset). */
    @JvmStatic
    fun nearestPreset(c: Int): Int = PRESETS.minByOrNull { distance(it.color, c) }?.color ?: c

    private fun distance(a: Int, b: Int): Int {
        val dr = Color.red(a) - Color.red(b); val dg = Color.green(a) - Color.green(b); val db = Color.blue(a) - Color.blue(b)
        return dr * dr * 2 + dg * dg * 4 + db * db * 3
    }

}
