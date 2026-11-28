package com.eddyizm.tempus.lite

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.fragment.NavHostFragment
import com.eddyizm.tempus.R
import com.eddyizm.tempus.ui.activity.MainActivity

/** Drift Lite: "Recommended for you", built from the user's Last.fm listening. */
class LiteForYouFragment : LiteFragment() {
    @Composable
    override fun Screen() {
        val ctx = LocalContext.current
        val user by LiteLastFm.account.collectAsState()
        var data by remember { mutableStateOf(LiteLastFm.cached()) }
        var loading by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        var refresh by remember { mutableIntStateOf(0) }
        var menuOpen by remember { mutableStateOf(false) }

        LaunchedEffect(user, refresh) {
            if (user == null) { data = null; return@LaunchedEffect }
            loading = data == null || refresh > 0
            failed = false
            val r = LiteLastFm.forYou(force = refresh > 0)
            if (r != null) data = r else failed = data == null
            loading = false
        }

        LazyColumn(Modifier.fillMaxSize().background(LBg), contentPadding = PaddingValues(bottom = 180.dp)) {
            item {
                Row(Modifier.statusBarsPadding().padding(start = 4.dp, top = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("←", color = LHi, fontSize = 26.sp, modifier = Modifier.clip(CircleShape)
                        .clickable { NavHostFragment.findNavController(this@LiteForYouFragment).navigateUp() }.padding(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.lite_fy_title), color = LHi, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                        if (user != null) Text(stringResource(R.string.lite_fy_from, user!!), color = LLo, fontSize = 13.sp)
                    }
                    if (user != null) Box {
                        Text("⋮", color = LHi, fontSize = 24.sp, modifier = Modifier.clip(CircleShape).clickable { menuOpen = true }.padding(horizontal = 16.dp, vertical = 8.dp))
                        DropdownMenu(menuOpen, { menuOpen = false }) {
                            var scrobble by remember { mutableStateOf(LiteLastFm.scrobbling) }
                            DropdownMenuItem(text = { Text(stringResource(if (scrobble) R.string.lite_fy_scrobble_off else R.string.lite_fy_scrobble_on)) }, onClick = {
                                LiteLastFm.scrobbling = !scrobble; scrobble = !scrobble; menuOpen = false
                                toast(ctx.getString(if (scrobble) R.string.lite_fy_scrobble_now_on else R.string.lite_fy_scrobble_now_off))
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.lite_fy_refresh)) }, onClick = { menuOpen = false; refresh++ })
                            DropdownMenuItem(text = { Text(stringResource(R.string.lite_fy_disconnect)) }, onClick = { menuOpen = false; LiteLastFm.logout() })
                        }
                    }
                }
            }
            if (user == null) {
                item { Connect() }
                return@LazyColumn
            }
            val d = data
            if (loading && d == null) { item { com.eddyizm.tempus.ui.components.DriftLoader(Modifier.fillMaxWidth().height(420.dp)) }; return@LazyColumn }
            if (failed || d == null) { item { Hint(stringResource(R.string.lite_fy_failed)) }; return@LazyColumn }
            if (d.tracks.isEmpty() && d.artists.isEmpty()) { item { Hint(stringResource(R.string.lite_fy_empty)) }; return@LazyColumn }
            if (d.tracks.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { Section(stringResource(R.string.lite_fy_tracks)) }
                        Box(Modifier.clip(RoundedCornerShape(50)).background(LAcc).clickable { play(d.tracks.map { it.id }, 0) }
                            .padding(horizontal = 16.dp, vertical = 8.dp)) { Text(stringResource(R.string.lite_play_button), color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    }
                }
                itemsIndexed(d.tracks) { i, t ->
                    var menu by remember { mutableStateOf(false) }
                    if (menu) LiteTrackMenu(t.id, actions, { menu = false })
                    TrackRow(t.title, t.artist, t.cover, t.duration, offline = LiteTracks.isDownloaded(t.id), onMenu = { menu = true }) { play(d.tracks.map { it.id }, i) }
                }
            }
            if (d.artists.isNotEmpty()) item {
                Section(stringResource(R.string.lite_fy_artists))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(d.artists) { a -> ArtistCircle(a.name, a.picture) { a.ref?.let { open(it, a.name, a.picture) } } }
                }
            }
        }
    }

    /** Not signed in: what Last.fm adds, and the button to connect. */
    @Composable
    private fun Connect() {
        val ctx = LocalContext.current
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.lite_fy_pitch), color = LHi, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(18.dp))
            listOf(R.string.lite_fy_point_tracks, R.string.lite_fy_point_artists, R.string.lite_fy_point_history).forEach { id ->
                Row(Modifier.padding(vertical = 7.dp)) {
                    Box(Modifier.padding(top = 7.dp).size(8.dp).clip(CircleShape).background(LAcc))
                    Text(stringResource(id), color = LLo, fontSize = 15.sp, modifier = Modifier.padding(start = 12.dp))
                }
            }
            Spacer(Modifier.height(26.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Color(0xFFD51007)).clickable { LiteLastFm.startLogin(ctx) }
                .padding(vertical = 15.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.lite_fy_connect), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Text(stringResource(R.string.lite_fy_join), color = LHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 14.dp).clip(RoundedCornerShape(50))
                    .clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LiteLastFm.JOIN_URL))) }.padding(horizontal = 14.dp, vertical = 8.dp))
            Text(stringResource(R.string.lite_fy_privacy), color = LLo, fontSize = 12.sp, modifier = Modifier.padding(top = 18.dp))
        }
    }
}

/** Home card that opens "Recommended for you" (hidden when the build has no Last.fm key). */
@Composable
fun LiteForYouCard(onOpen: () -> Unit) {
    if (!LiteLastFm.available) return
    val user by LiteLastFm.account.collectAsState()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp))
        .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFF3A1D5C), Color(0xFF7A1F3D))))
        .clickable(onClick = onOpen).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.lite_fy_title), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(if (user != null) R.string.lite_fy_card_connected else R.string.lite_fy_card_connect), color = Color(0xCCFFFFFF), fontSize = 13.sp)
        }
        Text("›", color = Color.White, fontSize = 28.sp)
    }
}

/** Last.fm sends the browser back to driftlite://lastfm?token=...: finish sign-in and return to the app. */
class LiteLastFmCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        val token = i?.data?.getQueryParameter("token")
        if (token.isNullOrBlank()) { finish(); return }
        Thread {
            val user = LiteLastFm.finishLogin(token)
            runOnUiThread {
                Toast.makeText(this, if (user != null) getString(R.string.lite_fy_connected_as, user) else getString(R.string.lite_fy_login_failed), Toast.LENGTH_LONG).show()
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }
        }.start()
    }
}
