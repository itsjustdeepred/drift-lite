package com.eddyizm.tempus.ui.components

import com.eddyizm.tempus.ui.components.DriftColors
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

/** Drift: small download progress bar (percentage when known, otherwise animated). */
@Composable
fun DriftDownloadBar(progress: Float?, modifier: Modifier = Modifier) {
    val m = modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
    val color = com.eddyizm.tempus.helper.DriftAccent.compose
    val track = if (DriftColors.dark) Color(0x33FFFFFF) else Color(0x22000000)
    if (progress == null) {
        LinearProgressIndicator(modifier = m, color = color, trackColor = track, strokeCap = StrokeCap.Round)
    } else {
        LinearProgressIndicator(progress = { (progress / 100f).coerceIn(0f, 1f) }, modifier = m, color = color,
            trackColor = track, strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
    }
}
