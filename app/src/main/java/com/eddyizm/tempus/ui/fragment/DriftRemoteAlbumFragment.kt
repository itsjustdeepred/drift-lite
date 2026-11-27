package com.eddyizm.tempus.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.eddyizm.tempus.R
import com.eddyizm.tempus.helper.DriftMixes
import com.eddyizm.tempus.ui.activity.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val RBg = Color(0xFF0B0B0F)
private val RAcc get() = com.eddyizm.tempus.helper.DriftAccent.compose
private val RHi = Color.White
private val RLo = Color(0xFFA7A7B3)

/**
 * Drift: page of an album you don't own (from Deezer): cover, tracks, "Download album" (Lidarr)
 * and "Download" on a single track (YouTube Music right away, then FLAC from Soulseek).
 * Arguments: [ARG_ID] (deezer id) and, to show the header right away, title/artist/cover.
 */
class DriftRemoteAlbumFragment : Fragment() {
    companion object {
        const val ARG_ID = "drift_deezer_album_id"
        const val ARG_TITLE = "drift_album_title"
        const val ARG_ARTIST = "drift_album_artist"
        const val ARG_COVER = "drift_album_cover"

        @JvmStatic
        fun args(id: Long, title: String?, artist: String?, cover: String?) = Bundle().apply {
            putLong(ARG_ID, id); putString(ARG_TITLE, title); putString(ARG_ARTIST, artist); putString(ARG_COVER, cover)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val a = requireArguments()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                RemoteAlbumScreen(
                    a.getLong(ARG_ID), a.getString(ARG_TITLE), a.getString(ARG_ARTIST), a.getString(ARG_COVER),
                    toast = { Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show() },
                    play = { songs, i ->
                        browser?.let { b ->
                            com.eddyizm.tempus.service.MediaManager.startQueue(b, songs, i)
                            (activity as? MainActivity)?.setBottomSheetInPeek(true)
                        }
                    },
                    back = { NavHostFragment.findNavController(this@DriftRemoteAlbumFragment).navigateUp() },
                )
            }
        }
    }

    private var browser: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaBrowser>? = null

    override fun onStart() {
        super.onStart()
        (activity as? MainActivity)?.setBottomNavigationBarVisibility(true)
        browser = androidx.media3.session.MediaBrowser.Builder(
            requireContext(),
            androidx.media3.session.SessionToken(requireContext(), android.content.ComponentName(requireContext(), com.eddyizm.tempus.service.MediaService::class.java))
        ).buildAsync()
    }

    override fun onStop() {
        browser?.let { androidx.media3.session.MediaBrowser.releaseFuture(it) }
        super.onStop()
    }
}

@kotlin.OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun RemoteAlbumScreen(id: Long, title0: String?, artist0: String?, cover0: String?, toast: (String) -> Unit,
                              play: (List<com.eddyizm.tempus.subsonic.models.Child>, Int) -> Unit, back: () -> Unit) {
    var album by remember { mutableStateOf<DriftMixes.RemoteAlbum?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var albumAsked by remember { mutableStateOf(false) }
    val trackAsked = remember { mutableStateMapOf<Long, Boolean>() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    // download status: every 3 s while something is in progress, otherwise every 15 s
    val jobs = remember { mutableStateMapOf<Long, DriftMixes.Job>() }
    val retrying = remember { mutableStateMapOf<Long, Boolean>() }
    // Tracks already in the library (including just downloaded ones): they play from here
    val inLibrary = remember { mutableStateMapOf<Long, com.eddyizm.tempus.subsonic.models.Child>() }
    val doneCount = jobs.values.count { it.state == "done" }
    LaunchedEffect(album, doneCount) {
        val a = album ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            a.tracks.filter { it.id !in inLibrary }.forEach { t ->
                runCatching {
                    com.eddyizm.tempus.App.getSubsonicClientInstance(false).searchingClient.search3("${t.artist} ${t.title}", 10, 0, 0, 0, 0, 0)
                        .execute().body()?.subsonicResponse?.searchResult3?.songs.orEmpty()
                        .firstOrNull { libNorm(it.title) == libNorm(t.title) && libNorm(it.artist).contains(libNorm(t.artist).substringBefore(' ')) }
                }.getOrNull()?.let { inLibrary[t.id] = it }
            }
        }
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        // only while the screen is in the foreground: no requests in the background
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            var askedAt = 0L
            while (true) {
                val all = withContext(Dispatchers.IO) { DriftMixes.jobs() }
                all.groupBy { it.deezerId }.forEach { (k, v) -> jobs[k] = v.first() }
                if (albumAsked || trackAsked.isNotEmpty()) { if (askedAt == 0L) askedAt = System.currentTimeMillis() }
                val justAsked = askedAt != 0L && System.currentTimeMillis() - askedAt < 30_000
                kotlinx.coroutines.delay(if (jobs.values.any { it.active } || justAsked || retrying.isNotEmpty()) 3000 else 15000)
            }
        }
    }

    LaunchedEffect(id) {
        val r = withContext(Dispatchers.IO) { DriftMixes.album(id) }
        if (r == null) error = withContext(Dispatchers.IO) { com.eddyizm.tempus.helper.Undertow.diagnose(com.eddyizm.tempus.R.string.drift_what_album) } else album = r
    }
    val title = album?.title ?: title0 ?: ""
    val artist = album?.artist ?: artist0 ?: ""
    val cover = album?.cover ?: cover0

    LazyColumn(Modifier.fillMaxSize().background(RBg), contentPadding = PaddingValues(bottom = 180.dp)) {
        item {
            Box(Modifier.fillMaxWidth()) {
                if (cover != null) GlideImage(cover, null, Modifier.matchParentSize().blur(60.dp), contentScale = ContentScale.Crop, alpha = 0.55f)
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, RBg))))
                Column(Modifier.statusBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("←", color = RHi, fontSize = 26.sp, modifier = Modifier.align(Alignment.Start).clip(CircleShape).clickable(onClick = back).padding(8.dp))
                    Box(Modifier.size(230.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF1C1C22))) {
                        if (cover != null) GlideImage(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(title, color = RHi, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.fillMaxWidth())
                    Text(artist, color = RHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    album?.let { a ->
                        Text(listOfNotNull(a.type?.replaceFirstChar { it.uppercase() }, a.date.take(4).ifBlank { null }, pluralStringResource(R.plurals.lite_tracks, a.tracks.size, a.tracks.size),
                            a.label).joinToString(" · "), color = RLo, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                    val aj = jobs[id]
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(aj?.let { DriftMixes.statusText(it) } ?: if (album?.tracks?.isNotEmpty() == true && album!!.tracks.all { it.id in inLibrary }) stringResource(R.string.drift_remote_in_library) else stringResource(R.string.drift_remote_not_in_library),
                                color = if (aj != null) RAcc else RLo, fontSize = 13.sp, maxLines = 2)
                            if (aj?.active == true) com.eddyizm.tempus.ui.components.DriftDownloadBar(aj.progress, Modifier.padding(end = 12.dp))
                        }
                        Box(
                            Modifier.clip(RoundedCornerShape(50)).background(if (albumAsked) Color(0xFF2A2A33) else RAcc)
                                .clickable(enabled = !albumAsked) {
                                    albumAsked = true
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) { DriftMixes.requestAlbum(id) }
                                        toast(ctx.getString(if (ok) R.string.drift_remote_album_requested else R.string.drift_request_failed))
                                        if (!ok) albumAsked = false
                                    }
                                }.padding(horizontal = 20.dp, vertical = 12.dp),
                        ) { Text(stringResource(if (albumAsked) R.string.drift_remote_requested else R.string.drift_remote_download_album), color = if (albumAsked) RHi else Color.Black, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
        error?.let { e -> item { Text(e, color = RLo, fontSize = 14.sp, modifier = Modifier.padding(16.dp)) } }
        itemsIndexed(album?.tracks.orEmpty()) { i, t ->
            val mine = inLibrary[t.id]
            Row(Modifier.fillMaxWidth()
                .clickable(enabled = mine != null) {
                    val songs = album?.tracks.orEmpty().mapNotNull { inLibrary[it.id] }
                    play(songs, songs.indexOf(mine).coerceAtLeast(0))
                }
                .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (mine != null) "▶" else "${i + 1}", color = if (mine != null) RAcc else RLo, fontSize = 14.sp, modifier = Modifier.width(28.dp))
                val tj = jobs[t.id]
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(t.title, color = RHi, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${if (t.explicit) "E · " else ""}${t.artist} · ${t.duration / 60}:${"%02d".format(t.duration % 60)}",
                        color = RLo, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    tj?.let { Text(DriftMixes.statusText(it), color = RAcc, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    if (tj?.active == true) com.eddyizm.tempus.ui.components.DriftDownloadBar(tj.progress)
                }
                // Failed: can be retried; the new request stays "Requested" until the new job shows up
                val failed = tj?.state == "failed"
                if (tj?.active == true) SideEffect { retrying.remove(t.id) }
                val asked = if (failed) retrying[t.id] == true else trackAsked[t.id] == true || tj != null
                Box(
                    Modifier.clip(RoundedCornerShape(50)).border(1.dp, RAcc, RoundedCornerShape(50))
                        .background(if (asked) RAcc else Color.Transparent)
                        .clickable(enabled = !asked) {
                            trackAsked[t.id] = true
                            if (failed) retrying[t.id] = true
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { DriftMixes.requestTrack(t.id) }
                                toast(if (ok) ctx.getString(R.string.drift_remote_track_requested, t.title) else ctx.getString(R.string.notification_download_failed))
                                if (!ok) { trackAsked[t.id] = false; retrying.remove(t.id) }
                            }
                        }.padding(horizontal = 12.dp, vertical = 5.dp),
                ) { Text(if (tj?.state == "done") "✓" else stringResource(if (asked) R.string.drift_remote_requested else if (failed) R.string.lite_retry else R.string.drift_remote_download), color = if (asked) Color.Black else RAcc, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** Drift: all the "Releases you're missing" in a grid. */
class DriftMissingFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MissingScreen(
                    open = { r -> NavHostFragment.findNavController(this@DriftMissingFragment)
                        .navigate(R.id.driftRemoteAlbumFragment, DriftRemoteAlbumFragment.args(r.deezerId, r.title, r.artist, r.cover)) },
                    back = { NavHostFragment.findNavController(this@DriftMissingFragment).navigateUp() },
                )
            }
        }
}

@kotlin.OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun MissingScreen(open: (DriftMixes.MissingRelease) -> Unit, back: () -> Unit) {
    var list by remember { mutableStateOf<List<DriftMixes.MissingRelease>?>(null) }
    LaunchedEffect(Unit) {
        list = withContext(Dispatchers.IO) { DriftMixes.fetch()?.mixes?.flatMap { it.missing }.orEmpty() }
    }
    Column(Modifier.fillMaxSize().background(RBg)) {
        Row(Modifier.statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("←", color = RHi, fontSize = 26.sp, modifier = Modifier.clip(CircleShape).clickable(onClick = back).padding(8.dp))
            Column(Modifier.padding(start = 4.dp)) {
                Text(stringResource(R.string.drift_missing_title), color = RHi, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Text(stringResource(R.string.drift_missing_subtitle), color = RLo, fontSize = 13.sp)
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 180.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(list.orEmpty()) { r ->
                Column(Modifier.clickable { open(r) }) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1C1C22))) {
                        if (r.cover != null) GlideImage(r.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Text(r.title, color = RHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    Text("${r.artist} · ${r.type ?: "album"} · ${r.date}", color = RLo, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun libNorm(s: String?): String =
    java.text.Normalizer.normalize(s ?: "", java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()
        .replace(Regex("\\((feat|ft|with)[^)]*\\)|\\[[^]]*]"), " ")
        .replace(Regex("[^a-z0-9]+"), " ").trim()
