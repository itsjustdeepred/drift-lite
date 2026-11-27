package com.eddyizm.tempus.lite

import android.content.ComponentName
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.navigation.fragment.NavHostFragment
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.eddyizm.tempus.R
import com.eddyizm.tempus.helper.DriftSpotify
import com.eddyizm.tempus.service.MediaManager
import com.eddyizm.tempus.service.MediaService
import com.eddyizm.tempus.ui.activity.MainActivity
import com.eddyizm.tempus.ui.fragment.DriftSpotifyPlaylistFragment
import com.eddyizm.tempus.ui.fragment.SpotifyCard
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val LBg = Color(0xFF0B0B0F)
internal val LAcc get() = com.eddyizm.tempus.helper.DriftAccent.compose
internal val LHi = Color.White
internal val LLo = Color(0xFFA7A7B3)
internal val LCard = Color(0xFF1C1C22)

/** Drift Lite: base of the screens (player connection, opening playlists). */
abstract class LiteFragment : Fragment() {
    private var browser: ListenableFuture<MediaBrowser>? = null

    @Composable abstract fun Screen()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { Screen() }
        }

    override fun onStart() {
        super.onStart()
        (activity as? MainActivity)?.setBottomNavigationBarVisibility(true)
        browser = MediaBrowser.Builder(requireContext(), SessionToken(requireContext(), ComponentName(requireContext(), MediaService::class.java))).buildAsync()
    }

    override fun onStop() {
        browser?.let { MediaBrowser.releaseFuture(it) }
        browser = null
        super.onStop()
    }

    fun toast(s: String) { context?.let { Toast.makeText(it, s, Toast.LENGTH_SHORT).show() } }

    /** Queues the tracks (ids "yt:...") and starts from [index]. */
    fun play(ids: List<String>, index: Int) {
        val f = browser ?: return
        val songs = ids.mapNotNull { LiteTracks.child(it) }
        if (songs.isEmpty()) return
        f.addListener({
            runCatching { f.get() }.getOrNull()?.let { b ->
                MediaManager.startQueue(f, songs, index.coerceIn(0, songs.lastIndex))
                (activity as? MainActivity)?.setBottomSheetInPeek(true)
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(requireContext()))
    }

    /** Queue: [next] = right after the current track. */
    fun enqueue(ids: List<String>, next: Boolean) {
        val f = browser ?: return
        val songs = ids.mapNotNull { LiteTracks.child(it) }
        if (songs.isNotEmpty()) MediaManager.enqueue(f, songs, next)
    }

    val actions get() = TrackActions(
        playNext = { enqueue(listOf(it), true) },
        addToQueue = { enqueue(listOf(it), false) },
        openArtist = { name -> Thread {
            val hit = LiteCatalog.artistRefByName(name) ?: return@Thread
            activity?.runOnUiThread { if (isAdded) open(hit.first, hit.second.name, hit.second.picture) }
        }.start() },
    )

    fun openPlaylist(id: String) {
        NavHostFragment.findNavController(this).navigate(R.id.playlistPageFragment, Bundle().apply { putString(LitePlaylistPageFragment.ARG, id) })
    }

    fun open(ref: String, name: String?, cover: String?) {
        NavHostFragment.findNavController(this).navigate(R.id.driftSpotifyPlaylistFragment, DriftSpotifyPlaylistFragment.args(ref, name, cover))
    }
}

// ------------------------------------------------------------------------------------------ Search

class LiteSearchFragment : LiteFragment() {
    @kotlin.OptIn(ExperimentalGlideComposeApi::class)
    @Composable
    override fun Screen() {
        var q by remember { mutableStateOf("") }
        var loading by remember { mutableStateOf(false) }
        var songs by remember { mutableStateOf<List<LiteCatalog.RawTrack>>(emptyList()) }
        var artists by remember { mutableStateOf<List<LiteCatalog.ArtistHit>>(emptyList()) }
        var albums by remember { mutableStateOf<List<LiteCatalog.AlbumHit>>(emptyList()) }
        LaunchedEffect(q) {
            if (q.isBlank()) { songs = emptyList(); artists = emptyList(); albums = emptyList(); return@LaunchedEffect }
            delay(450)
            loading = true
            withContext(Dispatchers.IO) {
                launch { artists = LiteCatalog.searchArtists(q, 8) }
                launch { albums = LiteCatalog.searchAlbums(q, 10) }
                launch { songs = LiteCatalog.searchTracks(q) }
            }
            loading = false
        }
        LazyColumn(Modifier.fillMaxSize().background(LBg).imePadding(), contentPadding = PaddingValues(bottom = 180.dp)) {
            item {
                Text(stringResource(R.string.lite_search), color = LHi, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.statusBarsPadding().padding(16.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(10.dp)).background(LHi).padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                        if (q.isEmpty()) Text(stringResource(R.string.lite_search_hint), color = Color(0xFF55555F), fontSize = 16.sp)
                        BasicTextField(q, { q = it }, singleLine = true, textStyle = TextStyle(color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Medium),
                            cursorBrush = SolidColor(Color.Black), modifier = Modifier.fillMaxWidth())
                    }
                    RecognizeButton(tint = Color(0xFF121212), size = 40)
                }
                if (loading && songs.isEmpty() && artists.isEmpty()) com.eddyizm.tempus.ui.components.DriftLoader(Modifier.fillMaxWidth().height(360.dp))
            }
            if (artists.isNotEmpty()) item {
                Section(stringResource(R.string.lite_artists))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(artists) { a ->
                        Column(Modifier.width(104.dp).clickable { open("dzartist:${a.id}", a.name, a.picture) }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(104.dp).clip(CircleShape).background(LCard)) {
                                if (a.picture != null) GlideImage(a.picture, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            }
                            Text(a.name, color = LHi, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
            if (albums.isNotEmpty()) item {
                Section(stringResource(R.string.lite_albums))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(albums) { a ->
                        SpotifyCard(DriftSpotify.Entry("dzalbum:${a.id}", "", a.title, a.cover, 0, sub = a.artist)) { open("dzalbum:${a.id}", a.title, a.cover) }
                    }
                }
            }
            if (songs.isNotEmpty()) item { Section(stringResource(R.string.lite_songs)) }
            itemsIndexed(songs) { i, s ->
                var menu by remember { mutableStateOf(false) }
                val rid = remember(s) { LiteTracks.register(s.title, s.artist, s.duration, s.cover) }
                if (menu) LiteTrackMenu(rid, actions, { menu = false })
                TrackRow(s.title, s.artist, s.cover, s.duration, onMenu = { menu = true }) {
                    val ids = songs.map { LiteTracks.register(it.title, it.artist, it.duration, it.cover) }
                    play(ids, i)
                }
            }
            if (!loading && q.isNotBlank() && songs.isEmpty() && artists.isEmpty() && albums.isEmpty()) item {
                Text(stringResource(R.string.lite_no_results), color = LLo, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ Library

class LiteLibraryFragment : LiteFragment() {
    @Composable
    override fun Screen() {
        var playlists by remember { mutableStateOf<List<DriftSpotify.Followed>>(emptyList()) }
        var artists by remember { mutableStateOf<List<DriftSpotify.Entry>>(emptyList()) }
        var saved by remember { mutableStateOf<List<LiteTracks.T>>(emptyList()) }
        var liked by remember { mutableStateOf<List<LiteTracks.T>>(emptyList()) }
        var showAllLiked by remember { mutableStateOf(false) }
        var discover by remember { mutableStateOf<List<LiteCatalog.ArtistHit>>(emptyList()) }
        var discoverOpen by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { discover = withContext(Dispatchers.IO) { LiteCatalog.discover() } }
        if (discoverOpen) {
            androidx.compose.runtime.DisposableEffect(Unit) {
                val cb = object : androidx.activity.OnBackPressedCallback(true) { override fun handleOnBackPressed() { discoverOpen = false } }
                requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, cb)
                onDispose { cb.remove() }
            }
            DiscoverGrid(discover, back = { discoverOpen = false; artists = LiteCatalog.followedArtists() }) { a -> open("dzartist:${a.id}", a.name, a.picture) }
            return
        }
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                liked = LiteTracks.liked()
                playlists = LiteCatalog.followed()
                artists = LiteCatalog.followedArtists()
                saved = LiteTracks.downloaded()
            }
        }
        LazyColumn(Modifier.fillMaxSize().background(LBg), contentPadding = PaddingValues(bottom = 180.dp)) {
            item {
                Row(Modifier.statusBarsPadding().padding(start = 16.dp, top = 12.dp, end = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.lite_library), color = LHi, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                    SettingsButton()
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Section(stringResource(R.string.lite_liked) + if (liked.isNotEmpty()) " · ${liked.size}" else "") }
                    if (liked.isNotEmpty()) Box(Modifier.clip(RoundedCornerShape(50)).background(LAcc).clickable { play(liked.map { it.id }, 0) }
                        .padding(horizontal = 16.dp, vertical = 8.dp)) { Text(stringResource(R.string.lite_play_button), color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                }
                if (liked.isEmpty()) Hint(stringResource(R.string.lite_no_tracks))
            }
            val likedShown = if (showAllLiked) liked else liked.take(5)
            itemsIndexed(likedShown) { i, t ->
                var menu by remember { mutableStateOf(false) }
                if (menu) LiteTrackMenu(t.id, actions, { menu = false })
                TrackRow(t.title, t.artist, t.cover, t.duration, offline = LiteTracks.isDownloaded(t.id), onMenu = { menu = true }) { play(liked.map { it.id }, i) }
            }
            if (liked.size > 5 && !showAllLiked) item {
                Text(stringResource(R.string.lite_show_all, liked.size), color = LLo, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { showAllLiked = true }.padding(horizontal = 16.dp, vertical = 10.dp))
            }
            item {
                Section(stringResource(R.string.lite_playlists))
                // your own playlists first, then saved charts/albums/playlists
                val own = remember { LitePlaylists.all() }
                if (playlists.isEmpty() && own.isEmpty()) Hint(stringResource(R.string.lite_no_saved_playlists))
                else LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(own) { p ->
                        val cover = p.tracks.firstNotNullOfOrNull { LiteTracks.get(it)?.cover }
                        SpotifyCard(DriftSpotify.Entry("local:" + p.id, "", p.name, cover, p.tracks.size,
                            sub = pluralStringResource(R.plurals.lite_tracks, p.tracks.size, p.tracks.size))) { openPlaylist(p.id) }
                    }
                    items(playlists) { f -> SpotifyCard(f.entry, hasNew = f.hasNew) { open(f.entry.id, f.entry.name, f.entry.cover) } }
                }
            }
            item {
                Section(stringResource(R.string.lite_artists))
                if (artists.isEmpty()) Hint(stringResource(R.string.lite_no_artists))
                else LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(artists) { a -> ArtistCircle(a.name, a.cover) { open(a.id, a.name, a.cover) } }
                }
            }
            if (discover.isNotEmpty()) item {
                Row(Modifier.fillMaxWidth().clickable { discoverOpen = true }.padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Section(stringResource(R.string.lite_discover_artists)) }
                    Text(stringResource(R.string.lite_see_more), color = LLo, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(discover.take(15)) { a -> ArtistCircle(a.name, a.picture) { open("dzartist:${a.id}", a.name, a.picture) } }
                }
            }

        }
    }
}

// ------------------------------------------------------------------------------------------ shared pieces

@Composable
internal fun Section(title: String) {
    Text(title, color = LHi, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 10.dp))
}

@Composable
internal fun Hint(text: String) {
    Text(text, color = LLo, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp))
}

@kotlin.OptIn(ExperimentalGlideComposeApi::class)
@Composable
internal fun ArtistCircle(name: String, picture: String?, selected: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.width(104.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(104.dp)) {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(LCard).then(if (selected) Modifier.border(3.dp, LAcc, CircleShape) else Modifier)) {
                if (picture != null) GlideImage(picture, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            if (selected) Box(Modifier.align(Alignment.BottomEnd).padding(4.dp).size(26.dp).clip(CircleShape).background(LAcc), contentAlignment = Alignment.Center) {
                Text("✓", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
        Text(name, color = LHi, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

@kotlin.OptIn(ExperimentalGlideComposeApi::class)
@Composable
internal fun TrackRow(title: String, artist: String, cover: String?, duration: Int, offline: Boolean = false, onMenu: (() -> Unit)? = null,
                      trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(LCard)) {
            if (cover != null) GlideImage(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, color = LHi, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist + if (duration > 0) " · ${duration / 60}:${"%02d".format(duration % 60)}" else "", color = LLo, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (offline) androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.drift_ic_downloaded), stringResource(R.string.lite_on_phone), Modifier.padding(start = 8.dp).size(16.dp))
        trailing?.invoke()
        if (onMenu != null) Text("⋮", color = LLo, fontSize = 22.sp, modifier = Modifier.clip(CircleShape).clickable(onClick = onMenu).padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

// ------------------------------------------------------------------------------------------ first launch

/** Language, name, genres and artists: once, then changed from settings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiteOnboarding(onLanguage: (String) -> Unit, onDone: () -> Unit) {
    val prefs = com.eddyizm.tempus.App.getInstance().preferences
    var step by remember { mutableIntStateOf(prefs.getInt("lite_onboarding_step", 0)) }
    fun go(s: Int) { step = s; prefs.edit().putInt("lite_onboarding_step", s).apply() }
    var name by remember { mutableStateOf(LiteApp.name) }
    val genres = remember { mutableStateListOf<Int>().apply { addAll(LiteApp.genres) } }
    val picked = remember { mutableStateListOf<Long>() }
    var suggestions by remember { mutableStateOf<List<LiteCatalog.ArtistHit>>(emptyList()) }
    var q by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<LiteCatalog.ArtistHit>>(emptyList()) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(LBg).statusBarsPadding().navigationBarsPadding().imePadding().padding(20.dp)) {
        Text(stringResource(R.string.lite_step, step + 1, 4), color = LAcc, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        when (step) {
            0 -> {
                Title(stringResource(R.string.lite_language), "")
                val current = prefs.getString("language", "default")
                LazyColumn(Modifier.weight(1f)) {
                    items(LiteApp.LANGUAGES) { (tag, label) ->
                        val sel = (tag.isEmpty() && (current == "default" || current.isNullOrEmpty())) || tag == current
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                            go(1)
                            onLanguage(tag)
                        }.padding(vertical = 14.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label.ifEmpty { stringResource(R.string.lite_phone_language) }, color = if (sel) LAcc else LHi, fontSize = 17.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                            if (sel) Text("✓", color = LAcc, fontSize = 17.sp)
                        }
                    }
                }
                Spacer(Modifier.height(1.dp))
            }
            1 -> {
                Title(stringResource(R.string.lite_whats_your_name), "")
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(LCard).padding(16.dp)) {
                    if (name.isEmpty()) Text(stringResource(R.string.lite_your_name), color = LLo, fontSize = 18.sp)
                    BasicTextField(name, { name = it.take(30) }, singleLine = true, textStyle = TextStyle(color = LHi, fontSize = 18.sp),
                        cursorBrush = SolidColor(LAcc), modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.weight(1f))
                Buttons(back = { go(0) }, next = stringResource(R.string.lite_continue)) { LiteApp.name = name; go(2) }
            }
            2 -> {
                Title(stringResource(R.string.lite_favorite_genres), "")
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LiteApp.GENRES.forEach { g ->
                        val id = g.id
                        val label = if (g.res != 0) stringResource(g.res) else g.name
                        val sel = id in genres
                        Box(Modifier.clip(RoundedCornerShape(50)).background(if (sel) LAcc else LCard)
                            .clickable { if (sel) genres.remove(id) else genres.add(id) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(label, color = if (sel) Color.Black else LHi, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Buttons(back = { go(1) }, next = stringResource(if (genres.isEmpty()) R.string.lite_skip else R.string.lite_continue)) {
                    LiteApp.genres = genres.toList(); go(3)
                }
            }
            else -> {
                LaunchedEffect(Unit) { suggestions = withContext(Dispatchers.IO) { LiteCatalog.genreArtists(LiteApp.genres.ifEmpty { listOf(132) }) } }
                LaunchedEffect(q) {
                    if (q.isBlank()) { found = emptyList(); return@LaunchedEffect }
                    delay(400); found = withContext(Dispatchers.IO) { LiteCatalog.searchArtists(q, 9) }
                }
                Title(stringResource(R.string.lite_favorite_artists), "")
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(LCard).padding(14.dp)) {
                    if (q.isEmpty()) Text(stringResource(R.string.lite_search_artist), color = LLo, fontSize = 16.sp)
                    BasicTextField(q, { q = it }, singleLine = true, textStyle = TextStyle(color = LHi, fontSize = 16.sp),
                        cursorBrush = SolidColor(LAcc), modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(12.dp))
                val shown = if (q.isBlank()) suggestions else found
                LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { com.eddyizm.tempus.ui.components.DriftLoader(Modifier.fillMaxWidth().height(360.dp)) }
                    items(shown.size) { i ->
                        val a = shown[i]
                        ArtistCircle(a.name, a.picture, a.id in picked) {
                            if (a.id in picked) picked.remove(a.id) else picked.add(a.id)
                            scope.launch(Dispatchers.IO) {
                                if (a.id in picked) {
                                    LiteCatalog.followArtist(a)
                                    // artists similar to the one picked, right after it: the list grows with your taste
                                    val rel = LiteCatalog.relatedArtists(a.id).filter { r -> shown.none { it.id == r.id } }
                                    if (rel.isNotEmpty() && q.isBlank()) {
                                        val cur = suggestions.toMutableList()
                                        val at = cur.indexOfFirst { it.id == a.id }.let { if (it < 0) cur.size else it + 1 }
                                        cur.addAll(at, rel.take(6)); suggestions = cur
                                    }
                                } else LiteCatalog.unfollow("dzartist:${a.id}")
                            }
                        }
                    }
                }
                Buttons(back = { go(2) }, next = if (picked.isEmpty()) stringResource(R.string.lite_skip) else stringResource(R.string.lite_done_count, picked.size)) {
                    LiteApp.onboarded = true; prefs.edit().remove("lite_onboarding_step").apply(); onDone()
                }
            }
        }
    }
}

@Composable
private fun Title(t: String, sub: String) {
    Text(t, color = LHi, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(bottom = 20.dp))
    if (sub.isNotEmpty()) Text(sub, color = LLo, fontSize = 15.sp, modifier = Modifier.padding(bottom = 20.dp))
}

@Composable
private fun Buttons(back: () -> Unit, next: String, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.lite_back), color = LLo, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = back).padding(14.dp))
        Spacer(Modifier.weight(1f))
        Box(Modifier.clip(RoundedCornerShape(50)).background(LAcc).clickable(onClick = onNext).padding(horizontal = 28.dp, vertical = 14.dp)) {
            Text(next, color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}


// ------------------------------------------------------------------------------------------ Playlists (tab)

class LitePlaylistsFragment : LiteFragment() {
    @Composable
    override fun Screen() {
        var list by remember { mutableStateOf(LitePlaylists.all()) }
        // saved and downloaded albums and playlists, below your own
        var saved by remember { mutableStateOf<List<DriftSpotify.Followed>>(emptyList()) }
        LaunchedEffect(Unit) { saved = withContext(Dispatchers.IO) { LiteCatalog.followed() } }
        var editing by remember { mutableStateOf(false) }
        var creating by remember { mutableStateOf(false) }
        var name by remember { mutableStateOf("") }
        val liked = remember { LiteTracks.liked() }
        if (creating) NameDialog(stringResource(R.string.lite_new_playlist), name, { name = it }, stringResource(R.string.lite_create), { creating = false }) {
            val id = LitePlaylists.create(name); creating = false; name = ""; list = LitePlaylists.all(); openPlaylist(id)
        }
        LazyColumn(Modifier.fillMaxSize().background(LBg), contentPadding = PaddingValues(bottom = 180.dp)) {
            item {
                Row(Modifier.statusBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.lite_playlists), color = LHi, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                    if (list.size > 1) Text(stringResource(if (editing) R.string.lite_done else R.string.lite_reorder), color = if (editing) LAcc else LLo, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable { editing = !editing }.padding(10.dp))
                    Box(Modifier.clip(RoundedCornerShape(50)).background(LAcc).clickable { creating = true }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Text(stringResource(R.string.lite_new), color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item {
                PlaylistRow(stringResource(R.string.lite_liked), pluralStringResource(R.plurals.lite_tracks, liked.size, liked.size), liked.firstOrNull()?.cover, heart = true) {
                    NavHostFragment.findNavController(this@LitePlaylistsFragment).navigate(R.id.playlistPageFragment,
                        Bundle().apply { putString(LitePlaylistPageFragment.ARG, LitePlaylistPageFragment.LIKED) })
                }
            }
            if (list.isEmpty()) item { Hint(stringResource(R.string.lite_no_playlists)) }
            itemsIndexed(list, key = { _, p -> p.id }) { i, p ->
                val cover = p.tracks.firstNotNullOfOrNull { LiteTracks.get(it)?.cover }
                PlaylistRow(p.name, pluralStringResource(R.plurals.lite_tracks, p.tracks.size, p.tracks.size), cover, trailing = if (!editing) null else ({
                    Arrows(i > 0, i < list.lastIndex,
                        up = { LitePlaylists.movePlaylist(i, i - 1); list = LitePlaylists.all() },
                        down = { LitePlaylists.movePlaylist(i, i + 1); list = LitePlaylists.all() })
                })) { if (!editing) openPlaylist(p.id) }
            }
            items(saved, key = { "s" + it.entry.id }) { f ->
                val sub = if (f.entry.id.startsWith("dzalbum:")) stringResource(R.string.lite_album) + " · " + pluralStringResource(R.plurals.lite_tracks, f.entry.count, f.entry.count)
                    else pluralStringResource(R.plurals.lite_tracks, f.entry.count, f.entry.count)
                PlaylistRow(f.entry.name, sub, f.entry.cover) { if (!editing) open(f.entry.id, f.entry.name, f.entry.cover) }
            }
        }
    }
}

@kotlin.OptIn(ExperimentalGlideComposeApi::class)
@Composable
internal fun PlaylistRow(name: String, sub: String, cover: String?, heart: Boolean = false, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(60.dp).clip(RoundedCornerShape(6.dp)).background(if (heart) LAcc else LCard), contentAlignment = Alignment.Center) {
            if (heart) Text("♥", color = Color.Black, fontSize = 26.sp)
            else if (cover != null) GlideImage(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text("♪", color = LLo, fontSize = 24.sp)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(name, color = LHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = LLo, fontSize = 13.sp)
        }
        trailing?.invoke()
    }
}

@Composable
internal fun Arrows(canUp: Boolean, canDown: Boolean, up: () -> Unit, down: () -> Unit, remove: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("↑", color = if (canUp) LHi else Color(0xFF3A3A44), fontSize = 22.sp, modifier = Modifier.clip(CircleShape).clickable(enabled = canUp, onClick = up).padding(10.dp))
        Text("↓", color = if (canDown) LHi else Color(0xFF3A3A44), fontSize = 22.sp, modifier = Modifier.clip(CircleShape).clickable(enabled = canDown, onClick = down).padding(10.dp))
        if (remove != null) Text("✕", color = Color(0xFFFF6B6B), fontSize = 18.sp, modifier = Modifier.clip(CircleShape).clickable(onClick = remove).padding(10.dp))
    }
}

// ------------------------------------------------------------------------------------------ playlist page

class LitePlaylistPageFragment : LiteFragment() {
    companion object { const val ARG = "lite_playlist_id"; const val LIKED = "__liked__" }

    @kotlin.OptIn(ExperimentalGlideComposeApi::class)
    @Composable
    override fun Screen() {
        val pid = requireArguments().getString(ARG)!!
        val isLiked = pid == LIKED
        var p by remember { mutableStateOf<LitePlaylists.P?>(if (isLiked) null else LitePlaylists.get(pid)) }
        var liked by remember { mutableStateOf<List<String>>(if (isLiked) LiteTracks.liked().map { it.id } else emptyList()) }
        val ids = if (isLiked) liked else p?.tracks.orEmpty()
        val title = if (isLiked) stringResource(R.string.lite_liked) else p?.name ?: ""
        var editing by remember { mutableStateOf(false) }
        var renaming by remember { mutableStateOf(false) }
        var confirmDelete by remember { mutableStateOf(false) }
        var name by remember { mutableStateOf(title) }
        var saving by remember { mutableStateOf(0) }
        val scope = rememberCoroutineScope()
        fun reload() { if (isLiked) liked = LiteTracks.liked().map { it.id } else p = LitePlaylists.get(pid) }

        if (renaming) NameDialog(stringResource(R.string.lite_rename), name, { name = it }, stringResource(R.string.lite_save), { renaming = false }) { LitePlaylists.rename(pid, name); renaming = false; reload() }
        if (confirmDelete) androidx.compose.material3.AlertDialog(onDismissRequest = { confirmDelete = false }, containerColor = Color(0xFF17171C),
            title = { Text(stringResource(R.string.lite_delete_confirm, title), color = LHi) }, text = { Text("", color = LLo) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { LitePlaylists.delete(pid); confirmDelete = false
                NavHostFragment.findNavController(this).navigateUp() }) { Text(stringResource(R.string.lite_delete), color = Color(0xFFFF6B6B)) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.lite_cancel), color = LLo) } })

        val missing = ids.count { !LiteTracks.isDownloaded(it) }
        LazyColumn(Modifier.fillMaxSize().background(LBg), contentPadding = PaddingValues(bottom = 180.dp)) {
            item {
                Column(Modifier.statusBarsPadding().padding(16.dp)) {
                    Text("←", color = LHi, fontSize = 26.sp, modifier = Modifier.clip(CircleShape).clickable { NavHostFragment.findNavController(this@LitePlaylistPageFragment).navigateUp() }.padding(8.dp))
                    Text(title, color = LHi, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 8.dp))
                    Text(pluralStringResource(R.plurals.lite_tracks, ids.size, ids.size), color = LLo, fontSize = 13.sp)
                    FlowRowButtons {
                        Pill(stringResource(R.string.lite_play_button), filled = true, enabled = ids.isNotEmpty()) { play(ids, 0) }
                        Pill(stringResource(R.string.lite_shuffle), enabled = ids.isNotEmpty()) { play(ids.shuffled(), 0) }
                        if (missing > 0) Pill(stringResource(R.string.lite_save_offline, missing)) {
                            val n = LiteDownloads.enqueue(ids)
                            toast(getString(if (n > 0) R.string.lite_download_started else R.string.lite_already_downloading))
                        }
                        if (!isLiked) Pill(stringResource(if (editing) R.string.lite_done else R.string.lite_edit), enabled = ids.isNotEmpty() || editing) { editing = !editing }
                        if (!isLiked) Pill(stringResource(R.string.lite_rename)) { name = title; renaming = true }
                        if (!isLiked) Pill(stringResource(R.string.lite_delete)) { confirmDelete = true }
                    }
                }
            }
            if (ids.isEmpty()) item { Hint(stringResource(R.string.lite_no_tracks)) }
            itemsIndexed(ids, key = { i, id -> "$i$id" }) { i, id ->
                val t = LiteTracks.get(id) ?: return@itemsIndexed
                var menu by remember { mutableStateOf(false) }
                val removeLabel = stringResource(R.string.lite_remove_from_playlist)
                if (menu) LiteTrackMenu(id, actions, { menu = false; reload() },
                    extra = if (isLiked) emptyList() else listOf(removeLabel to { LitePlaylists.removeAt(pid, i); reload() }))
                TrackRow(t.title, t.artist, t.cover, t.duration, offline = LiteTracks.isDownloaded(id),
                    onMenu = if (editing) null else ({ menu = true }),
                    trailing = if (!editing) null else ({
                        Arrows(i > 0, i < ids.lastIndex,
                            up = { LitePlaylists.moveTrack(pid, i, i - 1); reload() },
                            down = { LitePlaylists.moveTrack(pid, i, i + 1); reload() },
                            remove = { LitePlaylists.removeAt(pid, i); reload() })
                    })) { if (!editing) play(ids, i) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowButtons(content: @Composable () -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
internal fun Pill(text: String, filled: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(Modifier.clip(shape).then(if (filled) Modifier.background(if (enabled) LAcc else Color(0xFF2A2A33)) else Modifier.border(1.dp, if (enabled) LAcc else LLo, shape))
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(text, color = if (filled) (if (enabled) Color.Black else LHi) else (if (enabled) LAcc else LLo), fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

// ------------------------------------------------------------------------------------------ Offline (tab)

class LiteOfflineFragment : LiteFragment() {
    @Composable
    override fun Screen() {
        var saved by remember { mutableStateOf(LiteTracks.downloaded()) }
        val queue by LiteDownloads.items.collectAsState()
        // a track that just finished shows up right away among the ones on the phone
        val doneCount = queue.count { it.state == LiteDownloads.S.DONE }
        LaunchedEffect(doneCount) { saved = withContext(Dispatchers.IO) { LiteTracks.downloaded() } }
        val pending = queue.filter { it.pending }
        val failed = queue.filter { it.state == LiteDownloads.S.FAILED || it.state == LiteDownloads.S.NOT_FOUND || it.state == LiteDownloads.S.CANCELLED }
        LazyColumn(Modifier.fillMaxSize().background(LBg), contentPadding = PaddingValues(bottom = 180.dp)) {
            item {
                Row(Modifier.statusBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.lite_offline), color = LHi, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                        Text(pluralStringResource(R.plurals.lite_tracks, saved.size, saved.size), color = LLo, fontSize = 13.sp)
                    }
                    if (saved.isNotEmpty()) Pill(stringResource(R.string.lite_play_button), filled = true) { play(saved.map { it.id }, 0) }
                }
            }
            if (pending.isNotEmpty() || failed.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { Section(if (pending.isNotEmpty()) stringResource(R.string.lite_in_progress, pending.size) else stringResource(R.string.lite_failed_count, failed.size)) }
                        if (pending.isNotEmpty()) Text(stringResource(R.string.lite_cancel_all), color = Color(0xFFFF6B6B), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable { LiteDownloads.cancelAll() }.padding(10.dp))
                        if (failed.isNotEmpty()) Text(stringResource(R.string.lite_retry), color = LAcc, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable { LiteDownloads.retryFailed() }.padding(10.dp))
                        if (pending.isEmpty()) Text(stringResource(R.string.lite_clear), color = LLo, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable { LiteDownloads.clearFinished() }.padding(10.dp))
                    }
                }
                items(pending + failed, key = { "q" + it.id }) { q -> DownloadRow(q) }
                item { Spacer(Modifier.height(8.dp)) }
            }
            if (saved.isEmpty() && pending.isEmpty()) item { Hint(stringResource(R.string.lite_no_downloads)) }
            itemsIndexed(saved, key = { _, t -> t.id }) { i, t ->
                var menu by remember { mutableStateOf(false) }
                if (menu) LiteTrackMenu(t.id, actions, { menu = false; saved = LiteTracks.downloaded() })
                TrackRow(t.title, t.artist, t.cover, t.duration, offline = true, onMenu = { menu = true }) { play(saved.map { it.id }, i) }
            }
        }
    }
}

@kotlin.OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun DownloadRow(q: LiteDownloads.Item) {
    val t = LiteTracks.get(q.id) ?: return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)).background(LCard)) {
            if (t.cover != null) GlideImage(t.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(t.title, color = LHi, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val (label, color) = when (q.state) {
                LiteDownloads.S.QUEUED -> stringResource(R.string.lite_state_queued) to LLo
                LiteDownloads.S.SEARCHING -> stringResource(R.string.lite_state_searching) to LAcc
                LiteDownloads.S.DOWNLOADING -> (if (q.progress > 0) stringResource(R.string.lite_state_downloading_pct, q.progress.toInt()) else stringResource(R.string.lite_state_downloading)) to LAcc
                LiteDownloads.S.NOT_FOUND -> stringResource(R.string.lite_state_not_found) to Color(0xFFFF6B6B)
                LiteDownloads.S.FAILED -> stringResource(R.string.lite_state_failed) to Color(0xFFFF6B6B)
                LiteDownloads.S.CANCELLED -> stringResource(R.string.lite_state_cancelled) to LLo
                LiteDownloads.S.DONE -> stringResource(R.string.lite_state_saved) to LAcc
            }
            Text("${t.artist} · $label", color = color, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (q.pending) {
                val mod = Modifier.padding(top = 5.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
                if (q.state == LiteDownloads.S.DOWNLOADING && q.progress > 0)
                    androidx.compose.material3.LinearProgressIndicator(progress = { q.progress / 100f }, color = LAcc, trackColor = Color(0xFF2A2A33), gapSize = 0.dp, drawStopIndicator = {}, modifier = mod)
                else androidx.compose.material3.LinearProgressIndicator(color = LLo, trackColor = Color(0xFF2A2A33), gapSize = 0.dp, modifier = mod)
            }
        }
        if (q.pending) Text("✕", color = LLo, fontSize = 18.sp, modifier = Modifier.clip(CircleShape).clickable { LiteDownloads.cancel(q.id) }.padding(12.dp))
    }
}

/** Settings gear (backup and restore live there). */
@Composable
fun SettingsButton() {
    val view = androidx.compose.ui.platform.LocalView.current
    Box(Modifier.size(44.dp).clip(CircleShape).clickable {
        runCatching { androidx.navigation.Navigation.findNavController(view).navigate(R.id.settingsFragment) }
    }, contentAlignment = Alignment.Center) {
        androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_settings), stringResource(R.string.lite_settings), tint = LHi, modifier = Modifier.size(24.dp))
    }
}

/** Discover: artist grid; tap = page, green circle = follow (as at first launch). */
@Composable
private fun DiscoverGrid(list: List<LiteCatalog.ArtistHit>, back: () -> Unit, open: (LiteCatalog.ArtistHit) -> Unit) {
    val followed = remember { androidx.compose.runtime.mutableStateListOf<Long>() }
    val scope = rememberCoroutineScope()
    LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.fillMaxSize().background(LBg), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 180.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.statusBarsPadding().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("←", color = LHi, fontSize = 26.sp, modifier = Modifier.clip(CircleShape).clickable(onClick = back).padding(8.dp))
                Text(stringResource(R.string.lite_discover_artists), color = LHi, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 4.dp))
            }
        }
        items(list.size) { i ->
            val a = list[i]
            val on = a.id in followed
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ArtistCircle(a.name, a.picture, on) { open(a) }
                Text(if (on) "✓ " + stringResource(R.string.lite_follow) else stringResource(R.string.lite_follow), color = if (on) LAcc else LLo, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable {
                        if (on) followed.remove(a.id) else followed.add(a.id)
                        scope.launch(Dispatchers.IO) { if (!on) LiteCatalog.followArtist(a) else LiteCatalog.unfollow("dzartist:${a.id}") }
                    }.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
}
