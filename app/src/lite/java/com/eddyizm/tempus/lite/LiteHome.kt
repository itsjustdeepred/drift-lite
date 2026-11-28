package com.eddyizm.tempus.lite

import com.eddyizm.tempus.ui.components.DriftColors
import android.content.Context
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.eddyizm.tempus.App
import com.eddyizm.tempus.R
import com.eddyizm.tempus.helper.DriftSpotify

/**
 * Drift Lite Home: which sections are shown (chosen in Settings) and the personal rows on top,
 * "Most played" (songs played past halfway, counted on the phone) and "Your playlists".
 */
object LiteHome {
    const val TOP = "top"
    const val PLAYLISTS = "playlists"
    const val LASTFM = "lastfm"
    private const val KEY = "lite_home_hidden"

    /** Every section the user can hide, in Home order, with its label. */
    val SECTIONS: List<Pair<String, Int>> = listOf(
        LASTFM to R.string.lite_fy_title,
        TOP to R.string.lite_home_top,
        PLAYLISTS to R.string.lite_home_playlists,
        DriftSpotify.G_RELEASES to R.string.drift_group_releases,
        DriftSpotify.G_FOR_YOU to R.string.drift_group_for_you,
        DriftSpotify.G_MINE to R.string.lite_group_saved,
        DriftSpotify.G_CHARTS to R.string.drift_group_charts,
        DriftSpotify.G_ADDED to R.string.drift_group_added,
        DriftSpotify.G_GENRES to R.string.drift_group_genres,
        DriftSpotify.G_DECADES to R.string.drift_group_decades,
        DriftSpotify.G_MOODS to R.string.drift_group_moods,
    )

    private val prefs get() = App.getInstance().preferences
    val hidden: Set<String> get() = prefs.getStringSet(KEY, emptySet()).orEmpty()
    fun shown(key: String) = key !in hidden
    fun setShown(key: String, on: Boolean) {
        prefs.edit().putStringSet(KEY, hidden.toMutableSet().apply { if (on) remove(key) else add(key) }).apply()
    }
}

private fun navigate(view: android.view.View, dest: Int, args: Bundle? = null) {
    runCatching { androidx.navigation.Navigation.findNavController(view).navigate(dest, args) }
}

/** "Most played": up to 20 songs, a tap plays them all from that one. */
@kotlin.OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun LiteMostPlayedRow() {
    if (!LiteHome.shown(LiteHome.TOP)) return
    val ctx = LocalContext.current
    val top = remember { LiteTracks.mostPlayed(20) }
    if (top.isEmpty()) return
    HomeTitle(stringResource(R.string.lite_home_top))
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(top) { i, t ->
            Column(Modifier.width(140.dp).clip(RoundedCornerShape(8.dp)).clickable { litePlay(ctx, top.map { it.id }, i) }) {
                Box(Modifier.size(140.dp).clip(RoundedCornerShape(8.dp)).background(LCard)) {
                    if (t.cover != null) GlideImage(t.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Text(t.title, color = LHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                Text(t.artist, color = LLo, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** "Your playlists": Liked first, then the playlists made on the phone. */
@kotlin.OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun LitePlaylistsRow() {
    if (!LiteHome.shown(LiteHome.PLAYLISTS)) return
    val view = LocalView.current
    val own = remember { LitePlaylists.all() }
    val liked = remember { LiteTracks.liked() }
    if (own.isEmpty() && liked.isEmpty()) return
    HomeTitle(stringResource(R.string.lite_home_playlists))
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (liked.isNotEmpty()) item {
            PlaylistTile(stringResource(R.string.lite_liked), pluralStringResource(R.plurals.lite_tracks, liked.size, liked.size), null, heart = true) {
                navigate(view, R.id.playlistPageFragment, Bundle().apply { putString(LitePlaylistPageFragment.ARG, LitePlaylistPageFragment.LIKED) })
            }
        }
        items(own) { p ->
            PlaylistTile(p.name, pluralStringResource(R.plurals.lite_tracks, p.tracks.size, p.tracks.size), p.tracks.firstNotNullOfOrNull { LiteTracks.get(it)?.cover }) {
                navigate(view, R.id.playlistPageFragment, Bundle().apply { putString(LitePlaylistPageFragment.ARG, p.id) })
            }
        }
    }
}

@kotlin.OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun PlaylistTile(name: String, sub: String, cover: String?, heart: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.width(140.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        Box(Modifier.size(140.dp).clip(RoundedCornerShape(8.dp)).then(if (heart) Modifier.background(LikedBrush) else Modifier.background(LCard)),
            contentAlignment = Alignment.Center) {
            if (heart) androidx.compose.material3.Icon(painterResource(R.drawable.ds_heart), null, tint = Color.White, modifier = Modifier.size(48.dp))
            else if (cover != null) GlideImage(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else androidx.compose.material3.Icon(painterResource(R.drawable.ic_playlist), null, tint = LLo, modifier = Modifier.size(44.dp))
        }
        Text(name, color = LHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(sub, color = LLo, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun HomeTitle(text: String) {
    Text(text, color = com.eddyizm.tempus.helper.DriftAccent.composeText, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 10.dp))
}

/** Settings: show or hide each Home section. */
@Composable
fun LiteHomeSectionsDialog(onDismiss: () -> Unit) {
    var hidden by remember { mutableStateOf(LiteHome.hidden) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = DriftColors.surface,
        title = { Text(stringResource(R.string.lite_home_sections), color = LHi) },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                // the Last.fm card is only an invite: once connected the picks are part of "For you"
                items(LiteHome.SECTIONS.filter { it.first != LiteHome.LASTFM || (LiteLastFm.available && !LiteLastFm.connected) }) { (key, label) ->
                    val on = key !in hidden
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                        LiteHome.setShown(key, !on); hidden = LiteHome.hidden
                    }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(label), color = LHi, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Switch(on, { LiteHome.setShown(key, it); hidden = LiteHome.hidden },
                            colors = SwitchDefaults.colors(checkedTrackColor = LAcc, checkedThumbColor = Color.Black))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lite_done), color = com.eddyizm.tempus.helper.DriftAccent.composeText, fontWeight = FontWeight.Bold) } },
    )
}
