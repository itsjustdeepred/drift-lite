package com.eddyizm.tempus.ui.fragment

import android.content.ComponentName
import android.graphics.Color as AColor
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.DrawableRes
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.eddyizm.tempus.glide.CustomGlideRequest
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import com.eddyizm.tempus.R
import com.eddyizm.tempus.helper.DriftColors
import com.eddyizm.tempus.lan.LanRemoteSession
import com.eddyizm.tempus.service.MediaService
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.subsonic.models.LyricsList
import com.eddyizm.tempus.util.MusicUtil
import com.eddyizm.tempus.util.Preferences
import com.eddyizm.tempus.viewmodel.PlayerBottomSheetViewModel
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Drift: Spotify-style song lyrics. Solid card in the cover art color, large lines,
 * the current one in full white, tap on a line = seek to that point.
 * Lives on the second page of the cover pager (same entry points as before).
 */
@OptIn(UnstableApi::class)
class PlayerLyricsFragment : Fragment() {
    // Drift: the player keeps its dark look also when the app is in the light theme
    override fun onGetLayoutInflater(savedInstanceState: android.os.Bundle?): android.view.LayoutInflater =
        com.eddyizm.tempus.ui.components.DriftColors.playerInflater(super.onGetLayoutInflater(savedInstanceState))


    private lateinit var viewModel: PlayerBottomSheetViewModel
    private var browserFuture: ListenableFuture<MediaBrowser>? = null
    private var browser: MediaBrowser? = null

    // Drift: state read by Compose, written by the LiveData and the position ticker
    private val lyricsList = mutableStateOf<LyricsList?>(null)
    private val plainLyrics = mutableStateOf<String?>(null)
    private val description = mutableStateOf<String?>(null)
    private val media = mutableStateOf<Child?>(null)
    private val cached = mutableStateOf(false)
    private val playing = mutableStateOf(false)
    private val immersive = mutableStateOf(false)
    private val coverColor = mutableIntStateOf(DriftColors.current)
    private val position = mutableLongStateOf(0L)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        viewModel = ViewModelProvider(requireActivity())[PlayerBottomSheetViewModel::class.java]
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val content = remember(lyricsList.value, plainLyrics.value, description.value) {
                    buildLyricsContent(lyricsList.value, plainLyrics.value, description.value)
                }
                LyricsScreen(
                    color = Color(coverColor.intValue),
                    title = media.value?.title,
                    artist = media.value?.artist,
                    content = content,
                    positionMs = { position.longValue },
                    onImmersive = { immersive.value = true },
                    onSeek = ::seekTo,
                )
                // Drift: immersive mode, full screen over the app
                if (immersive.value) {
                    ImmersiveLyrics(
                        color = Color(coverColor.intValue),
                        coverArtId = media.value?.coverArtId,
                        title = media.value?.title,
                        artist = media.value?.artist,
                        content = content,
                        positionMs = { position.longValue },
                        durationMs = (media.value?.duration ?: 0) * 1000L,
                        playing = playing.value,
                        onPlayPause = ::playPause,
                        onSeek = ::seekTo,
                        onClose = { immersive.value = false },
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val owner = viewLifecycleOwner
        // Drift: the LiveData update the view even when the lyrics arrive later (Undertow in background)
        viewModel.getLiveLyrics().observe(owner) { plainLyrics.value = it }
        viewModel.getLiveLyricsList().observe(owner) { lyricsList.value = it }
        viewModel.getLiveDescription().observe(owner) { description.value = it }
        viewModel.getLiveMedia().observe(owner) { media.value = it }
        viewModel.getLyricsCachedState().observe(owner) { cached.value = it == true }

        // Drift: position read only while the page is visible (RESUMED); frequent while playing, slow when paused
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    coverColor.intValue = DriftColors.current
                    val now = readPosition()
                    playing.value = now
                    delay(if (now) 150L else 700L)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val ctx = requireContext()
        val future = MediaBrowser.Builder(ctx, SessionToken(ctx, ComponentName(ctx, MediaService::class.java))).buildAsync()
        browserFuture = future
        future.addListener({
            browser = try { future.get() } catch (e: Exception) { null }
        }, MoreExecutors.directExecutor())
    }

    override fun onResume() {
        super.onResume()
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        if (!Preferences.isDisplayAlwaysOn()) {
            requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onStop() {
        browser = null
        browserFuture?.let { MediaBrowser.releaseFuture(it) }
        browserFuture = null
        super.onStop()
    }

    /** Drift: updates the position; returns true if playing. */
    private fun readPosition(): Boolean {
        if (LanRemoteSession.isActive()) {
            val s = LanRemoteSession.current()
            position.longValue = s.positionAt(SystemClock.elapsedRealtime())
            return s.playing
        }
        val b = browser ?: return false
        position.longValue = b.currentPosition
        return b.isPlaying
    }

    private fun seekTo(ms: Long) {
        // Drift: 1 ms after the start, so the line is already the right one on arrival
        val target = ms.coerceAtLeast(0) + 1
        if (LanRemoteSession.isActive()) {
            LanRemoteSession.command("seek", target)
            if (!LanRemoteSession.current().playing) LanRemoteSession.command("play")
        } else {
            val b = browser ?: return
            b.seekTo(target)
            if (!b.isPlaying) b.play()
        }
        position.longValue = target
    }

    private fun playPause() {
        if (LanRemoteSession.isActive()) {
            LanRemoteSession.command(if (LanRemoteSession.current().playing) "pause" else "play")
        } else {
            val b = browser ?: return
            if (b.isPlaying) b.pause() else b.play()
        }
        playing.value = !playing.value
    }
}

// ---------------------------------------------------------------------------------------------
// Drift: lyrics model

private class LyricLine(val startMs: Long?, val text: String)

/** [synced]: timed lines; [unsyncedNote]: shows the "lyrics not synced" note (not for descriptions). */
private class LyricsContent(val lines: List<LyricLine>, val synced: Boolean, val unsyncedNote: Boolean)

private val LRC_TIME = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
private val LRC_OFFSET = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

private fun buildLyricsContent(list: LyricsList?, plain: String?, description: String?): LyricsContent? {
    val all = list?.structuredLyrics.orEmpty().filter { !it.line.isNullOrEmpty() }
    // Drift: if the server sends more than one, the synced one is preferred
    val sl = all.firstOrNull { it.synced } ?: all.firstOrNull()
    if (sl != null) {
        val synced = sl.synced && sl.line!!.any { it.start != null }
        val lines = sl.line!!.map { l ->
            // OpenSubsonic: positive offset = lyrics ahead
            val start = if (synced) l.start?.let { (it.toLong() - sl.offset).coerceAtLeast(0) } else null
            LyricLine(start, MusicUtil.getReadableLyrics(runCatching { l.value }.getOrNull()).trim())
        }
        return if (synced) LyricsContent(lines, true, false)
        else LyricsContent(compactPlain(lines.map { it.text }), false, true)
    }
    if (!plain.isNullOrBlank()) {
        val text = MusicUtil.getReadableLyrics(plain)
        return parseLrc(text) ?: LyricsContent(compactPlain(text.lines()), false, true)
    }
    if (!description.isNullOrBlank()) {
        return LyricsContent(compactPlain(MusicUtil.getReadableLyrics(description).lines()), false, false)
    }
    return null
}

/** Drift: "plain" lyrics that are actually LRC (from cache or server) become synced, with [offset:]. */
private fun parseLrc(text: String): LyricsContent? {
    val offset = LRC_OFFSET.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    val out = mutableListOf<LyricLine>()
    for (raw in text.lines()) {
        val stamps = LRC_TIME.findAll(raw).toList()
        if (stamps.isEmpty() || stamps.first().range.first != 0) continue
        val body = raw.substring(stamps.last().range.last + 1).trim()
        for (m in stamps) {
            val frac = m.groupValues[3]
            val fracMs = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.toLong() }
            val ms = m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + fracMs
            out += LyricLine((ms - offset).coerceAtLeast(0), body)
        }
    }
    if (out.size < 3) return null
    return LyricsContent(out.sortedBy { it.startMs }, true, false)
}

/** Drift: multiple blank lines collapsed to one, no blanks at the start or end. */
private fun compactPlain(raw: List<String>): List<LyricLine> {
    val out = mutableListOf<LyricLine>()
    for (s in raw.map { it.trim() }) {
        if (s.isEmpty() && (out.isEmpty() || out.last().text.isEmpty())) continue
        out += LyricLine(null, s)
    }
    while (out.isNotEmpty() && out.last().text.isEmpty()) out.removeAt(out.lastIndex)
    return out
}

// ---------------------------------------------------------------------------------------------
// Drift: view

private val PastLine = Color.White.copy(alpha = 0.55f)
private val NextLine = Color.Black.copy(alpha = 0.50f)

/** Drift: the extracted color, kept dark enough for white text. */
private fun lyricsBackground(c: Color): Color {
    val hsv = FloatArray(3)
    AColor.colorToHSV(c.toArgb(), hsv)
    hsv[2] = hsv[2].coerceAtMost(0.40f)
    return Color(AColor.HSVToColor(hsv))
}

@Composable
private fun LyricsScreen(
    color: Color,
    title: String?,
    artist: String?,
    content: LyricsContent?,
    positionMs: () -> Long,
    onImmersive: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val bg by animateColorAsState(lyricsBackground(color), tween(700), label = "lyricsBg")

    Box(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp).clip(RoundedCornerShape(16.dp)).background(bg)) {
        Column(Modifier.fillMaxSize()) {
            // Drift: a single, clear button: the rest (scrolling, offline saving) happens on its own
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title.orEmpty(), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!artist.isNullOrBlank()) {
                        Text(artist, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (content != null) {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.16f)).clickable(onClick = onImmersive)
                            .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(painterResource(R.drawable.drift_ic_fullscreen), null, colorFilter = ColorFilter.tint(Color.White), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.drift_lyrics_fullscreen), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (content == null) EmptyLyrics()
                else LyricsBody(content, bg, positionMs, true, onSeek)
            }
        }
    }
}

@Composable
private fun HeaderButton(@DrawableRes icon: Int, description: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Image(painterResource(icon), description, colorFilter = ColorFilter.tint(Color.White), modifier = Modifier.size(24.dp))
    }
}

/**
 * Drift: full-screen lyrics. Blurred cover behind, system bars hidden, screen kept on;
 * the controls fade after a few seconds and come back on the first touch.
 */
@kotlin.OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun ImmersiveLyrics(
    color: Color, coverArtId: String?, title: String?, artist: String?, content: LyricsContent?,
    positionMs: () -> Long, durationMs: Long, playing: Boolean,
    onPlayPause: () -> Unit, onSeek: (Long) -> Unit, onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            if (window != null) {
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                window.setWindowAnimations(android.R.style.Animation_Dialog)
                WindowCompat.getInsetsController(window, view).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
            onDispose { }
        }

        val bg by animateColorAsState(lyricsBackground(color), tween(900), label = "immersiveBg")
        var controls by remember { mutableStateOf(true) }
        var touch by remember { mutableLongStateOf(0L) }
        LaunchedEffect(touch, playing) {
            controls = true
            if (playing) { delay(3500); controls = false }
        }

        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .pointerInput(Unit) {
                    // Drift: every touch (lines included) brings the controls back, without stealing the tap
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            touch = SystemClock.uptimeMillis()
                        }
                    }
                },
        ) {
            if (!coverArtId.isNullOrEmpty()) {
                GlideImage(CustomGlideRequest.createUrl(coverArtId, Preferences.getImageSize()), null,
                    Modifier.fillMaxSize().blur(70.dp), contentScale = ContentScale.Crop, alpha = 0.6f)
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(bg.copy(alpha = 0.65f), bg.copy(alpha = 0.85f), Color.Black.copy(alpha = 0.9f)))))

            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (content == null) EmptyLyrics()
                    else LyricsBody(content, Color.Transparent, positionMs, true, onSeek, immersive = true)
                }
            }

            // Drift: controls at the top (close) and bottom (song, progress, play)
            AnimatedVisibility(controls, Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(8.dp), enter = fadeIn(), exit = fadeOut()) {
                HeaderButton(R.drawable.ic_close, stringResource(R.string.drift_lyrics_exit_fullscreen), Modifier.background(Color.Black.copy(alpha = 0.3f), CircleShape), onClose)
            }
            AnimatedVisibility(controls, Modifier.align(Alignment.BottomCenter), enter = fadeIn() + slideInVertically { it / 3 }, exit = fadeOut() + slideOutVertically { it / 3 }) {
                Column(
                    Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                        .systemBarsPadding().padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(title.orEmpty(), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!artist.isNullOrBlank()) Text(artist, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box(Modifier.size(56.dp).clip(CircleShape).background(Color.White).clickable(onClick = onPlayPause), contentAlignment = Alignment.Center) {
                            Image(painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play), stringResource(if (playing) R.string.drift_pause else R.string.drift_play),
                                colorFilter = ColorFilter.tint(Color.Black), modifier = Modifier.size(28.dp))
                        }
                    }
                    if (durationMs > 0) {
                        Spacer(Modifier.height(14.dp))
                        ImmersiveProgress(positionMs, durationMs)
                    }
                }
            }
        }
    }
}

@Composable
private fun ImmersiveProgress(positionMs: () -> Long, durationMs: Long) {
    // Drift: only the bar is redrawn, not the whole screen
    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.25f))
        .drawWithContent {
            val f = (positionMs().toFloat() / durationMs).coerceIn(0f, 1f)
            drawRect(Color.White, size = size.copy(width = size.width * f))
        })
}

@Composable
private fun EmptyLyrics() {
    // Drift: on song change the lyrics arrive a moment later: the message waits so it doesn't flash
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(1200); show = true }
    AnimatedVisibility(show, Modifier.fillMaxSize(), enter = fadeIn(tween(400)), exit = fadeOut()) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.drift_ic_lyrics), null, colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.6f)), modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.drift_lyrics_none), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LyricsBody(content: LyricsContent, bg: Color, positionMs: () -> Long, syncLock: Boolean, onSeek: (Long) -> Unit, immersive: Boolean = false) {
    val listState = rememberLazyListState()
    val lead = if (content.unsyncedNote) 1 else 0  // the note takes the first item

    // Drift: the current line changes a few times a minute; only it triggers recomposition
    val current by remember(content) {
        derivedStateOf {
            if (!content.synced) -1 else {
                val pos = positionMs()
                var idx = -1
                for (i in content.lines.indices) {
                    val s = content.lines[i].startMs ?: continue
                    if (s > pos) break
                    idx = i
                }
                idx
            }
        }
    }

    // Drift: manual scroll = auto-scroll paused; resumes on its own after 5 s idle
    var userPaused by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var lastTouch by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            when (it) {
                is DragInteraction.Start -> { dragging = true; userPaused = true; lastTouch = SystemClock.uptimeMillis() }
                is DragInteraction.Stop, is DragInteraction.Cancel -> { dragging = false; lastTouch = SystemClock.uptimeMillis() }
            }
        }
    }
    LaunchedEffect(userPaused, lastTouch) {
        if (!userPaused) return@LaunchedEffect
        delay(5000)
        snapshotFlow { !dragging && !listState.isScrollInProgress }.first { it }
        userPaused = false
    }

    // Drift: new song, start again from the top
    LaunchedEffect(content) {
        userPaused = false
        listState.scrollToItem(0)
    }

    val autoScroll = content.synced && syncLock && !userPaused
    LaunchedEffect(current, autoScroll) {
        if (!autoScroll || current < 0) return@LaunchedEffect
        val index = current + lead
        // Drift: on first open, wait until the list has a height
        snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
        val info = listState.layoutInfo
        val target = (info.viewportSize.height * if (immersive) 0.36f else 0.28f)
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        if (item != null) {
            listState.animateScrollBy((item.offset - info.viewportStartOffset) - target, tween(650, easing = FastOutSlowInEasing))
        } else {
            listState.scrollToItem(index)
            listState.scrollBy(-target)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 340.dp
        val big = immersive && !narrow
        val bottomPad = maxHeight * 0.6f
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = if (immersive) maxHeight * 0.3f else 14.dp, bottom = if (content.synced) bottomPad else if (immersive) 160.dp else 40.dp),
        ) {
            if (content.unsyncedNote) {
                item(key = "note") {
                    Text(stringResource(R.string.drift_lyrics_unsynced), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp))
                }
            }
            itemsIndexed(content.lines, key = { i, _ -> i }) { i, line ->
                LyricRow(line, i, current, content.synced, narrow, big, onSeek = {
                    userPaused = false
                    onSeek(it)
                })
            }
        }

        // Drift: top and bottom fades, like the Spotify card
        if (!immersive) Box(Modifier.fillMaxWidth().height(18.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(bg, Color.Transparent))))
        if (!immersive) Box(Modifier.fillMaxWidth().height(40.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, bg))))

        AnimatedVisibility(
            visible = content.synced && syncLock && userPaused && current >= 0,
            modifier = Modifier.align(if (immersive) Alignment.TopCenter else Alignment.BottomCenter).padding(top = if (immersive) 56.dp else 0.dp, bottom = 14.dp),
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(Color.White).clickable { userPaused = false }
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            ) {
                Text(stringResource(R.string.drift_lyrics_back_to_line), color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun LyricRow(line: LyricLine, index: Int, current: Int, synced: Boolean, narrow: Boolean, big: Boolean, onSeek: (Long) -> Unit) {
    val target = when {
        !synced -> Color.White
        index == current -> Color.White
        index < current -> PastLine
        big -> Color.White.copy(alpha = 0.32f)
        else -> NextLine
    }
    val color by animateColorAsState(target, tween(300), label = "lyricLine")
    val size = if (big) 32.sp else if (narrow) 22.sp else 26.sp
    val text = if (synced && line.text.isEmpty()) "♪" else line.text
    val start = line.startMs
    var mod = Modifier.fillMaxWidth()
    if (synced && start != null) mod = mod.clickable(interactionSource = null, indication = null) { onSeek(start) }
    if (!synced && text.isEmpty()) {
        Spacer(Modifier.height(14.dp))
        return
    }
    Text(
        text,
        color = color,
        fontSize = size,
        lineHeight = if (big) 40.sp else if (narrow) 28.sp else 32.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.2).sp,
        modifier = mod.padding(horizontal = if (big) 28.dp else 20.dp, vertical = if (synced) (if (big) 10.dp else 7.dp) else 3.dp),
    )
}
