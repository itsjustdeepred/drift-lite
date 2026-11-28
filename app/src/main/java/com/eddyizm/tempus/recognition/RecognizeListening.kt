package com.eddyizm.tempus.recognition

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.eddyizm.tempus.R
import com.eddyizm.tempus.service.MediaManager
import com.eddyizm.tempus.service.MediaService
import com.eddyizm.tempus.ui.activity.MainActivity
import com.eddyizm.tempus.ui.components.DriftColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val LAcc get() = com.eddyizm.tempus.helper.DriftAccent.compose
private val LHi get() = DriftColors.text
private val LLo get() = DriftColors.textLo

/** The listening screen, shared with the full app. */
@Composable
fun RecognizeListening(level: Float, searching: Boolean) {
    val t = rememberInfiniteTransition(label = "waves")
    val wave by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart), label = "w")
    val lvl by animateFloatAsState(level, tween(90), label = "lvl")
    // the circle starts big (like the button that was just pressed) and settles a little higher
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { settled = true }
    val circle by animateDpAsState(if (settled) 150.dp else 190.dp, tween(450), label = "c")
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(0.8f))
        Box(Modifier.size(320.dp), contentAlignment = Alignment.Center) {
            for (k in 0 until 3) {
                val f = (wave + k / 3f) % 1f
                Box(Modifier.size(circle).graphicsLayer {
                    val sc = 1f + f * (0.9f + lvl * 0.6f); scaleX = sc; scaleY = sc; alpha = (1f - f) * (0.18f + lvl * 0.35f)
                }.clip(CircleShape).border(2.dp, LAcc, CircleShape))
            }
            val pulse = 1f + lvl * 0.08f
            Box(Modifier.size(circle).graphicsLayer { scaleX = pulse; scaleY = pulse }.clip(CircleShape).background(LAcc), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_drift_mic), null, colorFilter = ColorFilter.tint(Color.Black), modifier = Modifier.size(60.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        Bars(lvl, searching)
        Spacer(Modifier.height(20.dp))
        AnimatedContent(searching, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) }, label = "txt") { s ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(if (s) R.string.lite_rec_searching else R.string.lite_rec_listening), color = LHi, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Text(stringResource(if (s) R.string.lite_rec_searching_sub else R.string.lite_rec_listening_sub), color = LLo, fontSize = 14.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp))
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

/** Five bars that follow the microphone; while searching they turn into a slow "thinking" ripple. */
@Composable
private fun Bars(level: Float, searching: Boolean) {
    val t = rememberInfiniteTransition(label = "bars")
    val ph by t.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(900, easing = LinearEasing)), label = "ph")
    Row(Modifier.height(36.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val shape = floatArrayOf(0.55f, 0.8f, 1f, 0.8f, 0.55f)
        for (i in 0 until 5) {
            val wob = ((kotlin.math.sin(ph + i * 1.1f) + 1f) / 2f)
            val h = if (searching) 0.25f + wob * 0.25f else (0.18f + level * shape[i] * (0.6f + wob * 0.4f)).coerceIn(0.18f, 1f)
            Box(Modifier.width(5.dp).height((36 * h).dp).clip(RoundedCornerShape(3.dp)).background(LHi))
        }
    }
}

