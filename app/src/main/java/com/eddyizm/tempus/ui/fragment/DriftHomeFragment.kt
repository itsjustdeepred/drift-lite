package com.eddyizm.tempus.ui.fragment

import com.eddyizm.tempus.ui.components.DriftColors
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.annotation.StringRes

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.navigation.fragment.NavHostFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.eddyizm.tempus.R
import com.eddyizm.tempus.glide.CustomGlideRequest
import com.eddyizm.tempus.service.MediaManager
import com.eddyizm.tempus.service.MediaService
import com.eddyizm.tempus.subsonic.models.AlbumID3
import com.eddyizm.tempus.subsonic.models.ArtistID3
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.subsonic.models.Playlist
import com.eddyizm.tempus.ui.activity.MainActivity
import com.eddyizm.tempus.util.Constants
import com.eddyizm.tempus.util.Preferences
import com.eddyizm.tempus.viewmodel.HomeViewModel
import com.google.common.util.concurrent.ListenableFuture
import java.util.Calendar

/**
 * Drift: Compose home. Spotify layout (filters, shortcuts, horizontal rows),
 * with a "futuristic" look: animated aurora background, glass and glows.
 */
@OptIn(UnstableApi::class)
class DriftHomeFragment : Fragment() {

    private lateinit var homeViewModel: HomeViewModel
    private var browser: ListenableFuture<MediaBrowser>? = null
    private val madeForYou = androidx.compose.runtime.mutableStateOf<List<Playlist>>(emptyList())
    private val missing = androidx.compose.runtime.mutableStateOf<List<com.eddyizm.tempus.helper.DriftMixes.MissingRelease>>(emptyList())
    private val spotify = androidx.compose.runtime.mutableStateOf<List<com.eddyizm.tempus.helper.DriftSpotify.Entry>>(emptyList())
    private val followed = androidx.compose.runtime.mutableStateOf<List<com.eddyizm.tempus.helper.DriftSpotify.Followed>>(emptyList())
    private val kidsAlbums = androidx.compose.runtime.mutableStateOf(com.eddyizm.tempus.helper.DriftMixes.kidsAlbums())
    private val kidsArtists = androidx.compose.runtime.mutableStateOf(com.eddyizm.tempus.helper.DriftMixes.kidsArtists())


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        homeViewModel = ViewModelProvider(requireActivity())[HomeViewModel::class.java]
        val owner = this
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val vo = viewLifecycleOwner
                DriftHome(
                    recent = homeViewModel.getRecentlyPlayedAlbumList(vo),
                    newReleases = homeViewModel.getRecentlyReleasedAlbums(vo),
                    added = homeViewModel.getMostRecentlyAddedAlbums(vo),
                    mostPlayed = homeViewModel.getMostPlayedAlbums(vo),
                    artists = homeViewModel.getStarredArtistsSample(vo),
                    discover = homeViewModel.getDiscoverSongSample(vo),
                    playlists = homeViewModel.getPinnedPlaylists(vo),
                    madeForYou = madeForYou.value,
                    missing = missing.value,
                    spotify = spotify.value,
                    followed = followed.value,
                    kidsAlbums = kidsAlbums.value,
                    kidsArtists = kidsArtists.value,
                    actions = owner.actions,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.eddyizm.tempus.helper.DriftSpotify.catalog() }
                ?.let { spotify.value = it }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.eddyizm.tempus.helper.DriftSpotify.followed() }
                ?.let { followed.value = it }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val res = com.eddyizm.tempus.helper.DriftMixes.fetch()
                if (res != null) runCatching { com.eddyizm.tempus.helper.DriftMixes.sync(res) }
                val pls = runCatching {
                    com.eddyizm.tempus.App.getSubsonicClientInstance(false).playlistClient.playlists.execute()
                        .body()?.subsonicResponse?.playlists?.playlists.orEmpty()
                }.getOrDefault(emptyList())
                res to pls
            }
            val order = r.first?.mixes?.map { com.eddyizm.tempus.helper.DriftMixes.PREFIX + it.title }.orEmpty()
            madeForYou.value = r.second.filter { it.name?.startsWith(com.eddyizm.tempus.helper.DriftMixes.PREFIX) == true }
                .sortedBy { order.indexOf(it.name).let { i -> if (i < 0) 99 else i } }
            missing.value = r.first?.mixes?.flatMap { it.missing }.orEmpty()
            r.first?.let { if (it.kidsAlbums.isNotEmpty()) { kidsAlbums.value = it.kidsAlbums; kidsArtists.value = it.kidsArtists } }
        }
        browser = MediaBrowser.Builder(
            requireContext(),
            SessionToken(requireContext(), ComponentName(requireContext(), MediaService::class.java))
        ).buildAsync()
    }

    override fun onStop() {
        browser?.let { MediaBrowser.releaseFuture(it) }
        super.onStop()
    }

    private fun nav(dest: Int, bundle: Bundle) =
        NavHostFragment.findNavController(this).navigate(dest, bundle)

    private fun peek() = (activity as? MainActivity)?.setBottomSheetInPeek(true)

    private fun play(songs: List<Child>?, index: Int = 0) {
        val b = browser ?: return
        if (songs.isNullOrEmpty()) return
        MediaManager.startQueue(b, songs, index)
        peek()
    }

    /** Observes a LiveData once and consumes it as soon as a non-empty list arrives. */
    private fun <T> once(data: LiveData<List<T>>, block: (List<T>) -> Unit) {
        val owner = viewLifecycleOwner
        val obs = object : androidx.lifecycle.Observer<List<T>> {
            override fun onChanged(value: List<T>) {
                if (value.isNotEmpty()) { data.removeObserver(this); block(value) }
            }
        }
        data.observe(owner, obs)
    }

    /** Last requested mix: if the user taps another mix before it arrives, the old one is ignored. */
    private var mixRequest = 0

    val actions = HomeActions(
        openAlbum = { a -> nav(R.id.albumPageFragment, Bundle().apply { putParcelable(Constants.ALBUM_OBJECT, a) }) },
        openArtist = { a -> nav(R.id.artistPageFragment, Bundle().apply { putParcelable(Constants.ARTIST_OBJECT, a) }) },
        openPlaylist = { p -> nav(R.id.playlistPageFragment, Bundle().apply { putParcelable(Constants.PLAYLIST_OBJECT, p) }) },
        openSettings = { nav(R.id.settingsFragment, Bundle()) },
        // Drift: "Recognize" opens Search and starts listening right away
        recognize = { nav(R.id.searchFragment, Bundle().apply { putBoolean(DriftSearchFragment.ARG_RECOGNIZE, true) }) },
        playSongs = { songs, i -> play(songs, i) },
        // New LiveData for each request (the HomeViewModel ones are shared across taps)
        artistMix = { a -> val n = ++mixRequest
            once(com.eddyizm.tempus.repository.ArtistRepository().getTopSongs(a.name, 10)) { if (n == mixRequest) play(it) } },
        radioFrom = { seed -> val n = ++mixRequest
            once(com.eddyizm.tempus.repository.SongRepository().getInstantMix(seed.id, com.eddyizm.tempus.util.Constants.SeedType.TRACK, 20)) { if (n == mixRequest) play(it) } },
        openAlbumList = { key -> nav(R.id.albumListPageFragment, Bundle().apply { putString(key, key) }) },
        openMissing = { nav(R.id.driftMissingFragment, Bundle()) },
        openPlaylists = { nav(R.id.playlistCatalogueFragment, Bundle().apply { putString(Constants.PLAYLIST_ALL, Constants.PLAYLIST_ALL) }) },
        openMixes = { nav(R.id.playlistCatalogueFragment, Bundle().apply {
            putString(Constants.PLAYLIST_ALL, Constants.PLAYLIST_ALL); putBoolean(Constants.PLAYLIST_MIXES, true)
        }) },
        openSpotify = { nav(R.id.driftSpotifyFragment, Bundle()) },
        openSpotifyPlaylist = { e -> nav(R.id.driftSpotifyPlaylistFragment, DriftSpotifyPlaylistFragment.args(e.id, e.name, e.cover)) },
        openRemoteAlbum = { rel -> nav(R.id.driftRemoteAlbumFragment, DriftRemoteAlbumFragment.args(rel.deezerId, rel.title, rel.artist, rel.cover)) },
        requestAlbum = { rel ->
            viewLifecycleOwner.lifecycleScope.launch {
                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.eddyizm.tempus.helper.DriftMixes.requestAlbum(rel.deezerId) }
                android.widget.Toast.makeText(requireContext(), if (ok) getString(R.string.drift_home_asked_lidarr, rel.title) else getString(R.string.drift_home_download_failed), android.widget.Toast.LENGTH_SHORT).show()
            }
        },
    )
}

class HomeActions(
    val openAlbum: (AlbumID3) -> Unit,
    val openArtist: (ArtistID3) -> Unit,
    val openPlaylist: (Playlist) -> Unit,
    val openSettings: () -> Unit,
    val recognize: () -> Unit,
    val playSongs: (List<Child>, Int) -> Unit,
    val artistMix: (ArtistID3) -> Unit,
    val radioFrom: (Child) -> Unit,
    val requestAlbum: (com.eddyizm.tempus.helper.DriftMixes.MissingRelease) -> Unit,
    val openAlbumList: (String) -> Unit,
    val openMissing: () -> Unit,
    val openPlaylists: () -> Unit,
    val openMixes: () -> Unit,
    val openRemoteAlbum: (com.eddyizm.tempus.helper.DriftMixes.MissingRelease) -> Unit,
    val openSpotify: () -> Unit,
    val openSpotifyPlaylist: (com.eddyizm.tempus.helper.DriftSpotify.Entry) -> Unit,
)

// ---------------------------------------------------------------- palette

private val Bg get() = DriftColors.bg
private val Accent get() = com.eddyizm.tempus.helper.DriftAccent.compose
private val Cyan = Color(0xFF22D3EE)
private val Violet = Color(0xFF8B5CF6)
private val TextHi get() = DriftColors.text
private val TextLo get() = DriftColors.textLo
private val Glass get() = DriftColors.glass
private val GlassBorder get() = DriftColors.border
/** Secondary text over a dark image overlay: stays light in both themes. */
private val OnImageLo = Color(0xFFA7A7B3)

private val Display = FontFamily.Default

private enum class Filter(@StringRes val label: Int) {
    ALL(R.string.drift_home_filter_all), MUSIC(R.string.drift_home_filter_music), MIX(R.string.drift_home_filter_mix), NEW(R.string.drift_group_releases)
}

// ---------------------------------------------------------------- screen

@Composable
private fun DriftHome(
    recent: LiveData<List<AlbumID3>>,
    newReleases: LiveData<List<AlbumID3>>,
    added: LiveData<List<AlbumID3>>,
    mostPlayed: LiveData<List<AlbumID3>>,
    artists: LiveData<List<ArtistID3>>,
    discover: LiveData<List<Child>>,
    playlists: LiveData<List<Playlist>>,
    madeForYou: List<Playlist>,
    missing: List<com.eddyizm.tempus.helper.DriftMixes.MissingRelease>,
    spotify: List<com.eddyizm.tempus.helper.DriftSpotify.Entry> = emptyList(),
    followed: List<com.eddyizm.tempus.helper.DriftSpotify.Followed> = emptyList(),
    kidsAlbums: Set<String>,
    kidsArtists: Set<String>,
    actions: HomeActions,
) {
    // "Exclude kids": no kids' music on the home (shortcuts, album rows, artists)
    fun List<AlbumID3>?.noKids() = orEmpty().filter { it.id !in kidsAlbums && it.artistId !in kidsArtists }
    val recentL = recent.observeAsState(emptyList()).value.noKids()
    val newL = newReleases.observeAsState(emptyList()).value.noKids()
    val addedL = added.observeAsState(emptyList()).value.noKids()
    val mostL = mostPlayed.observeAsState(emptyList()).value.noKids()
    val artistsL = artists.observeAsState(emptyList()).value.orEmpty().filter { it.id !in kidsArtists }
    val discoverL = discover.observeAsState(emptyList()).value.orEmpty().filter { it.albumId !in kidsAlbums && it.artistId !in kidsArtists }
    val playlistsL by playlists.observeAsState(emptyList())
    var filter by remember { mutableStateOf(Filter.ALL) }

    val show = { f: Filter -> filter == Filter.ALL || filter == f }
    val heroCover = recentL.orEmpty().firstOrNull()?.coverArtId ?: discoverL.orEmpty().firstOrNull()?.coverArtId

    Box(Modifier.fillMaxSize().background(Bg)) {
        Aurora(heroCover)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 160.dp),
        ) {
            item {
                Column(Modifier.statusBarsPadding().padding(top = 8.dp)) {
                    TopBar(filter, onFilter = { filter = it }, onAvatar = actions.openSettings,
                        onRecognize = actions.recognize)  // recognition runs on the phone, Undertow only for downloads
                    Greeting()
                }
            }

            if (filter == Filter.ALL || filter == Filter.MUSIC) {
                val shortcuts = buildShortcuts(recentL.orEmpty(), playlistsL.orEmpty())
                if (shortcuts.isNotEmpty()) item { ShortcutGrid(shortcuts, actions) }
            }

            if (show(Filter.MIX) && madeForYou.isNotEmpty()) item {
                Section(stringResource(R.string.drift_home_made_for_you), null, actions.openMixes) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(madeForYou) { p -> MadeForYouCard(p, madeForYou.indexOf(p)) { actions.openPlaylist(p) } }
                    }
                }
            }

            if (show(Filter.MIX) && followed.isNotEmpty()) item {
                val newCount = followed.count { it.hasNew }
                Section(stringResource(R.string.drift_group_followed), if (newCount > 0) stringResource(R.string.drift_home_followed_new, newCount) else null, actions.openSpotify) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(followed.sortedByDescending { it.hasNew }) { f -> SpotifyCard(f.entry, hasNew = f.hasNew) { actions.openSpotifyPlaylist(f.entry) } }
                    }
                }
            }

            if (show(Filter.MIX) && spotify.isNotEmpty()) item {
                Section(stringResource(R.string.drift_home_charts_playlists), null, actions.openSpotify) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(spotify.take(12)) { e -> SpotifyCard(e) { actions.openSpotifyPlaylist(e) } }
                    }
                }
            }

            if (show(Filter.MIX)) {
                val seed = discoverL.orEmpty().firstOrNull()
                if (seed != null) item {
                    HeroRadio(seed) { actions.radioFrom(seed) }
                }
                // Favorite artists; if there are none, the artists of recently/most played albums
                val mixArtists = artistsL.orEmpty().ifEmpty {
                    (recentL.orEmpty() + mostL.orEmpty())
                        .filter { !it.artistId.isNullOrBlank() && it.artist != null && !it.artist!!.contains("Various", true) }
                        .distinctBy { it.artistId }
                        .map { ArtistID3(id = it.artistId, name = it.artist, coverArtId = it.coverArtId) }
                }
                if (mixArtists.isNotEmpty()) item {
                    Section(stringResource(R.string.drift_home_your_mixes), null) {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(mixArtists.take(10)) { a -> MixCard(a, mixArtists.indexOf(a)) { actions.artistMix(a) } }
                        }
                    }
                }
            }

            if (show(Filter.MUSIC)) albumRow(this, R.string.drift_home_recently_played, null, recentL.orEmpty(), actions, Constants.ALBUM_RECENTLY_PLAYED)
            if (show(Filter.NEW)) albumRow(this, R.string.drift_group_releases, null, newL.orEmpty(), actions, Constants.ALBUM_NEW_RELEASES)
            if (show(Filter.NEW)) albumRow(this, R.string.drift_home_recently_added, null, addedL.orEmpty(), actions, Constants.ALBUM_RECENTLY_ADDED)
            if (show(Filter.NEW) && missing.isNotEmpty()) item {
                Section(stringResource(R.string.drift_home_missing_releases), null, actions.openMissing) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(missing) { rel -> MissingCard(rel, onOpen = { actions.openRemoteAlbum(rel) }) { actions.requestAlbum(rel) } }
                    }
                }
            }
            if (show(Filter.MUSIC)) albumRow(this, R.string.drift_home_most_played, null, mostL.orEmpty(), actions, Constants.ALBUM_MOST_PLAYED)

            if (show(Filter.MUSIC) && artistsL.orEmpty().isNotEmpty()) item {
                Section(stringResource(R.string.drift_home_artists_you_love), null) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(artistsL.orEmpty()) { a -> ArtistBubble(a) { actions.openArtist(a) } }
                    }
                }
            }

            if (show(Filter.MIX) && discoverL.orEmpty().isNotEmpty()) item {
                Section(stringResource(R.string.drift_home_rediscover), null) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        discoverL.orEmpty().take(6).forEachIndexed { i, s ->
                            SongRow(s) { actions.playSongs(discoverL.orEmpty(), i) }
                        }
                    }
                }
            }
        }
    }
}

private sealed class Shortcut {
    data class A(val album: AlbumID3) : Shortcut()
    data class P(val playlist: Playlist) : Shortcut()
}

private fun buildShortcuts(recent: List<AlbumID3>, playlists: List<Playlist>): List<Shortcut> {
    val out = mutableListOf<Shortcut>()
    playlists.take(2).forEach { out += Shortcut.P(it) }
    recent.forEach { if (out.size < 8) out += Shortcut.A(it) }
    playlists.drop(2).forEach { if (out.size < 8) out += Shortcut.P(it) }
    return out
}

private fun albumRow(scope: androidx.compose.foundation.lazy.LazyListScope, @StringRes title: Int, subtitle: String?, albums: List<AlbumID3>, actions: HomeActions, moreKey: String? = null) {
    if (albums.isEmpty()) return
    scope.item {
        Section(stringResource(title), subtitle, moreKey?.let { k -> { actions.openAlbumList(k) } }) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(albums) { a -> AlbumCard(a) { actions.openAlbum(a) } }
            }
        }
    }
}

// ---------------------------------------------------------------- pieces

@kotlin.OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun Aurora(coverId: String?) {
    val t = rememberInfiniteTransition(label = "aurora")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Reverse), label = "p")
    Box(Modifier.fillMaxWidth().height(460.dp)) {
        if (coverId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            GlideImage(
                model = CustomGlideRequest.createUrl(coverId, 200),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(90.dp),
                alpha = 0.55f,
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            drawRect(Brush.radialGradient(listOf(Accent.copy(alpha = .35f), Color.Transparent),
                center = Offset(w * (0.15f + 0.25f * p), h * 0.25f), radius = w * 0.7f))
            drawRect(Brush.radialGradient(listOf(Violet.copy(alpha = .30f), Color.Transparent),
                center = Offset(w * (0.95f - 0.3f * p), h * (0.15f + 0.2f * p)), radius = w * 0.6f))
            drawRect(Brush.radialGradient(listOf(Cyan.copy(alpha = .18f), Color.Transparent),
                center = Offset(w * 0.6f, h * (0.55f - 0.15f * p)), radius = w * 0.5f))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Bg), startY = h * 0.35f, endY = h))
        }
    }
}

@Composable
private fun TopBar(filter: Filter, onFilter: (Filter) -> Unit, onAvatar: () -> Unit, onRecognize: (() -> Unit)? = null) {
    val name = Preferences.getUser() ?: "?"
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(Accent, Cyan, Violet)))
                .clickable(onClick = onAvatar),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(Bg), contentAlignment = Alignment.Center) {
                Text(name.take(1).uppercase(), color = TextHi, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
        Spacer(Modifier.width(10.dp))
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Filter.entries.toList()) { f ->
                val sel = f == filter
                Box(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(if (sel) Accent else Glass)
                        .border(1.dp, if (sel) Color.Transparent else GlassBorder, RoundedCornerShape(50))
                        .clickable { onFilter(if (sel && f != Filter.ALL) Filter.ALL else f) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(f.label), color = if (sel) Color.Black else TextHi, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        // Drift: shortcut to "Recognize song"
        if (onRecognize != null) {
            Spacer(Modifier.width(6.dp))
            RecognizeMicButton(TextHi, onRecognize)
        }
    }
}

@Composable
private fun Greeting() {
    // Drift: simple greeting with the user name
    val u = com.eddyizm.tempus.util.Preferences.getUser().orEmpty().substringBefore('@').trim()
    val g = if (u.isEmpty()) stringResource(R.string.drift_home_hello) else stringResource(R.string.drift_home_hello_name, u.replaceFirstChar { it.titlecase() })
    Text(
        g, color = TextHi, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, fontFamily = Display,
        letterSpacing = (-0.5).sp, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp),
    )
}

@kotlin.OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun ShortcutGrid(items: List<Shortcut>, actions: HomeActions) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { s ->
                    val (title, cover, click) = when (s) {
                        is Shortcut.A -> Triple(s.album.name ?: "", s.album.coverArtId, { actions.openAlbum(s.album) })
                        is Shortcut.P -> Triple(s.playlist.name ?: "", s.playlist.coverArtId, { actions.openPlaylist(s.playlist) })
                    }
                    Row(
                        Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(6.dp))
                            .background(Glass).border(1.dp, GlassBorder, RoundedCornerShape(6.dp))
                            .clickable(onClick = click),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Cover(cover, Modifier.size(56.dp))
                        Text(
                            title, color = TextHi, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp),
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@kotlin.OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun HeroRadio(seed: Child, onPlay: () -> Unit) {
    val t = rememberInfiniteTransition(label = "glow")
    val glow by t.animateFloat(0.35f, 0.8f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "g")
    Box(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp).fillMaxWidth().height(190.dp)
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, Brush.linearGradient(listOf(Accent.copy(alpha = .7f), Violet.copy(alpha = .5f), Cyan.copy(alpha = .4f))), RoundedCornerShape(20.dp))
            .clickable(onClick = onPlay),
    ) {
        Cover(seed.coverArtId, Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(24.dp) else Modifier))
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xE60B0B0F), Color(0x660B0B0F)))))
        Column(Modifier.padding(20.dp).align(Alignment.CenterStart)) {
            Text("DRIFT RADIO", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.drift_home_start_radio), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 28.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.drift_home_radio_from, seed.title ?: "?", seed.artist ?: ""), color = OnImageLo, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(220.dp),
            )
        }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(18.dp).size(56.dp)
                .shadow(24.dp * glow, CircleShape, ambientColor = Accent, spotColor = Accent)
                .clip(CircleShape).background(Accent),
            contentAlignment = Alignment.Center,
        ) { PlayGlyph(Color.Black) }
    }
}

@Composable
private fun PlayGlyph(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val p = Path().apply {
            moveTo(size.width * 0.2f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * 0.2f, size.height); close()
        }
        drawPath(p, color)
    }
}

@Composable
private fun Section(title: String, subtitle: String?, onMore: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 30.dp)) {
        Row(
            Modifier.fillMaxWidth().then(if (onMore != null) Modifier.clickable(onClick = onMore) else Modifier)
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                if (subtitle != null) Text(subtitle, color = TextLo, fontSize = 13.sp)
                Text(title, color = TextHi, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp)
            }
            if (onMore != null) Text(stringResource(R.string.drift_home_show_all), color = TextLo, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp))
        }
        content()
    }
}

@kotlin.OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun Cover(id: String?, modifier: Modifier) {
    Box(modifier.background(DriftColors.card)) {
        // shown until (or instead of) the cover: empty playlists have none
        androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(com.eddyizm.tempus.R.drawable.ds_note), null, tint = DriftColors.textLo,
            modifier = Modifier.align(Alignment.Center).fillMaxSize(0.35f))
        if (id != null) GlideImage(
            model = CustomGlideRequest.createUrl(id, 300),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun AlbumCard(a: AlbumID3, onClick: () -> Unit) {
    Column(Modifier.width(148.dp).clickable(onClick = onClick)) {
        Cover(a.coverArtId, Modifier.size(148.dp).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.height(8.dp))
        Text(a.name ?: "", color = TextHi, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(a.artist, a.year.takeIf { it > 0 }?.toString()).joinToString(" · "),
            color = TextLo, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

private val mixColors = listOf(Accent, Cyan, Violet, Color(0xFFF59E0B), Color(0xFFF43F5E), Color(0xFF3B82F6))

@Composable
private fun MixCard(a: ArtistID3, index: Int, onClick: () -> Unit) {
    val c = mixColors[index.mod(mixColors.size)]
    Column(Modifier.width(156.dp).clickable(onClick = onClick)) {
        Box(Modifier.size(156.dp).clip(RoundedCornerShape(10.dp))) {
            Cover(a.coverArtId, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, c.copy(alpha = .85f)))))
            Box(Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomCenter).background(c))
            Text(
                "Mix", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.drift_home_mix_similar, a.name ?: ""), color = TextLo, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ArtistBubble(a: ArtistID3, onClick: () -> Unit) {
    Column(Modifier.width(112.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(112.dp).clip(CircleShape)
                .border(2.dp, Brush.sweepGradient(listOf(Accent, Cyan, Violet, Accent)), CircleShape)
                .padding(4.dp).clip(CircleShape),
        ) { Cover(a.coverArtId, Modifier.fillMaxSize()) }
        Spacer(Modifier.height(8.dp))
        Text(a.name ?: "", color = TextHi, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SongRow(s: Child, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(s.coverArtId, Modifier.size(52.dp).clip(RoundedCornerShape(4.dp)))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(s.title ?: "", color = TextHi, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.artist ?: "", color = TextLo, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}


@Composable
private fun MadeForYouCard(p: Playlist, index: Int, onClick: () -> Unit) {
    val c = mixColors[index.mod(mixColors.size)]
    val title = (p.name ?: "").removePrefix(com.eddyizm.tempus.helper.DriftMixes.PREFIX)
    Column(Modifier.width(156.dp).clickable(onClick = onClick)) {
        Box(Modifier.size(156.dp).clip(RoundedCornerShape(10.dp))) {
            Cover(p.coverArtId, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(c.copy(alpha = .55f), Color(0xCC0B0B0F)))))
            Box(Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomCenter).background(c))
            PlayGlyphSmall(Modifier.align(Alignment.TopStart).padding(10.dp))
            Text(title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 21.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(pluralStringResource(R.plurals.lite_tracks, p.songCount, p.songCount), color = TextLo, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun PlayGlyphSmall(modifier: Modifier) {
    Text("d", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black, modifier = modifier)
}

@kotlin.OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun MissingCard(rel: com.eddyizm.tempus.helper.DriftMixes.MissingRelease, onOpen: () -> Unit, onDownload: () -> Unit) {
    var asked by remember { mutableStateOf(false) }
    Column(Modifier.width(148.dp).clickable(onClick = onOpen)) {
        Box(Modifier.size(148.dp).clip(RoundedCornerShape(8.dp)).background(DriftColors.card)) {
            if (rel.cover != null) GlideImage(rel.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(
                Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(50))
                    .background(if (asked) Accent else Color(0xE6000000))
                    .border(1.dp, Accent, RoundedCornerShape(50))
                    .clickable(enabled = !asked) { asked = true; onDownload() }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) { Text(stringResource(if (asked) R.string.drift_home_requested else R.string.lite_download), color = if (asked) Color.Black else Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(8.dp))
        Text(rel.title, color = TextHi, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${rel.artist} · ${rel.date}", color = TextLo, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
