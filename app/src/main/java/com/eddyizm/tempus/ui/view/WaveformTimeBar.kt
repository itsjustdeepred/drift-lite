package com.eddyizm.tempus.ui.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.TimeBar
import com.eddyizm.tempus.helper.DriftMixes
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Drift: waveform-shaped progress bar. Replaces DefaultTimeBar with the same id (exo_progress),
 * so PlayerControlView drives it on its own; the bars come from the sidecar (the file's real waveform).
 */
@UnstableApi
class WaveformTimeBar @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs), TimeBar {

    private val listeners = CopyOnWriteArraySet<TimeBar.OnScrubListener>()
    private var position = 0L
    private var buffered = 0L
    private var duration = 0L
    private var scrubbing = false
    private var scrubPosition = 0L
    private var bars: IntArray? = null
    private var songId: String? = null
    private var fallbackDuration = 0L
    private var positionLabel: android.widget.TextView? = null
    private var remainingLabel: android.widget.TextView? = null

    /** Duration known from the server: a transcoded stream sometimes does not report it to the player (Symfonium does the same). */
    private val total get() = if (duration > 0) duration else fallbackDuration

    /** Labels handled here (not by PlayerControlView): position on the left, remaining time on the right. */
    fun setLabels(position: android.widget.TextView?, remaining: android.widget.TextView?) {
        positionLabel = position; remainingLabel = remaining; updateLabels()
    }

    private fun fmt(ms: Long): String { val s = (ms.coerceAtLeast(0) + 500) / 1000; return "%d:%02d".format(s / 60, s % 60) }

    private fun updateLabels() {
        val p = if (scrubbing) scrubPosition else position
        positionLabel?.text = fmt(p)
        remainingLabel?.text = if (total > 0) "-" + fmt(total - p) else "--:--"
    }

    private val density = resources.displayMetrics.density
    private val played = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val unplayed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF }
    private val rect = RectF()
    private val main = Handler(Looper.getMainLooper())

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (44 * density).roundToInt()
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    /** Loads the song's waveform; until it arrives (or without a sidecar) a flat low line is shown. */
    fun setSong(id: String?, durationMs: Long) {
        fallbackDuration = durationMs
        updateLabels()
        if (id == songId) return
        songId = id
        bars = null
        invalidate()
        if (id == null) return
        io.execute {
            val w = DriftMixes.wave(id)
            main.post { if (songId == id) { bars = w; invalidate() } }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width - paddingLeft - paddingRight
        if (w <= 0) return
        val data = bars
        val n = data?.size ?: 100
        val slot = w.toFloat() / n
        val barW = (slot * 0.6f).coerceAtLeast(1.5f * density)
        val maxH = height - paddingTop - paddingBottom
        val cy = paddingTop + maxH / 2f
        val shown = if (scrubbing) scrubPosition else position
        val progressX = if (total > 0) paddingLeft + w * (shown.toFloat() / total).coerceIn(0f, 1f) else paddingLeft.toFloat()
        val r = barW / 2
        for (i in 0 until n) {
            val v = data?.get(i) ?: 12
            val bh = (maxH * (v.coerceIn(6, 100) / 100f)).coerceAtLeast(barW)
            val x = paddingLeft + i * slot + (slot - barW) / 2
            rect.set(x, cy - bh / 2, x + barW, cy + bh / 2)
            canvas.drawRoundRect(rect, r, r, if (x + barW / 2 <= progressX) played else unplayed)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled || total <= 0) return false
        val w = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val pos = (((e.x - paddingLeft) / w).coerceIn(0f, 1f) * total).toLong()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)   // the bottom sheet must not drag
                scrubbing = true; scrubPosition = pos
                listeners.forEach { it.onScrubStart(this, pos) }
            }
            MotionEvent.ACTION_MOVE -> if (scrubbing) {
                scrubPosition = pos
                listeners.forEach { it.onScrubMove(this, pos) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (scrubbing) {
                scrubbing = false; position = pos
                listeners.forEach { it.onScrubStop(this, pos, e.actionMasked == MotionEvent.ACTION_CANCEL) }
            }
        }
        updateLabels()
        invalidate()
        return true
    }

    override fun addListener(listener: TimeBar.OnScrubListener) { listeners.add(listener) }
    override fun removeListener(listener: TimeBar.OnScrubListener) { listeners.remove(listener) }
    override fun setKeyTimeIncrement(time: Long) {}
    override fun setKeyCountIncrement(count: Int) {}
    override fun setPosition(position: Long) { if (this.position != position) { this.position = position; updateLabels(); invalidate() } }
    override fun setBufferedPosition(bufferedPosition: Long) { buffered = bufferedPosition }
    override fun setDuration(duration: Long) { if (this.duration != duration) { this.duration = duration; updateLabels(); invalidate() } }
    override fun getPreferredUpdateDelay(): Long {
        val w = width.coerceAtLeast(1)
        return if (total <= 0) 1000L else (total / w).coerceIn(30L, 1000L)
    }
    override fun setAdGroupTimesMs(adGroupTimesMs: LongArray?, playedAdGroups: BooleanArray?, adGroupCount: Int) {}

    private companion object {
        val io = Executors.newSingleThreadExecutor()
    }
}
