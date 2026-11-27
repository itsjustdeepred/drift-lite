package com.eddyizm.tempus.ui.fragment

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.eddyizm.tempus.App
import com.eddyizm.tempus.R
import com.eddyizm.tempus.helper.DriftRecognition
import com.eddyizm.tempus.subsonic.models.Child
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/*
 * Drift: Shazam-style "Recognize song". The microphone records ~10 s (mono AAC 44.1 kHz in cache), the file goes
 * to Undertow (POST /recognize), which returns title/artist with the Navidrome id if the song is in the library,
 * or the Deezer id to download it. RECORD_AUDIO is requested on first tap, after a short explanation.
 */

private const val CLIP_MS = 10_000L

/** What recognition needs from the hosting fragment. */
class RecognizeHooks(
    val hasMic: () -> Boolean,
    /** Requests RECORD_AUDIO; the answer: (granted, permanently blocked). */
    val askMic: ((Boolean, Boolean) -> Unit) -> Unit,
    val openAppSettings: () -> Unit,
    val play: (Child) -> Unit,
    val enqueue: (Child) -> Unit,
    val download: (Long) -> Unit,
    val search: (String) -> Unit,
    val toast: (String) -> Unit,
)

/** Clip recorder: AAC in an MPEG-4 container, mono, 44.1 kHz. */
private class ClipRecorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null

    fun start(out: File) {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        rec = r
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(44_100)
        r.setAudioEncodingBitRate(96_000)
        r.setOutputFile(out.absolutePath)
        r.prepare()
        r.start()
    }

    /** true if the file is valid. */
    fun stop(): Boolean = try { rec?.stop(); true } catch (e: RuntimeException) { false } finally { release() }

    fun release() {
        runCatching { rec?.reset() }
        runCatching { rec?.release() }
        rec = null
    }
}

/**
 * Button + full flow: permission, full-screen listening, result.
 * [requests] grows by one on every request (mic tap, or arriving from home).
 */
@Composable
fun RecognizeHost(requests: Int, hooks: RecognizeHooks) {
    val ctx = LocalContext.current
    var rationale by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }

    fun ask() = hooks.askMic { granted, isBlocked ->
        when {
            granted -> open = true
            isBlocked -> blocked = true
            else -> hooks.toast(ctx.getString(R.string.drift_recog_denied))
        }
    }

    LaunchedEffect(requests) {
        if (requests <= 0) return@LaunchedEffect
        if (hooks.hasMic()) open = true else rationale = true
    }

    if (rationale) AlertDialog(
        onDismissRequest = { rationale = false },
        title = { Text(stringResource(R.string.drift_recog_rationale_title)) },
        text = { Text(stringResource(R.string.drift_recog_rationale)) },
        confirmButton = { TextButton(onClick = { rationale = false; ask() }) { Text(stringResource(R.string.drift_recog_continue)) } },
        dismissButton = { TextButton(onClick = { rationale = false }) { Text(stringResource(R.string.drift_recog_not_now)) } },
    )
    if (blocked) AlertDialog(
        onDismissRequest = { blocked = false },
        text = { Text(stringResource(R.string.drift_recog_blocked)) },
        confirmButton = { TextButton(onClick = { blocked = false; hooks.openAppSettings() }) { Text(stringResource(R.string.drift_recog_open_settings)) } },
        dismissButton = { TextButton(onClick = { blocked = false }) { Text(stringResource(R.string.drift_recog_close)) } },
    )
    if (open) RecognizeScreen(hooks, onClose = { open = false })
}

/** The microphone icon in the search bar. */
@Composable
fun RecognizeMicButton(tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_drift_mic), stringResource(R.string.drift_recog_button), colorFilter = ColorFilter.tint(tint), modifier = Modifier.size(24.dp))
    }
}

private enum class Phase { LISTENING, SEARCHING, RESULT }

private val RBg = Color(0xFF0B0B0F)
private val RAccent get() = com.eddyizm.tempus.helper.DriftAccent.compose
private val RHi = Color.White
private val RLo = Color(0xFFA7A7B3)

@Composable
private fun RecognizeScreen(hooks: RecognizeHooks, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var attempt by remember { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf(Phase.LISTENING) }
    var progress by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf<DriftRecognition.Result?>(null) }
    var downloaded by remember { mutableStateOf(false) }

    LaunchedEffect(attempt) {
        phase = Phase.LISTENING; progress = 0f; result = null; downloaded = false
        val file = File(ctx.cacheDir, "drift_recognize.m4a")
        val rec = ClipRecorder(ctx)
        try {
            try {
                rec.start(file)
            } catch (e: Exception) {
                rec.release()
                result = DriftRecognition.Result.Failed(ctx.getString(R.string.drift_recog_mic_busy)); phase = Phase.RESULT
                return@LaunchedEffect
            }
            val t0 = SystemClock.elapsedRealtime()
            while (true) {
                val el = SystemClock.elapsedRealtime() - t0
                progress = (el.toFloat() / CLIP_MS).coerceIn(0f, 1f)
                if (el >= CLIP_MS) break
                delay(100)
            }
            if (!rec.stop()) {
                result = DriftRecognition.Result.Failed(ctx.getString(R.string.drift_recog_mic_busy)); phase = Phase.RESULT
                return@LaunchedEffect
            }
            phase = Phase.SEARCHING
            result = withContext(Dispatchers.IO) { DriftRecognition.recognize(file) }
            phase = Phase.RESULT
        } catch (e: CancellationException) {
            rec.release()
            throw e
        } finally {
            file.delete()
        }
    }

    fun withSong(id: String, block: (Child) -> Unit) {
        scope.launch {
            val c = withContext(Dispatchers.IO) {
                runCatching { App.getSubsonicClientInstance(false).browsingClient.getSong(id).execute().body()?.subsonicResponse?.song }.getOrNull()
            }
            if (c != null) block(c) else hooks.toast(ctx.getString(R.string.drift_recog_song_gone))
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Box(Modifier.fillMaxSize().background(RBg).statusBarsPadding().navigationBarsPadding()) {
            when (phase) {
                Phase.LISTENING, Phase.SEARCHING -> Listening(phase == Phase.SEARCHING, progress)
                Phase.RESULT -> when (val r = result) {
                    is DriftRecognition.Result.Found -> FoundCard(
                        r.song, downloaded,
                        onPlay = { id -> withSong(id) { hooks.play(it); onClose() } },
                        onEnqueue = { id -> withSong(id) { hooks.enqueue(it); onClose() } },
                        onDownload = { id -> hooks.download(id); downloaded = true },
                        onSearch = { hooks.search("${r.song.artist} ${r.song.title}".trim()); onClose() },
                        onRetry = { attempt++ },
                    )
                    DriftRecognition.Result.NotFound -> Message(stringResource(R.string.drift_recog_not_found), stringResource(R.string.drift_recog_not_found_sub), onRetry = { attempt++ })
                    DriftRecognition.Result.Disabled -> Message(stringResource(R.string.drift_recog_disabled), null, onRetry = null)
                    is DriftRecognition.Result.Failed -> Message(stringResource(R.string.drift_recog_error), r.message, onRetry = { attempt++ })
                    null -> Unit
                }
            }
            // Cancel / Close always top left
            Text(stringResource(if (phase == Phase.RESULT) R.string.drift_recog_close else R.string.lite_cancel), color = RHi, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClose)
                    .padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun Listening(searching: Boolean, progress: Float) {
    val t = rememberInfiniteTransition(label = "pulse")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "p")
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
            // three waves that expand and fade, offset by a third
            for (k in 0 until 3) {
                val f = (p + k / 3f) % 1f
                Box(Modifier.size(120.dp).graphicsLayer { scaleX = 1f + f * 1.15f; scaleY = 1f + f * 1.15f; alpha = (1f - f) * 0.45f }
                    .clip(CircleShape).background(RAccent))
            }
            Box(Modifier.size(120.dp).clip(CircleShape).background(RAccent), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_drift_mic), null, colorFilter = ColorFilter.tint(Color.Black), modifier = Modifier.size(52.dp))
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(stringResource(if (searching) R.string.drift_recog_searching else R.string.drift_recog_listening), color = RHi, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        if (!searching) {
            Text(stringResource(R.string.drift_recog_listening_sub), color = RLo, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            Box(Modifier.padding(top = 22.dp).width(180.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
                Box(Modifier.fillMaxWidth(progress).height(4.dp).background(RAccent))
            }
        }
    }
}

@kotlin.OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun FoundCard(
    s: DriftRecognition.Song, downloaded: Boolean,
    onPlay: (String) -> Unit, onEnqueue: (String) -> Unit, onDownload: (Long) -> Unit, onSearch: () -> Unit, onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(240.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1C1C22))) {
            if (s.cover != null) GlideImage(s.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Spacer(Modifier.height(22.dp))
        Text(s.title, color = RHi, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(s.artist, color = RHi, fontSize = 16.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        s.album?.let { Text(it, color = RLo, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
        Text(stringResource(if (s.songId != null) R.string.drift_dl_in_library else R.string.drift_recog_not_in_library), color = if (s.songId != null) RAccent else RLo,
            fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(22.dp))
        when {
            s.songId != null -> {
                Pill(stringResource(R.string.drift_recog_play), primary = true) { onPlay(s.songId) }
                Pill(stringResource(R.string.drift_recog_enqueue)) { onEnqueue(s.songId) }
            }
            s.deezerId != null ->
                if (downloaded) Text(stringResource(R.string.lite_download_started), color = RAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(12.dp))
                else Pill(stringResource(R.string.lite_download), primary = true) { onDownload(s.deezerId) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill(stringResource(R.string.lite_search), onClick = onSearch)
            Pill(stringResource(R.string.lite_retry), onClick = onRetry)
        }
    }
}

@Composable
private fun Message(title: String, sub: String?, onRetry: (() -> Unit)?) {
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, color = RHi, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
        sub?.let { Text(it, color = RLo, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp)) }
        if (onRetry != null) {
            Spacer(Modifier.height(24.dp))
            Pill(stringResource(R.string.lite_retry), primary = true, onClick = onRetry)
        }
    }
}

@Composable
private fun Pill(text: String, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.padding(vertical = 5.dp).clip(RoundedCornerShape(50))
            .background(if (primary) RAccent else Color.Transparent)
            .border(1.dp, if (primary) Color.Transparent else Color(0x66FFFFFF), RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 26.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (primary) Color.Black else RHi, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
}
