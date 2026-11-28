package com.eddyizm.tempus.ui.fragment

import com.eddyizm.tempus.ui.components.DriftColors
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.eddyizm.tempus.App
import com.eddyizm.tempus.R
import com.eddyizm.tempus.glide.CustomGlideRequest
import com.eddyizm.tempus.subsonic.models.AlbumID3
import com.eddyizm.tempus.subsonic.models.ArtistID3
import com.eddyizm.tempus.subsonic.models.Playlist
import com.eddyizm.tempus.ui.activity.MainActivity
import com.eddyizm.tempus.util.Constants
import com.eddyizm.tempus.util.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Drift: Your library. Pill filters, playlists that can be pinned to the top and reordered
 * by long-pressing (the order stays on the phone: Subsonic has no playlist order).
 */
class DriftLibraryFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LibraryScreen(
                    openPlaylist = { nav(R.id.playlistPageFragment, Bundle().apply { putParcelable(Constants.PLAYLIST_OBJECT, it) }) },
                    openAlbum = { nav(R.id.albumPageFragment, Bundle().apply { putParcelable(Constants.ALBUM_OBJECT, it) }) },
                    openArtist = { nav(R.id.artistPageFragment, Bundle().apply { putParcelable(Constants.ARTIST_OBJECT, it) }) },
                    openOffline = { nav(R.id.downloadFragment, Bundle()) },
                    // Drift: hearted songs (Navidrome favorites)
                    openLiked = { nav(R.id.songListPageFragment, Bundle().apply { putString(Constants.MEDIA_STARRED, Constants.MEDIA_STARRED) }) },
                    openFollowed = { e -> nav(R.id.driftSpotifyPlaylistFragment, DriftSpotifyPlaylistFragment.args(e.id, e.name, e.cover)) },
                    openSettings = { nav(R.id.settingsFragment, Bundle()) },
                    openMenu = { nav(R.id.playlistRowBottomSheetDialog, Bundle().apply { putParcelable(Constants.PLAYLIST_OBJECT, it) }) },
                )
            }
        }

    override fun onStart() {
        super.onStart()
        (activity as? MainActivity)?.let {
            it.setBottomNavigationBarVisibility(true)
            it.setBottomSheetVisibility(true)
        }
    }

    private fun nav(dest: Int, b: Bundle) = NavHostFragment.findNavController(this).navigate(dest, b)
}

// ------------------------------------------------------------------ local order

private object LibraryOrder {
    private const val ORDER = "drift_playlist_order"
    private val prefs get() = App.getInstance().preferences

    fun order(): List<String> = prefs.getString(ORDER, "")!!.split(',').filter { it.isNotBlank() }
    fun saveOrder(ids: List<String>) = prefs.edit().putString(ORDER, ids.joinToString(",")).apply()

    /** Pinned on top, then the saved order, then new ones (most recent first). */
    fun apply(list: List<Playlist>, pin: Set<String>): List<Playlist> {
        val pos = order().withIndex().associate { it.value to it.index }
        return list.sortedWith(
            compareBy<Playlist> { if (it.id in pin) 0 else 1 }
                .thenBy { pos[it.id] ?: -1 }
                .thenByDescending { it.changed?.time ?: 0L }
        )
    }
}

private enum class LibFilter(@StringRes val label: Int) { LIKED(R.string.drift_lib_filter_liked), PLAYLIST(R.string.lite_playlists), FOLLOWED(R.string.drift_group_followed), ALBUM(R.string.lite_albums), ARTIST(R.string.lite_artists), OFFLINE(R.string.drift_lib_filter_downloaded) }

private val LBg get() = DriftColors.bg
private val LAccent get() = com.eddyizm.tempus.helper.DriftAccent.compose
private val LHi get() = DriftColors.text
private val LLo get() = DriftColors.textLo
private val LGlass get() = DriftColors.glass
private val LBorder get() = DriftColors.border

private fun loadPlaylists(): List<Playlist> =
    App.getSubsonicClientInstance(false).playlistClient.playlists.execute().body()?.subsonicResponse?.playlists?.playlists.orEmpty()

private fun loadAlbums(): List<AlbumID3> =
    App.getSubsonicClientInstance(false).albumSongListClient.getAlbumList2("recent", 200, 0, null, null).execute()
        .body()?.subsonicResponse?.albumList2?.albums.orEmpty()

private fun loadArtists(): List<ArtistID3> =
    App.getSubsonicClientInstance(false).browsingClient.artists.execute().body()?.subsonicResponse?.artists?.indices
        .orEmpty().flatMap { it.artists.orEmpty() }

@kotlin.OptIn(com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi::class)
@Composable
private fun LibraryScreen(
    openPlaylist: (Playlist) -> Unit,
    openAlbum: (AlbumID3) -> Unit,
    openArtist: (ArtistID3) -> Unit,
    openOffline: () -> Unit,
    openLiked: () -> Unit,
    openFollowed: (com.eddyizm.tempus.helper.DriftSpotify.Entry) -> Unit,
    openSettings: () -> Unit,
    openMenu: (Playlist) -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(LibFilter.PLAYLIST) }
    val playlists = remember { mutableStateListOf<Playlist>() }
    val pinnedRows by remember { com.eddyizm.tempus.repository.PlaylistRepository().pinnedPlaylists }
        .observeAsState(emptyList())
    val pinned = pinnedRows.orEmpty().map { it.playlistId }.toSet()
    val pinnedNow by rememberUpdatedState(pinned)
    LaunchedEffect(pinned) {
        val sorted = LibraryOrder.apply(playlists.toList(), pinned)
        playlists.clear(); playlists.addAll(sorted)
    }
    var albums by remember { mutableStateOf<List<AlbumID3>>(emptyList()) }
    var artists by remember { mutableStateOf<List<ArtistID3>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var followed by remember { mutableStateOf<List<com.eddyizm.tempus.helper.DriftSpotify.Followed>?>(null) }

    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { loadPlaylists() } }
            .onSuccess { playlists.clear(); playlists.addAll(LibraryOrder.apply(it, pinnedNow)) }
            .onFailure { error = it.message }
    }
    LaunchedEffect(filter) {
        if (filter == LibFilter.ALBUM && albums.isEmpty())
            albums = runCatching { withContext(Dispatchers.IO) { loadAlbums() } }.getOrDefault(emptyList())
        if (filter == LibFilter.ARTIST && artists.isEmpty())
            artists = runCatching { withContext(Dispatchers.IO) { loadArtists() } }.getOrDefault(emptyList())
        if (filter == LibFilter.OFFLINE) { filter = LibFilter.PLAYLIST; openOffline() }
        if (filter == LibFilter.LIKED) { filter = LibFilter.PLAYLIST; openLiked() }
        if (filter == LibFilter.FOLLOWED)
            followed = withContext(Dispatchers.IO) { com.eddyizm.tempus.helper.DriftSpotify.followed() } ?: emptyList()
    }

    Column(Modifier.fillMaxSize().background(LBg)) {
        // header
        Row(Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val name = Preferences.getUser() ?: "?"
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(Brush.linearGradient(listOf(LAccent, Color(0xFF22D3EE), Color(0xFF8B5CF6))))
                    .clickable(onClick = openSettings),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(LBg), contentAlignment = Alignment.Center) {
                    Text(name.take(1).uppercase(), color = LHi, fontWeight = FontWeight.Bold)
                }
            }
            Text(stringResource(R.string.drift_lib_title), color = LHi, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 12.dp))
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(LibFilter.entries.toList()) { f ->
                val sel = f == filter
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(if (sel) LAccent else LGlass)
                        .border(1.dp, if (sel) Color.Transparent else LBorder, RoundedCornerShape(50))
                        .clickable { filter = f }.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Liked: a drawn heart, not the emoji
                        if (f == LibFilter.LIKED) androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ds_heart), null,
                            tint = if (sel) Color.Black else Color(0xFFFF4D6D), modifier = Modifier.padding(end = 6.dp).size(16.dp))
                        Text(stringResource(f.label), color = if (sel) Color.Black else LHi, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        when (filter) {
            LibFilter.PLAYLIST, LibFilter.OFFLINE, LibFilter.LIKED -> {
                Text(
                    if (error != null) stringResource(R.string.drift_error_detail, error.orEmpty()) else "",
                    color = LLo, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
                )
                ReorderablePlaylists(
                    playlists, pinned,
                    onOpen = openPlaylist,
                    onTogglePin = openMenu,
                    onReordered = { LibraryOrder.saveOrder(playlists.map { it.id }) },
                )
            }
            LibFilter.FOLLOWED -> LazyColumn(contentPadding = PaddingValues(bottom = 180.dp)) {
                val list = followed
                if (list == null) item { com.eddyizm.tempus.ui.components.DriftLoader(Modifier.fillMaxWidth().height(420.dp)) }
                else if (list.isEmpty()) item {
                    Text(stringResource(R.string.drift_lib_no_followed),
                        color = LLo, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
                }
                items(list.orEmpty()) { f ->
                    Row(Modifier.fillMaxWidth().clickable { openFollowed(f.entry) }.padding(horizontal = 16.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(64.dp).clip(RoundedCornerShape(4.dp)).background(DriftColors.card)) {
                            androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(com.eddyizm.tempus.R.drawable.ic_playlist), null, tint = DriftColors.textLo, modifier = Modifier.align(Alignment.Center).size(28.dp))
                            f.entry.cover?.let { GlideImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(f.entry.name, color = LHi, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.drift_lib_followed_in_library, f.inLibrary, f.entry.count) + (if (f.hasNew) stringResource(R.string.drift_lib_followed_new, f.lastNew) else ""),
                                color = if (f.hasNew) LAccent else LLo, fontSize = 13.sp, maxLines = 1)
                        }
                        if (f.hasNew) Box(Modifier.size(10.dp).clip(CircleShape).background(LAccent))
                    }
                }
            }
            LibFilter.ALBUM -> LazyColumn(contentPadding = PaddingValues(bottom = 180.dp)) {
                items(albums) { a ->
                    Row2(cover = a.coverArtId, title = a.name ?: "", sub = "Album · ${a.artist ?: ""}", round = false) { openAlbum(a) }
                }
            }
            LibFilter.ARTIST -> LazyColumn(contentPadding = PaddingValues(bottom = 180.dp)) {
                items(artists) { a ->
                    Row2(cover = a.coverArtId, title = a.name ?: "", sub = stringResource(R.string.lite_artist), round = true) { openArtist(a) }
                }
            }
        }
    }
}

@Composable
private fun ReorderablePlaylists(
    items: MutableList<Playlist>,
    pinned: Set<String>,
    onOpen: (Playlist) -> Unit,
    onTogglePin: (Playlist) -> Unit,
    onReordered: () -> Unit,
) {
    val listState = rememberLazyListState()
    val pinnedNow by rememberUpdatedState(pinned)
    val haptic = LocalHapticFeedback.current
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    fun indexOfKey(k: String?) = items.indexOfFirst { it.id == k }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = 180.dp),
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { offset.y.toInt() in it.offset..(it.offset + it.size) }
                        ?.let { info ->
                            draggingKey = info.key as? String
                            dragOffset = 0f
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                },
                onDrag = { change, amount ->
                    change.consume()
                    val key = draggingKey ?: return@detectDragGesturesAfterLongPress
                    dragOffset += amount.y
                    val from = indexOfKey(key)
                    val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return@detectDragGesturesAfterLongPress
                    val center = info.offset + info.size / 2 + dragOffset
                    val target = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                        it.key != key && center.toInt() in it.offset..(it.offset + it.size)
                    }
                    if (target != null) {
                        val to = target.index
                        // never cross the boundary between pinned and unpinned
                        if ((items[from].id in pinnedNow) == (items[to].id in pinnedNow)) {
                            val moved = items.removeAt(from)
                            items.add(to, moved)
                            dragOffset += (info.offset - target.offset).toFloat()
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                },
                onDragEnd = { draggingKey = null; dragOffset = 0f; onReordered() },
                onDragCancel = { draggingKey = null; dragOffset = 0f; onReordered() },
            )
        },
    ) {
        itemsIndexed(items, key = { _, p -> p.id }) { _, p ->
            val dragging = p.id == draggingKey
            Box(
                Modifier
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (dragging) dragOffset else 0f
                        scaleX = if (dragging) 1.03f else 1f
                        scaleY = if (dragging) 1.03f else 1f
                    }
                    .then(if (dragging) Modifier.shadow(16.dp, RoundedCornerShape(8.dp)).background(DriftColors.raised, RoundedCornerShape(8.dp)) else Modifier),
            ) {
                PlaylistRow(p, p.id in pinned, onClick = { onOpen(p) }, onMenu = { onTogglePin(p) })
            }
        }
    }
}

@kotlin.OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun PlaylistRow(p: Playlist, isPinned: Boolean, onClick: () -> Unit, onMenu: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(4.dp)).background(DriftColors.card)) {
            androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(com.eddyizm.tempus.R.drawable.ic_playlist), null, tint = DriftColors.textLo, modifier = Modifier.align(Alignment.Center).size(28.dp))
            p.coverArtId?.let {
                GlideImage(CustomGlideRequest.createUrl(it, 300), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(p.name ?: "", color = LHi, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isPinned) {
                    Text("📌", fontSize = 11.sp)
                    Spacer(Modifier.width(4.dp))
                }
                Text("Playlist · " + pluralStringResource(R.plurals.lite_tracks, p.songCount, p.songCount) + (p.owner?.let { " · $it" } ?: ""), color = LLo, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text("⋮", color = if (isPinned) LAccent else LLo, fontSize = 22.sp,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onMenu).padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

@kotlin.OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun Row2(cover: String?, title: String, sub: String, round: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = if (round) CircleShape else RoundedCornerShape(4.dp)
        Box(Modifier.size(64.dp).clip(shape).background(DriftColors.card)) {
            androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(if (round) com.eddyizm.tempus.R.drawable.ds_person else com.eddyizm.tempus.R.drawable.ds_album), null,
                tint = DriftColors.textLo, modifier = Modifier.align(Alignment.Center).size(28.dp))
            cover?.let { GlideImage(CustomGlideRequest.createUrl(it, 300), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, color = LHi, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = LLo, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
