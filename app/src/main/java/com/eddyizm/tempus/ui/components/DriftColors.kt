package com.eddyizm.tempus.ui.components

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import com.eddyizm.tempus.util.Preferences

/**
 * Palette of the Compose screens, light or dark following the system theme.
 * [dark] is set by MainActivity.onCreate (the activity is recreated when the
 * system night mode changes). The accent stays in DriftAccent.
 */
object DriftColors {
    @JvmStatic
    var dark: Boolean = true

    /** Dark theme with the "true black" (AMOLED) option on. */
    @JvmStatic
    var black: Boolean = false

    @JvmStatic
    fun setFrom(context: Context) {
        val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        dark = mode == Configuration.UI_MODE_NIGHT_YES
        black = dark && runCatching { Preferences.isDarkThemeBlack() }.getOrDefault(false)
    }

    /**
     * The player always sits on a dark, cover-tinted background: in the light theme its views are inflated
     * with the dark resources so text and icons stay light and readable (as in Spotify / YouTube Music).
     */
    @JvmStatic
    fun playerInflater(inflater: android.view.LayoutInflater): android.view.LayoutInflater {
        val ctx = inflater.context
        val mode = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (mode == Configuration.UI_MODE_NIGHT_YES) return inflater
        val cfg = Configuration(ctx.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        }
        val night = androidx.appcompat.view.ContextThemeWrapper(ctx, com.eddyizm.tempus.R.style.AppTheme).apply { applyOverrideConfiguration(cfg) }
        com.eddyizm.tempus.helper.DriftAccent.apply(night)
        return inflater.cloneInContext(night)
    }

    /** Screen background. */
    val bg: Color get() = if (black) Color(0xFF000000) else if (dark) Color(0xFF0B0B0F) else Color(0xFFF7F7F9)
    /** Bottom sheets, dialogs, menus. */
    val surface: Color get() = if (black) Color(0xFF0A0A0A) else if (dark) Color(0xFF17171C) else Color(0xFFFFFFFF)
    val sheet: Color get() = surface
    /** Cards and rows on the background. */
    val card: Color get() = if (black) Color(0xFF121212) else if (dark) Color(0xFF1C1C22) else Color(0xFFECECF1)
    /** Raised card / chip / input field. */
    val cardHi: Color get() = if (black) Color(0xFF1E1E1E) else if (dark) Color(0xFF2A2A33) else Color(0xFFE0E0E7)
    val chip: Color get() = cardHi
    /** Item lifted while dragging (drawn with a shadow). */
    val raised: Color get() = if (dark) Color(0xFF22222A) else Color(0xFFFFFFFF)
    /** Primary text and icons. */
    val text: Color get() = if (dark) Color.White else Color(0xFF111114)
    /** Secondary text and icons. */
    val textLo: Color get() = if (dark) Color(0xFFA7A7B3) else Color(0xFF5C5C66)
    /** Translucent "glass" fill over the background. */
    val glass: Color get() = if (dark) Color(0x14FFFFFF) else Color(0x0A000000)
    /** Hairlines / separators. */
    val divider: Color get() = if (dark) Color(0x14FFFFFF) else Color(0x14000000)
    /** Thin borders around glass cards. */
    val border: Color get() = if (dark) Color(0x1FFFFFFF) else Color(0x1F000000)
    /** Outlines of buttons and inputs. */
    val stroke: Color get() = if (dark) Color(0x55FFFFFF) else Color(0x33000000)
    /** Disabled glyphs (e.g. reorder arrows at the ends of a list). */
    val textDisabled: Color get() = if (dark) Color(0xFF3A3A44) else Color(0xFFC4C4CC)
    val error: Color get() = Color(0xFFFF6B6B)

    /** Text color with alpha, for faint hints and disabled states. */
    fun textA(alpha: Float): Color = text.copy(alpha = alpha)
}
