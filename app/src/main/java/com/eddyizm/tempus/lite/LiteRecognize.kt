package com.eddyizm.tempus.lite

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Drift Lite: "What's playing?". Tapping the button starts listening at once: full-screen, live sound bars,
 * cancel at any time. A match shows the song with Play / Like and asks which playlist to save it in.
 */

/** Microphone button that runs the whole flow (permission included). */
@Composable
fun RecognizeButton(tint: Color = LHi, size: Int = 44) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val onResult = { granted: Boolean ->
        if (granted) open = true
        else {
            val act = ctx.activity()
            val blocked = act != null && !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(act, Manifest.permission.RECORD_AUDIO)
            Toast.makeText(ctx, ctx.getString(if (blocked) R.string.lite_rec_mic_blocked else R.string.lite_rec_mic_denied), Toast.LENGTH_LONG).show()
            if (blocked) ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
        }
    }
    // permission request through the activity's result registry (no activity-compose dependency)
    val key = remember { "lite_rec_mic_" + java.util.UUID.randomUUID() }
    var launcher by remember { mutableStateOf<androidx.activity.result.ActivityResultLauncher<String>?>(null) }
    androidx.compose.runtime.DisposableEffect(key) {
        val reg = (ctx.activity() as? androidx.activity.ComponentActivity)?.activityResultRegistry
            ?.register(key, ActivityResultContracts.RequestPermission()) { onResult(it) }
        launcher = reg
        onDispose { reg?.unregister() }
    }
    Box(Modifier.size(size.dp).clip(CircleShape).clickable {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) open = true
        else launcher?.launch(Manifest.permission.RECORD_AUDIO)
    }, contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_drift_mic), stringResource(R.string.lite_rec_button), colorFilter = ColorFilter.tint(tint), modifier = Modifier.size(24.dp))
    }
    if (open) RecognizeScreen(onClose = { open = false })
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

private sealed class RState {
    object Listening : RState()
    data class Found(val song: LiteRecognizer.Song, val id: String) : RState()
    object NotFound : RState()
    data class Error(val message: Int) : RState()
}

@Composable
private fun RecognizeScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<RState>(RState.Listening) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var level by remember { mutableFloatStateOf(0f) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(attempt) {
        state = RState.Listening; elapsed = 0; level = 0f; searching = false
        state = try {
            val song = LiteRecognizer.listen { p -> elapsed = p.elapsedMs; level = p.level; searching = p.searching }
            if (song == null) RState.NotFound
            else {
                val id = withContext(Dispatchers.IO) { register(song) }
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                RState.Found(song, id)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LiteRecognizer.NoNetwork) {
            RState.Error(R.string.lite_rec_no_network)
        } catch (e: LiteRecognizer.Busy) {
            RState.Error(R.string.lite_rec_busy)
        } catch (e: Exception) {
            RState.Error(R.string.lite_rec_mic_busy)
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(LBg)) {
            AnimatedContent(state, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) },
                contentKey = { it::class }, label = "rec", modifier = Modifier.fillMaxSize()) { s ->
                when (s) {
                    RState.Listening -> Listening(level, elapsed > 6_000)
                    is RState.Found -> Found(s.song, s.id, onPlay = { playNow(ctx, s.id); onClose() }, onAgain = { attempt++ })
                    RState.NotFound -> Message(R.string.lite_rec_not_found, R.string.lite_rec_not_found_sub) { attempt++ }
                    is RState.Error -> Message(R.string.lite_rec_error, s.message) { attempt++ }
                }
            }
            Box(Modifier.statusBarsPadding().padding(8.dp).size(48.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Text("✕", color = LHi, fontSize = 22.sp)
            }
        }
    }
}

/** Saves the match in the song index, using catalog data (exact length, cover) when the same song is found there. */
private fun register(s: LiteRecognizer.Song): String {
    val hit = LiteCatalog.searchTracks("${s.artist} ${s.title}", 5).firstOrNull { t ->
        LiteMatch.norm(t.title) == LiteMatch.norm(s.title) &&
            LiteMatch.norm(t.artist).split(' ').any { it.length > 1 && it in LiteMatch.norm(s.artist) }
    }
    return if (hit != null) LiteTracks.register(hit.title, hit.artist, hit.duration, hit.cover ?: s.cover)
    else LiteTracks.register(s.title, s.artist, 0, s.cover)
}

private fun playNow(ctx: Context, id: String) {
    val c = LiteTracks.child(id) ?: return
    val f = MediaBrowser.Builder(ctx, SessionToken(ctx, ComponentName(ctx, MediaService::class.java))).buildAsync()
    f.addListener({
        if (runCatching { f.get() }.isSuccess) {
            MediaManager.startQueue(f, listOf(c), 0)
            (ctx.activity() as? MainActivity)?.setBottomSheetInPeek(true)
        }
        Handler(Looper.getMainLooper()).postDelayed({ MediaBrowser.releaseFuture(f) }, 5_000)
    }, ContextCompat.getMainExecutor(ctx))
}

@Composable
private fun Listening(level: Float, searching: Boolean) {
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

@kotlin.OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun Found(s: LiteRecognizer.Song, id: String, onPlay: () -> Unit, onAgain: () -> Unit) {
    val ctx = LocalContext.current
    var liked by remember { mutableStateOf(LiteTracks.isLiked(id)) }
    var playlists by remember { mutableStateOf(LitePlaylists.all()) }
    val added = remember { mutableStateListOf<String>().apply { addAll(playlists.filter { id in it.tracks }.map { it.id }) } }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    fun toast(text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()

    if (creating) NameDialog(stringResource(R.string.lite_new_playlist), name, { name = it }, confirm = stringResource(R.string.lite_create), onDismiss = { creating = false }) {
        val pid = LitePlaylists.create(name, listOf(id))
        toast(ctx.getString(R.string.lite_playlist_created, name.trim().ifEmpty { ctx.getString(R.string.lite_default_playlist_name) }))
        playlists = LitePlaylists.all(); added.add(pid); creating = false; name = ""
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 72.dp, bottom = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            Box(Modifier.statusBarsPadding().size(250.dp).clip(RoundedCornerShape(12.dp)).background(LCard)) {
                if (s.cover != null) GlideImage(s.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Spacer(Modifier.height(20.dp))
            Text(s.title, color = LHi, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(s.artist, color = LHi, fontSize = 17.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            s.album?.let { Text(it, color = LLo, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.clip(RoundedCornerShape(50)).background(LAcc).clickable(onClick = onPlay).padding(horizontal = 34.dp, vertical = 13.dp)) {
                    Text(stringResource(R.string.lite_play_button), color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.size(48.dp).clip(CircleShape).border(1.dp, Color(0x55FFFFFF), CircleShape).clickable {
                    liked = !liked
                    LiteTracks.setLiked(id, liked)
                    com.eddyizm.tempus.util.FavoriteRegistry.set(com.eddyizm.tempus.util.FavoriteRegistry.Kind.SONG, id, liked)
                    toast(ctx.getString(if (liked) R.string.lite_liked_done else R.string.lite_unliked))
                }, contentAlignment = Alignment.Center) {
                    Text(if (liked) "♥" else "♡", color = if (liked) LAcc else LHi, fontSize = 22.sp)
                }
            }
            Spacer(Modifier.height(30.dp))
            Text(stringResource(R.string.lite_rec_save_where), color = LHi, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { creating = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(LAcc), contentAlignment = Alignment.Center) {
                    Text("+", color = Color.Black, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Text(stringResource(R.string.lite_new_playlist), color = LHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 14.dp))
            }
        }
        items(playlists, key = { it.id }) { p ->
            val isIn = p.id in added
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = !isIn) {
                LitePlaylists.add(p.id, listOf(id)); added.add(p.id)
                toast(ctx.getString(R.string.lite_added_to, p.name))
            }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                val cover = p.tracks.firstNotNullOfOrNull { LiteTracks.get(it)?.cover }
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(LCard)) {
                    if (cover != null) GlideImage(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(p.name, color = LHi, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(pluralStringResource(R.plurals.lite_tracks, p.tracks.size, p.tracks.size), color = LLo, fontSize = 13.sp)
                }
                if (isIn) Text("✓", color = LAcc, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 6.dp))
            }
        }
        item {
            Spacer(Modifier.height(26.dp))
            Box(Modifier.clip(RoundedCornerShape(50)).border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(50)).clickable(onClick = onAgain)
                .padding(horizontal = 26.dp, vertical = 11.dp)) {
                Text(stringResource(R.string.lite_rec_again), color = LHi, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun Message(title: Int, sub: Int, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(72.dp).clip(CircleShape).background(LCard), contentAlignment = Alignment.Center) {
            Text("?", color = LHi, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(title), color = LHi, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
        Text(stringResource(sub), color = LLo, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().padding(bottom = 24.dp).clip(RoundedCornerShape(50)).background(LAcc).clickable(onClick = onRetry).padding(vertical = 15.dp),
            contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.lite_retry), color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}
