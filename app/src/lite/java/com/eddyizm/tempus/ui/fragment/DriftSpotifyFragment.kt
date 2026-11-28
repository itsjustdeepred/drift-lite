package com.eddyizm.tempus.ui.fragment

import com.eddyizm.tempus.ui.components.DriftColors
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.NavHostFragment
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.eddyizm.tempus.R
import com.eddyizm.tempus.helper.DriftSpotify
import com.eddyizm.tempus.ui.components.SpotifyCard
import com.eddyizm.tempus.ui.activity.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SBg get() = DriftColors.bg
private val SAcc get() = com.eddyizm.tempus.helper.DriftAccent.compose
private val SHi get() = DriftColors.text
private val SLo get() = DriftColors.textLo
private val SCard get() = DriftColors.card

/** Drift: catalog of Spotify playlists, by group, with a field to add one from a link. */
class DriftSpotifyFragment : Fragment() {
    // open subcategory (group key): back closes it instead of leaving the catalog
    private val openGroup = androidx.compose.runtime.mutableStateOf<String?>(null)
    private val backToCatalog = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { openGroup.value = null; isEnabled = false }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        backToCatalog.isEnabled = openGroup.value != null
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backToCatalog)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                // Drift Lite: Home; at first launch language, name, genres and artists
                var onboarded by remember { mutableStateOf(com.eddyizm.tempus.lite.LiteApp.onboarded) }
                if (!onboarded) {
                    LaunchedEffect(Unit) { (activity as? MainActivity)?.setBottomNavigationBarVisibility(false) }
                    // full-screen window: above the mini player and bottom bar, even while a track is playing
                    androidx.compose.ui.window.Dialog(onDismissRequest = {}, properties = androidx.compose.ui.window.DialogProperties(
                        usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
                    com.eddyizm.tempus.lite.LiteOnboarding(
                        onLanguage = { tag ->
                            val v = tag.ifEmpty { "default" }
                            com.eddyizm.tempus.App.getInstance().preferences.edit().putString("language", v).apply()
                            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                                if (tag.isEmpty()) androidx.core.os.LocaleListCompat.getEmptyLocaleList() else androidx.core.os.LocaleListCompat.forLanguageTags(tag))
                        },
                        onDone = { onboarded = true; (activity as? MainActivity)?.setBottomNavigationBarVisibility(true) },
                    )
                    }
                    return@setContent
                }
                SpotifyCatalogScreen(
                    openGroup = openGroup.value,
                    setGroup = { openGroup.value = it; backToCatalog.isEnabled = it != null },
                    open = { id, name, cover -> NavHostFragment.findNavController(this@DriftSpotifyFragment)
                        .navigate(R.id.driftSpotifyPlaylistFragment, DriftSpotifyPlaylistFragment.args(id, name, cover)) },
                    toast = { Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show() },
                    back = { NavHostFragment.findNavController(this@DriftSpotifyFragment).navigateUp() },
                )
            }
        }

    override fun onStart() {
        super.onStart()
        (activity as? MainActivity)?.setBottomNavigationBarVisibility(true)
    }
}

@Composable
private fun SpotifyCatalogScreen(openGroup: String?, setGroup: (String?) -> Unit, open: (String, String?, String?) -> Unit, toast: (String) -> Unit, back: () -> Unit) {
    var list by remember { mutableStateOf<List<DriftSpotify.Entry>?>(null) }
    var reload by remember { mutableStateOf(0) }
    var link by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var why by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    LaunchedEffect(reload) {
        // Drift: the last saved version shows up right away, the new one arrives in background
        if (list == null) withContext(Dispatchers.IO) { DriftSpotify.cachedCatalog() }?.let { list = it }
        list = withContext(Dispatchers.IO) { DriftSpotify.catalog() } ?: list.orEmpty()
        if (list.isNullOrEmpty()) why = withContext(Dispatchers.IO) { liteDiagnose(com.eddyizm.tempus.R.string.drift_what_playlists) }
    }

    // An open subcategory: all its playlists in a grid
    openGroup?.let { g ->
        val entries = list.orEmpty().filter { it.group == g }
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(150.dp),
            modifier = Modifier.fillMaxSize().background(SBg),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 180.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Row(Modifier.statusBarsPadding().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("←", color = SHi, fontSize = 26.sp, modifier = Modifier.clip(CircleShape).clickable { setGroup(null) }.padding(8.dp))
                    Text(entries.firstOrNull()?.let { DriftSpotify.groupLabel(ctx, it) } ?: DriftSpotify.groupLabel(ctx, g), color = SHi, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 4.dp))
                }
            }
            items(entries.size) { i -> val e = entries[i]; SpotifyCard(e, Modifier.fillMaxWidth()) { open(e.id, e.name, e.cover) } }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize().background(SBg), contentPadding = PaddingValues(bottom = 180.dp)) {
        item {
            // Drift Lite: greeting with the name picked at first launch
            val n = com.eddyizm.tempus.lite.LiteApp.name
            Row(Modifier.statusBarsPadding().padding(start = 16.dp, top = 12.dp, end = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (n.isEmpty()) stringResource(R.string.lite_hello) else stringResource(R.string.lite_hello_name, n), color = com.eddyizm.tempus.helper.DriftAccent.composeText, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                com.eddyizm.tempus.lite.RecognizeButton()
                com.eddyizm.tempus.lite.SettingsButton()
            }
            val navView = androidx.compose.ui.platform.LocalView.current
            if (com.eddyizm.tempus.lite.LiteHome.shown(com.eddyizm.tempus.lite.LiteHome.LASTFM)) com.eddyizm.tempus.lite.LiteForYouCard {
                runCatching { androidx.navigation.Navigation.findNavController(navView).navigate(R.id.liteForYouFragment) }
            }
            com.eddyizm.tempus.lite.LiteMostPlayedRow()
            com.eddyizm.tempus.lite.LitePlaylistsRow()
        }
        val l = list
        if (l == null || (l.isEmpty() && why.isEmpty())) item { com.eddyizm.tempus.ui.components.DriftLoader(Modifier.fillMaxWidth().height(520.dp)) }
        else if (l.isEmpty()) item { Text(why, color = SLo, modifier = Modifier.padding(16.dp)) }
        // Drift Lite: sections can be hidden from Settings
        l.orEmpty().filter { com.eddyizm.tempus.lite.LiteHome.shown(it.group) }.groupBy { it.group }.forEach { (group, entries) ->
            item {
                Row(Modifier.fillMaxWidth().clickable { setGroup(group) }.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(DriftSpotify.groupLabel(ctx, entries.first()), color = com.eddyizm.tempus.helper.DriftAccent.composeText, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.lite_see_all, entries.size), color = SLo, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(entries) { e -> SpotifyCard(e) { open(e.id, e.name, e.cover) } }
                }
            }
        }
    }
}

/** Drift Lite: a chart, playlist, album or artist page; songs play from YouTube and can be saved offline. */
class DriftSpotifyPlaylistFragment : Fragment() {
    companion object {
        private const val ARG_ID = "drift_spotify_id"
        private const val ARG_NAME = "drift_spotify_name"
        private const val ARG_COVER = "drift_spotify_cover"
        @JvmStatic
        fun args(id: String, name: String?, cover: String?) = Bundle().apply {
            putString(ARG_ID, id); putString(ARG_NAME, name); putString(ARG_COVER, cover)
        }
    }

    private var browser: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaBrowser>? = null
    private val playingId = androidx.compose.runtime.mutableStateOf<String?>(null)
    private val nowPlayingListener = object : androidx.media3.common.Player.Listener {
        override fun onEvents(player: androidx.media3.common.Player, events: androidx.media3.common.Player.Events) {
            playingId.value = player.currentMediaItem?.mediaId
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val a = requireArguments()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                SpotifyPlaylistScreen(
                    a.getString(ARG_ID)!!, a.getString(ARG_NAME), a.getString(ARG_COVER), playingId.value,
                    toast = { Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show() },
                    play = { songs, i ->
                        browser?.let { b ->
                            com.eddyizm.tempus.service.MediaManager.startQueue(b, songs, i)
                            (activity as? MainActivity)?.setBottomSheetInPeek(true)
                        }
                    },
                    back = { NavHostFragment.findNavController(this@DriftSpotifyPlaylistFragment).navigateUp() },
                    openRef = { ref, n, c -> NavHostFragment.findNavController(this@DriftSpotifyPlaylistFragment)
                        .navigate(R.id.driftSpotifyPlaylistFragment, args(ref, n, c)) },
                    liteActions = com.eddyizm.tempus.lite.TrackActions(
                        playNext = { id -> enqueue(id, true) }, addToQueue = { id -> enqueue(id, false) },
                        openArtist = { name -> Thread {
                            val hit = com.eddyizm.tempus.lite.LiteCatalog.artistRefByName(name) ?: return@Thread
                            activity?.runOnUiThread { if (isAdded) NavHostFragment.findNavController(this@DriftSpotifyPlaylistFragment)
                                .navigate(R.id.driftSpotifyPlaylistFragment, args(hit.first, hit.second.name, hit.second.picture)) }
                        }.start() }),
                )
            }
        }
    }

    private fun enqueue(id: String, next: Boolean) {
        val f = browser ?: return
        val c = com.eddyizm.tempus.lite.LiteTracks.child(id) ?: return
        com.eddyizm.tempus.service.MediaManager.enqueue(f, listOf(c), next)
    }

    override fun onStart() {
        super.onStart()
        (activity as? MainActivity)?.setBottomNavigationBarVisibility(true)
        browser = androidx.media3.session.MediaBrowser.Builder(
            requireContext(),
            androidx.media3.session.SessionToken(requireContext(), android.content.ComponentName(requireContext(), com.eddyizm.tempus.service.MediaService::class.java))
        ).buildAsync().also { f ->
            f.addListener({
                runCatching { f.get() }.getOrNull()?.let { b ->
                    playingId.value = b.currentMediaItem?.mediaId
                    b.addListener(nowPlayingListener)
                }
            }, androidx.core.content.ContextCompat.getMainExecutor(requireContext()))
        }
    }

    override fun onStop() {
        browser?.let { f -> if (f.isDone) runCatching { f.get().removeListener(nowPlayingListener) }; androidx.media3.session.MediaBrowser.releaseFuture(f) }
        super.onStop()
    }
}

@kotlin.OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun SpotifyPlaylistScreen(id: String, name0: String?, cover0: String?, playingId: String?, toast: (String) -> Unit,
                                  play: (List<com.eddyizm.tempus.subsonic.models.Child>, Int) -> Unit, back: () -> Unit,
                                  openRef: (String, String?, String?) -> Unit = { _, _, _ -> },
                                  liteActions: com.eddyizm.tempus.lite.TrackActions) {
    var menuFor by remember { mutableStateOf<String?>(null) }
    menuFor?.let { m -> com.eddyizm.tempus.lite.LiteTrackMenu(m, liteActions, { menuFor = null }) }
    // Drift Lite: artist page = top tracks + albums, singles and EPs, similar artists
    val artistId = if (id.startsWith("dzartist:")) id.substringAfter(':').toLongOrNull() else null
    var disco by remember { mutableStateOf<List<com.eddyizm.tempus.lite.LiteCatalog.AlbumHit>>(emptyList()) }
    var similar by remember { mutableStateOf<List<com.eddyizm.tempus.lite.LiteCatalog.ArtistHit>>(emptyList()) }
    if (artistId != null) LaunchedEffect(artistId) {
        withContext(Dispatchers.IO) {
            disco = com.eddyizm.tempus.lite.LiteCatalog.artistAlbums(artistId)
            similar = com.eddyizm.tempus.lite.LiteCatalog.relatedArtists(artistId)
        }
    }
    var pl by remember { mutableStateOf<DriftSpotify.Playlist?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var bulk by remember { mutableStateOf<DriftSpotify.Bulk?>(null) }
    var busy by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctxApp = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val ctx = LocalContext.current

    LaunchedEffect(id) {
        DriftSpotify.markSeen(id)
        val r = withContext(Dispatchers.IO) { DriftSpotify.playlist(id) }
        if (r == null) error = withContext(Dispatchers.IO) { liteDiagnose(com.eddyizm.tempus.R.string.drift_what_playlist) } else pl = r
    }
    // Missing tracks downloading: state every 2 s, list (with the arrived tracks) every 10 s
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            var tick = 0
            var idle = 0
            while (true) {
                val b = withContext(Dispatchers.IO) { DriftSpotify.downloadState(id) }
                val wasActive = bulk?.active == true
                bulk = b
                // after the last download Navidrome still has to scan: keep reloading the list for a while
                if (b?.active == true) idle = 0 else idle++
                val active = b?.active == true || (b != null && b.total > 0 && idle < 40)
                if (active && tick % 5 == 4) withContext(Dispatchers.IO) { DriftSpotify.playlist(id) }?.let { pl = it }
                tick++
                kotlinx.coroutines.delay(if (active) 2000 else 15000)
            }
        }
    }

    fun playFrom(songId: String?) {
        val p = pl ?: return
        if (busy) return
        busy = true
        scope.launch {
            val ids = p.tracks.mapNotNull { it.songId }.distinct()
            val songs = withContext(Dispatchers.IO) { DriftSpotify.songs(ids) }
            busy = false
            if (songs.isEmpty()) { toast(ctx.getString(R.string.drift_no_tracks_in_library)); return@launch }
            play(songs, songs.indexOfFirst { it.id == songId }.coerceAtLeast(0))
        }
    }

    val name = pl?.name ?: name0 ?: ""
    val cover = pl?.cover ?: cover0
    LazyColumn(Modifier.fillMaxSize().background(SBg), contentPadding = PaddingValues(bottom = 180.dp)) {
        item {
            Box(Modifier.fillMaxWidth()) {
                if (cover != null) GlideImage(cover, null, Modifier.matchParentSize().blur(60.dp), contentScale = ContentScale.Crop, alpha = 0.55f)
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, SBg))))
                Column(Modifier.statusBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("←", color = SHi, fontSize = 26.sp, modifier = Modifier.align(Alignment.Start).clip(CircleShape).clickable(onClick = back).padding(8.dp))
                    Box(Modifier.size(220.dp).clip(RoundedCornerShape(6.dp)).background(SCard)) {
                        if (cover != null) GlideImage(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(name, color = SHi, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.fillMaxWidth())
                    pl?.let { p ->
                        Text(if (id.startsWith("dzartist:")) stringResource(R.string.lite_artist)
                             else pluralStringResource(R.plurals.lite_tracks, p.tracks.size, p.tracks.size), color = SLo, fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                    bulk?.takeIf { it.total > 0 }?.let { b ->
                        val nf = if (b.failed > 0) " · " + stringResource(R.string.drift_not_found_count, b.failed) else ""
                        val txt = if (b.active) stringResource(R.string.drift_download_progress, b.downloaded, b.total) + nf
                                  else stringResource(R.string.drift_downloaded_count, b.downloaded) + nf
                        Text(txt, color = com.eddyizm.tempus.helper.DriftAccent.composeText, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                        if (b.active) {
                            val frac by androidx.compose.animation.core.animateFloatAsState(b.progress / 100f, label = "bulk")
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { frac }, color = SAcc, trackColor = DriftColors.cardHi, gapSize = 0.dp, drawStopIndicator = {},
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp).clip(RoundedCornerShape(2.dp)))
                        }
                    }
                    val p = pl
                    if (p != null) Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val playable = p.tracks.isNotEmpty()
                        Pill(if (busy) "…" else stringResource(R.string.lite_play_button), filled = true, enabled = playable && !busy) { playFrom(null) }
                        val artistPage = id.startsWith("dzartist:")
                        Pill(if (saving) "…" else if (p.followed) "✓ " + stringResource(if (artistPage) R.string.lite_follow else R.string.lite_in_library)
                             else if (artistPage) stringResource(R.string.lite_follow) else "+ " + stringResource(R.string.lite_add_to_library),
                             filled = false, enabled = playable && !saving) {
                            if (p.followed) {
                                scope.launch { withContext(Dispatchers.IO) { DriftSpotify.unfollow(id) }; pl = p.copy(followed = false)
                                    toast(if (artistPage) ctx.getString(R.string.lite_unfollowed_artist, p.name) else ctx.getString(R.string.lite_removed_from_library)) }
                                return@Pill
                            }
                            saving = true
                            scope.launch {
                                // saved = followed on the phone (with the YouTube songs)
                                withContext(Dispatchers.IO) { DriftSpotify.follow(id, false) }
                                pl = p.copy(followed = true)
                                saving = false
                                toast(if (artistPage) ctx.getString(R.string.lite_following_artist, p.name) else ctx.getString(R.string.lite_saved_to_library))
                            }
                        }
                    }
                    val missing = p?.tracks?.count { com.eddyizm.tempus.lite.LiteTracks.isLite(it.songId) && !com.eddyizm.tempus.lite.LiteTracks.isDownloaded(it.songId!!) } ?: 0
                    if (p != null && missing > 0) Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        Pill(stringResource(R.string.lite_download_count, missing), filled = false, enabled = bulk?.active != true) {
                            scope.launch {
                                val b = withContext(Dispatchers.IO) { DriftSpotify.downloadMissing(id).also { if (it != null && !p.followed) DriftSpotify.follow(id, true) } }
                                if (b != null && !p.followed) pl = p.copy(followed = true, autoDownload = true)
                                if (b == null) toast(ctx.getString(R.string.drift_request_failed)) else { bulk = b; toast(ctx.getString(R.string.lite_download_started)) }
                            }
                        }
                    }
                }
            }
        }
        error?.let { e -> item { Text(e, color = SLo, fontSize = 14.sp, modifier = Modifier.padding(16.dp)) } }
        if (pl == null && error == null) item { com.eddyizm.tempus.ui.components.DriftLoader(Modifier.fillMaxWidth().height(360.dp)) }
        if (artistId != null) {
            listOf(ctx.getString(R.string.lite_albums) to disco.filter { it.type == "album" || it.type == "compile" },
                ctx.getString(R.string.lite_singles_eps) to disco.filter { it.type == "single" || it.type == "ep" })
                .filter { it.second.isNotEmpty() }.forEach { (title, list) ->
                    item {
                        Text(title, color = SHi, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 10.dp))
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(list) { a ->
                                SpotifyCard(DriftSpotify.Entry("dzalbum:${a.id}", "", a.title, a.cover, 0, sub = a.year ?: a.artist)) { openRef("dzalbum:${a.id}", a.title, a.cover) }
                            }
                        }
                    }
                }
            if (similar.isNotEmpty()) item {
                Text(stringResource(R.string.lite_similar_artists), color = SHi, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 10.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(similar) { a -> com.eddyizm.tempus.lite.ArtistCircle(a.name, a.picture) { openRef("dzartist:${a.id}", a.name, a.picture) } }
                }
            }
            if (pl?.tracks?.isNotEmpty() == true) item {
                Text(stringResource(R.string.lite_top_tracks), color = SHi, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp))
            }
        }
        itemsIndexed(pl?.tracks.orEmpty()) { i, t ->
            val mine = t.songId != null
            val current = mine && t.songId == playingId
            Row(Modifier.fillMaxWidth().then(if (current) Modifier.background(SAcc.copy(alpha = 0.13f)) else Modifier)
                .clickable(enabled = mine) { playFrom(t.songId) }
                .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (current) "♪" else if (mine) "▶" else "${i + 1}", color = if (mine) com.eddyizm.tempus.helper.DriftAccent.composeText else SLo,
                    fontSize = if (current) 18.sp else 14.sp, modifier = Modifier.width(32.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, color = if (current) com.eddyizm.tempus.helper.DriftAccent.composeText else if (mine) SHi else SLo, fontWeight = if (current) FontWeight.Bold else null, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${t.artist} · ${t.duration / 60}:${"%02d".format(t.duration % 60)}", color = SLo, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val yt = com.eddyizm.tempus.lite.LiteTracks.isLite(t.songId)
                val ytState = if (yt) bulk?.items?.get(i) else null
                if (mine && (ytState == null || ytState.state == "done")) {
                    // Drift: track downloaded on the phone, same icon as the other lists
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val offline = remember(t.songId, ytState?.state) { yt && com.eddyizm.tempus.lite.LiteTracks.isDownloaded(t.songId!!) }
                    if (offline) androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.drift_ic_downloaded), stringResource(R.string.drift_downloaded),
                        Modifier.padding(start = 8.dp).size(16.dp))
                }
                if (!mine || (ytState != null && ytState.state != "done")) {
                    val st = bulk?.items?.get(i)
                    val (label, icon) = when (st?.state) {
                        "waiting" -> stringResource(R.string.drift_state_waiting) to com.eddyizm.tempus.R.drawable.ic_timer
                        "searching" -> stringResource(R.string.lite_state_searching) to com.eddyizm.tempus.R.drawable.ic_search
                        "queued" -> stringResource(R.string.lite_state_queued) to com.eddyizm.tempus.R.drawable.ic_queue
                        "downloading" -> (if (st.progress > 0) stringResource(R.string.lite_state_downloading_pct, st.progress.toInt()) else stringResource(R.string.lite_state_downloading)) to com.eddyizm.tempus.R.drawable.ic_download
                        "done" -> stringResource(R.string.drift_state_added) to com.eddyizm.tempus.R.drawable.ic_check_circle
                        "failed", "not_found" -> stringResource(R.string.lite_state_not_found) to com.eddyizm.tempus.R.drawable.ic_close
                        else -> stringResource(R.string.drift_state_missing) to null
                    }
                    val tint = if (st?.state == "done" || st?.state == "downloading" || st?.state == "searching") SAcc else SLo
                    Column(Modifier.padding(start = 8.dp).width(118.dp), horizontalAlignment = Alignment.End) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (icon != null) androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(icon), null,
                                tint = tint, modifier = Modifier.padding(end = 4.dp).size(14.dp))
                            Text(label, color = tint, fontSize = 12.sp, maxLines = 1)
                        }
                        if (st?.state == "downloading" || st?.state == "searching" || st?.state == "queued") {
                            val f by androidx.compose.animation.core.animateFloatAsState(st.progress / 100f, label = "track")
                            val mod = Modifier.padding(top = 4.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
                            if (st.state == "downloading" && st.progress > 0)
                                androidx.compose.material3.LinearProgressIndicator(progress = { f }, color = SAcc, trackColor = DriftColors.cardHi, gapSize = 0.dp, drawStopIndicator = {}, modifier = mod)
                            else androidx.compose.material3.LinearProgressIndicator(color = SLo, trackColor = DriftColors.cardHi, gapSize = 0.dp, modifier = mod)
                        }
                    }
                }
                // Drift: ⋮ = add to playlist, like, download, queue
                if (mine) Text("⋮", color = SLo, fontSize = 22.sp, modifier = Modifier.clip(CircleShape).clickable {
                    menuFor = t.songId
                }.padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp))
            }
        }
    }
}

@Composable
private fun Pill(text: String, filled: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val accentText = com.eddyizm.tempus.helper.DriftAccent.composeText
    Box(Modifier.clip(shape).then(if (filled) Modifier.background(if (enabled) SAcc else DriftColors.cardHi) else Modifier.border(1.dp, if (enabled) accentText else SLo, shape))
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(text, color = if (filled) (if (enabled) Color(com.eddyizm.tempus.helper.DriftAccent.onAccent()) else SHi) else (if (enabled) accentText else SLo), fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Why a catalog could not be read, in plain words. */
private fun liteDiagnose(@androidx.annotation.StringRes what: Int): String {
    val ctx = com.eddyizm.tempus.App.getContext()
    return if (com.eddyizm.tempus.util.NetworkUtil.isOffline()) ctx.getString(R.string.lite_diag_offline)
    else ctx.getString(R.string.lite_diag_failed, ctx.getString(what))
}
