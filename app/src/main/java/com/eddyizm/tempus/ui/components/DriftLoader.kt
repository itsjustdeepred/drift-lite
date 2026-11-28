package com.eddyizm.tempus.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eddyizm.tempus.R
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Drift: "space travel" loading screen. Logo and name in the center, colored music notes flying from the
 * back toward the viewer (like stars at light speed). Takes all the space it is given.
 */
@Composable
fun DriftLoader(modifier: Modifier = Modifier.fillMaxSize()) {
    val glyphs = remember { arrayOf("♪", "♫", "♩", "♬") }
    val accent = com.eddyizm.tempus.helper.DriftAccent.compose.toArgb()
    val palette = remember(accent) { intArrayOf(accent, 0xFF22D3EE.toInt(), 0xFF8B5CF6.toInt(), 0xFFFF5FA2.toInt(), 0xFFFFC145.toInt(), 0xFF5B8CFF.toInt()) }

    class Note(var a: Float, var r: Float, var z: Float, val glyph: String, val color: Int, val spin: Float)
    fun spawn(rnd: Random, far: Boolean) = Note(
        a = rnd.nextFloat() * 6.2832f, r = 0.15f + rnd.nextFloat() * 0.85f,
        z = if (far) 1f else 0.15f + rnd.nextFloat() * 0.85f,
        glyph = glyphs[rnd.nextInt(glyphs.size)], color = palette[rnd.nextInt(palette.size)], spin = rnd.nextFloat() * 40f - 20f,
    )
    val rnd = remember { Random(System.nanoTime()) }
    val notes = remember(palette) { Array(46) { spawn(rnd, far = false) } }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) withFrameNanos { now ->
            if (last != 0L) {
                val dt = ((now - last) / 1e9f).coerceAtMost(0.05f)
                notes.forEachIndexed { i, n -> n.z -= dt * 0.32f; if (n.z <= 0.06f) notes[i] = spawn(rnd, far = true) }
            }
            last = now; frame = now
        }
    }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD } }
    val pulse by rememberInfiniteTransition(label = "loader").animateFloat(0.94f, 1.06f,
        infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION") frame   // redraw on every frame
            val cx = size.width / 2; val cy = size.height / 2
            val reach = maxOf(size.width, size.height) * 0.55f
            notes.sortedByDescending { it.z }.forEach { n ->
                val k = 1f / n.z
                val x = cx + cos(n.a) * n.r * reach * 0.18f * k
                val y = cy + sin(n.a) * n.r * reach * 0.18f * k
                if (x < -80 || x > size.width + 80 || y < -80 || y > size.height + 80) return@forEach
                val alpha = ((1f - n.z) * 1.6f).coerceIn(0f, 1f)
                paint.color = n.color
                paint.alpha = (alpha * 255).toInt()
                paint.textSize = (10f + 26f * k).coerceAtMost(140f) * density / 2.2f
                val c = drawContext.canvas.nativeCanvas
                c.save(); c.rotate(n.spin * (1f - n.z), x, y); c.drawText(n.glyph, x, y, paint); c.restore()
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // adaptive icon composed by hand (Compose does not draw adaptive-icon)
            Box(Modifier.size(96.dp).scale(pulse).clip(androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.size(144.dp), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(144.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.app_name), color = DriftColors.text, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}
