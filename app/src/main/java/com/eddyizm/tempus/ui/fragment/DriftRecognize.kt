package com.eddyizm.tempus.ui.fragment

import com.eddyizm.tempus.ui.components.DriftColors
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
import androidx.compose.runtime.mutableLongStateOf
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
 * Drift: "Recognize song". Listening starts on tap and runs on the phone (same engine as Drift Lite): a fingerprint
 * of what was heard is sent after 3 s and twice more while listening, cancel at any time. A match is looked up in
 * the library (play, queue, save to a playlist) and on Deezer (download through Undertow when it is set up).
 * RECORD_AUDIO is requested on first tap, after a short explanation.
 */


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

private val RBg get() = DriftColors.bg
private val RAccent get() = com.eddyizm.tempus.helper.DriftAccent.compose
private val RHi get() = DriftColors.text
private val RLo get() = DriftColors.textLo

@Composable
private fun RecognizeScreen(hooks: RecognizeHooks, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var attempt by remember { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf(Phase.LISTENING) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var level by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf<DriftRecognition.Result?>(null) }
    var downloaded by remember { mutableStateOf(false) }

    LaunchedEffect(attempt) {
        phase = Phase.LISTENING; elapsed = 0; level = 0f; result = null; downloaded = false
        result = try {
            val heard = com.eddyizm.tempus.lite.LiteRecognizer.listen { p -> elapsed = p.elapsedMs; level = p.level }
            if (heard == null) DriftRecognition.Result.NotFound
            else DriftRecognition.Result.Found(withContext(Dispatchers.IO) { DriftRecognition.resolve(heard) })
                .also { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: com.eddyizm.tempus.lite.LiteRecognizer.NoNetwork) {
            DriftRecognition.Result.Failed(ctx.getString(R.string.lite_rec_no_network))
        } catch (e: com.eddyizm.tempus.lite.LiteRecognizer.Busy) {
            DriftRecognition.Result.Failed(ctx.getString(R.string.lite_rec_busy))
        } catch (e: Exception) {
            DriftRecognition.Result.Failed(ctx.getString(R.string.drift_recog_mic_busy))
        }
        phase = Phase.RESULT
    }

    fun withSong(id: String, block: (Child) -> Unit) {
        scope.launch {
            val c = withContext(Dispatchers.IO) {
                runCatching { App.getSubsonicClientInstance(false).browsingClient.getSong(id).execute().body()?.subsonicResponse?.song }.getOrNull()
            }
            if (c != null) block(c) else hooks.toast(ctx.getString(R.string.drift_recog_song_gone))
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(RBg)) {
            when (phase) {
                Phase.LISTENING, Phase.SEARCHING -> com.eddyizm.tempus.lite.RecognizeListening(level, elapsed > 6_000)
                Phase.RESULT -> when (val r = result) {
                    is DriftRecognition.Result.Found -> FoundCard(
                        r.song, downloaded,
                        onPlay = { id -> withSong(id) { hooks.play(it); onClose() } },
                        onEnqueue = { id -> withSong(id) { hooks.enqueue(it); onClose() } },
                        onDownload = { id -> hooks.download(id); downloaded = true },
                        onSearch = { hooks.search("${r.song.artist} ${r.song.title}".trim()); onClose() },
                        onRetry = { attempt++ },
                        toast = hooks.toast,
                    )
                    DriftRecognition.Result.NotFound -> Message(stringResource(R.string.drift_recog_not_found), stringResource(R.string.drift_recog_not_found_sub), onRetry = { attempt++ })
                    DriftRecognition.Result.Disabled -> Message(stringResource(R.string.drift_recog_disabled), null, onRetry = null)
                    is DriftRecognition.Result.Failed -> Message(stringResource(R.string.drift_recog_error), r.message, onRetry = { attempt++ })
                    null -> Unit
                }
            }
            Box(Modifier.statusBarsPadding().padding(8.dp).size(48.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Text("✕", color = RHi, fontSize = 22.sp)
            }
        }
    }
}

@kotlin.OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun FoundCard(
    s: DriftRecognition.Song, downloaded: Boolean,
    onPlay: (String) -> Unit, onEnqueue: (String) -> Unit, onDownload: (Long) -> Unit, onSearch: () -> Unit, onRetry: () -> Unit,
    toast: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var playlists by remember { mutableStateOf<List<com.eddyizm.tempus.subsonic.models.Playlist>>(emptyList()) }
    val added = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val repo = remember { com.eddyizm.tempus.repository.PlaylistRepository() }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    fun reload() {
        Thread {
            val l = runCatching { App.getSubsonicClientInstance(false).playlistClient.getPlaylists().execute().body()?.subsonicResponse?.playlists?.playlists }.getOrNull()
            if (l != null) main.post { playlists = l }
        }.start()
    }
    LaunchedEffect(s.songId) { if (s.songId != null) reload() }
    fun say(msg: String) { main.post { toast(msg) } }

    if (creating && s.songId != null) com.eddyizm.tempus.lite.NameDialog(stringResource(R.string.lite_new_playlist), name, { name = it },
        confirm = stringResource(R.string.lite_create), onDismiss = { creating = false }) {
        val n = name.trim().ifEmpty { ctx.getString(R.string.lite_default_playlist_name) }
        repo.createPlaylist(null, n, arrayListOf(s.songId), object : com.eddyizm.tempus.repository.PlaylistRepository.PlaylistActionCallback {
            override fun onSuccess() { say(ctx.getString(R.string.lite_playlist_created, n)); reload() }
            override fun onFailure() { say(ctx.getString(R.string.drift_recog_error)) }
        })
        creating = false; name = ""
    }

    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 28.dp, end = 28.dp, top = 72.dp, bottom = 40.dp)) {
        item {
            Box(Modifier.statusBarsPadding().size(240.dp).clip(RoundedCornerShape(10.dp)).background(DriftColors.card)) {
                if (s.cover != null) GlideImage(s.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Spacer(Modifier.height(22.dp))
            Text(s.title, color = RHi, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(s.artist, color = RHi, fontSize = 16.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            s.album?.let { Text(it, color = RLo, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
            Text(stringResource(if (s.songId != null) R.string.drift_dl_in_library else R.string.drift_recog_not_in_library), color = if (s.songId != null) com.eddyizm.tempus.helper.DriftAccent.composeText else RLo,
                fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.height(18.dp))
            when {
                s.songId != null -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill(stringResource(R.string.drift_recog_play), primary = true) { onPlay(s.songId) }
                    Pill(stringResource(R.string.drift_recog_enqueue)) { onEnqueue(s.songId) }
                }
                s.deezerId != null && com.eddyizm.tempus.helper.Undertow.enabled ->
                    if (downloaded) Text(stringResource(R.string.lite_download_started), color = com.eddyizm.tempus.helper.DriftAccent.composeText, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(12.dp))
                    else Pill(stringResource(R.string.lite_download), primary = true) { onDownload(s.deezerId) }
            }
            if (s.songId != null) {
                Spacer(Modifier.height(22.dp))
                Text(stringResource(R.string.lite_rec_save_where), color = RHi, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { creating = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(RAccent), contentAlignment = Alignment.Center) {
                        Text("+", color = Color.Black, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(stringResource(R.string.lite_new_playlist), color = RHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 14.dp))
                }
            }
        }
        if (s.songId != null) items(playlists.size) { i ->
            val p = playlists[i]
            val isIn = p.id in added
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = !isIn) {
                added.add(p.id)
                repo.addSongToPlaylist(p.id, arrayListOf(s.songId), null, object : com.eddyizm.tempus.repository.PlaylistRepository.AddToPlaylistCallback {
                    override fun onSuccess() = say(ctx.getString(R.string.lite_added_to, p.name ?: ""))
                    override fun onFailure() { main.post { added.remove(p.id) }; say(ctx.getString(R.string.drift_recog_error)) }
                    override fun onAllSkipped() = say(ctx.getString(R.string.lite_already_in, p.name ?: ""))
                })
            }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(DriftColors.card))
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(p.name ?: "", color = RHi, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(androidx.compose.ui.res.pluralStringResource(R.plurals.lite_tracks, p.songCount, p.songCount), color = RLo, fontSize = 13.sp)
                }
                if (isIn) Text("✓", color = com.eddyizm.tempus.helper.DriftAccent.composeText, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 6.dp))
            }
        }
        item {
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill(stringResource(R.string.lite_search), onClick = onSearch)
                Pill(stringResource(R.string.lite_rec_again), onClick = onRetry)
            }
            Spacer(Modifier.navigationBarsPadding())
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
            .border(1.dp, if (primary) Color.Transparent else DriftColors.stroke, RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 26.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (primary) Color.Black else RHi, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
}
