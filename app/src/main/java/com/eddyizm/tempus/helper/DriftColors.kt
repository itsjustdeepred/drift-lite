package com.eddyizm.tempus.helper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.eddyizm.tempus.glide.CustomGlideRequest

/**
 * Drift: Spotify-style color extracted from the cover art. Average of the saturated pixels,
 * brought to a fixed dark brightness so white text stays readable.
 */
object DriftColors {

    private const val BG = 0xFF0B0B0F.toInt()
    private var lastId: String? = null
    var current: Int = 0xFF2A2A33.toInt()
        private set
    private val listeners = mutableListOf<(Int) -> Unit>()

    @JvmStatic
    fun addListener(l: (Int) -> Unit) { listeners += l; l(current) }

    @JvmStatic
    fun removeAllListeners() = listeners.clear()

    @JvmStatic
    fun fromBitmap(b: Bitmap): Int {
        // HARDWARE bitmaps cannot be read pixel by pixel
        val src = if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
        val s = Bitmap.createScaledBitmap(src, 24, 24, true)
        var r = 0L; var g = 0L; var bl = 0L; var w = 0L
        val hsv = FloatArray(3)
        for (x in 0 until 24) for (y in 0 until 24) {
            val c = s.getPixel(x, y)
            Color.colorToHSV(c, hsv)
            // weigh colorful pixels that are not too dark/bright more
            val weight = (1 + (hsv[1] * 10).toLong()) * (if (hsv[2] in 0.15f..0.95f) 2 else 1)
            r += Color.red(c) * weight; g += Color.green(c) * weight; bl += Color.blue(c) * weight; w += weight
        }
        val avg = Color.rgb((r / w).toInt(), (g / w).toInt(), (bl / w).toInt())
        Color.colorToHSV(avg, hsv)
        hsv[1] = hsv[1].coerceIn(0.35f, 0.75f)
        hsv[2] = 0.36f
        return Color.HSVToColor(hsv)
    }

    /** Loads the (small) cover art and updates the current color. */
    @JvmStatic
    fun update(context: Context, coverArtId: String?) {
        if (coverArtId == null || coverArtId == lastId) return
        lastId = coverArtId
        Glide.with(context.applicationContext).asBitmap()
            .load(CustomGlideRequest.createUrl(coverArtId, 64))
            .disallowHardwareConfig()
            .into(object : CustomTarget<Bitmap>(64, 64) {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    if (coverArtId != lastId) return  // arrived after a track change: stale color
                    current = fromBitmap(resource)
                    listeners.toList().forEach { it(current) }
                }
                override fun onLoadCleared(placeholder: Drawable?) {}
            })
    }

    /** Rounded card of the mini player. */
    @JvmStatic
    fun paintCard(v: View, color: Int) {
        v.background = GradientDrawable().apply {
            cornerRadius = v.resources.displayMetrics.density * 8
            setColor(color)
        }
    }

    /** Full-screen player background: color at the top fading into black. */
    @JvmStatic
    fun paintPlayer(v: View, color: Int) {
        val top = lighten(color, 1.25f)
        v.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(top, color, blend(color, BG, 0.75f), BG)
        )
    }

    @JvmStatic
    fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
        (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
        (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt(),
    )

    private fun lighten(c: Int, f: Float): Int {
        val hsv = FloatArray(3); Color.colorToHSV(c, hsv); hsv[2] = (hsv[2] * f).coerceAtMost(1f)
        return Color.HSVToColor(hsv)
    }
}
