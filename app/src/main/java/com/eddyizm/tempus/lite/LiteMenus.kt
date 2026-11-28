package com.eddyizm.tempus.lite

import com.eddyizm.tempus.ui.components.DriftColors
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.eddyizm.tempus.R
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Track actions provided by the host screen (queue and artist depend on the screen). */
class TrackActions(
    val playNext: (String) -> Unit,
    val addToQueue: (String) -> Unit,
    val openArtist: (String) -> Unit,
)

/** Drift Lite: the track "⋮" menu. [extra] = additional entries (e.g. "Remove from playlist"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiteTrackMenu(id: String, actions: TrackActions, onDismiss: () -> Unit, extra: List<Pair<String, () -> Unit>> = emptyList()) {
    val t = LiteTracks.get(id) ?: run { onDismiss(); return }
    val ctx = LocalContext.current.applicationContext
    var picker by remember { mutableStateOf(false) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val res = LocalContext.current
    fun toast(id: Int) = Toast.makeText(ctx, res.getString(id), Toast.LENGTH_SHORT).show()
    if (picker) { LitePlaylistPicker(listOf(id)) { picker = false; onDismiss() }; return }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = DriftColors.surface) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(t.title, color = LHi, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp))
            Text(t.artist, color = LLo, fontSize = 14.sp, maxLines = 1, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp))
            val liked = LiteTracks.isLiked(id)
            val offline = LiteTracks.isDownloaded(id)
            // Triple(label, action, closes the menu): the playlist picker keeps it open
            val items = buildList {
                add(Triple(stringResource(R.string.lite_play_next), { actions.playNext(id); toast(R.string.lite_play_next) }, true))
                add(Triple(stringResource(R.string.lite_add_to_queue), { actions.addToQueue(id); toast(R.string.lite_added_to_queue) }, true))
                add(Triple(stringResource(R.string.lite_add_to_playlist), { picker = true }, false))
                add(Triple(stringResource(if (liked) R.string.lite_unlike else R.string.lite_like), {
                    LiteTracks.setLiked(id, !liked)
                    com.eddyizm.tempus.util.FavoriteRegistry.set(com.eddyizm.tempus.util.FavoriteRegistry.Kind.SONG, id, !liked)
                    toast(if (liked) R.string.lite_unliked else R.string.lite_liked_done)
                }, true))
                add(Triple(stringResource(if (offline) R.string.lite_remove_download else R.string.lite_download), {
                    if (offline) { LiteTracks.delete(id); toast(R.string.lite_download_removed) }
                    else { LiteDownloads.enqueue(listOf(id)); toast(R.string.lite_download_started) }
                }, true))
                add(Triple(stringResource(R.string.lite_go_to_artist), { actions.openArtist(t.artist) }, true))
                extra.forEach { (label, action) -> add(Triple(label, action, true)) }
            }
            items.forEach { (label, action, closes) ->
                Text(label, color = LHi, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable {
                    action()
                    if (closes) onDismiss()
                }.padding(horizontal = 20.dp, vertical = 14.dp))
            }
        }
    }
}

/** Pick a playlist (or create a new one) for the given tracks. */
@Composable
fun LitePlaylistPicker(ids: List<String>, onDone: () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    var creating by remember { mutableStateOf(LitePlaylists.all().isEmpty()) }
    var name by remember { mutableStateOf("") }
    val res = LocalContext.current
    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
    if (creating) {
        NameDialog(stringResource(R.string.lite_new_playlist), name, { name = it }, confirm = stringResource(R.string.lite_create), onDismiss = onDone,
            taken = { LitePlaylists.nameTaken(it) }) {
            val pid = LitePlaylists.create(name, ids)
            toast(res.getString(R.string.lite_playlist_created, LitePlaylists.get(pid)?.name ?: name))
            onDone()
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDone, containerColor = DriftColors.surface,
        title = { Text(stringResource(R.string.lite_add_to_playlist), color = LHi) },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(LAcc), contentAlignment = Alignment.Center) {
                            Text("+", color = Color.Black, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(stringResource(R.string.lite_new_playlist), color = LHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
                    }
                }
                items(LitePlaylists.all()) { p ->
                    Row(Modifier.fillMaxWidth().clickable {
                        val n = LitePlaylists.add(p.id, ids)
                        toast(res.getString(if (n == 0) R.string.lite_already_in else R.string.lite_added_to, p.name))
                        onDone()
                    }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(LCard))
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(p.name, color = LHi, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(pluralStringResource(R.plurals.lite_tracks, p.tracks.size, p.tracks.size), color = LLo, fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.lite_cancel), color = LLo) } },
    )
}

@Composable
fun NameDialog(title: String, value: String, onValue: (String) -> Unit, confirm: String, onDismiss: () -> Unit,
               taken: (String) -> Boolean = { false }, onConfirm: () -> Unit) {
    val clash = taken(value)
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = DriftColors.surface,
        title = { Text(title, color = LHi) },
        text = {
            Column {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(LCard).padding(14.dp)) {
                    if (value.isEmpty()) Text(stringResource(R.string.lite_playlist_name), color = LLo, fontSize = 16.sp)
                    BasicTextField(value, { onValue(it.take(60)) }, singleLine = true, textStyle = TextStyle(color = LHi, fontSize = 16.sp),
                        cursorBrush = SolidColor(LAcc), modifier = Modifier.fillMaxWidth())
                }
                if (clash) Text(stringResource(R.string.lite_name_taken), color = DriftColors.error, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = !clash) { Text(confirm, color = if (clash) LLo else com.eddyizm.tempus.helper.DriftAccent.composeText, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lite_cancel), color = LLo) } },
    )
}

/** Playlist picker for non-Compose screens (track menu in the player): lives on top of the activity content. */
object LitePlaylistPickerDialog {
    @JvmStatic
    fun show(activity: androidx.activity.ComponentActivity, ids: ArrayList<String>) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val cv = ComposeView(activity)
        cv.setContent { LitePlaylistPicker(ids) { root.post { root.removeView(cv) } } }
        root.addView(cv, ViewGroup.LayoutParams(0, 0))
    }
}
