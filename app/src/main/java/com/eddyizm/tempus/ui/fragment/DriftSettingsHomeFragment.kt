package com.eddyizm.tempus.ui.fragment

import com.eddyizm.tempus.ui.components.DriftColors
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import com.eddyizm.tempus.App
import com.eddyizm.tempus.BuildConfig
import com.eddyizm.tempus.R
import com.eddyizm.tempus.util.Preferences

/**
 * Drift: Spotify-like settings entry. Two quick tiles at the top, then the categories with icon,
 * title and summary; each category reopens the existing settings screen showing only that section
 * (argument [ARG_CATEGORY] = category title, same string resource as the preference category).
 */
class DriftSettingsHomeFragment : Fragment() {

    companion object {
        const val ARG_CATEGORY = "drift_settings_category"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { SettingsHome(::open) }
        }

    private fun open(titleRes: Int) {
        val nav = NavHostFragment.findNavController(requireParentFragment())
        nav.navigate(R.id.settingsFragment, Bundle().apply { putString(ARG_CATEGORY, getString(titleRes)) })
    }
}

private data class Cat(val titleRes: Int, val summaryRes: Int, val icon: Int)
private data class Group(val labelRes: Int, val cats: List<Cat>)

// Same order as the settings sections; summaries list entries that really exist
private val groups = listOf(
    Group(R.string.ds_group_listening, buildList {
        add(Cat(R.string.ds_playback, R.string.ds_sum_playback, R.drawable.ds_play))
        add(Cat(R.string.ds_quality, R.string.ds_sum_quality, R.drawable.ds_hq))
        add(Cat(R.string.ds_sound, R.string.ds_sum_sound, R.drawable.ds_sound))
        add(Cat(R.string.ds_devices, R.string.ds_sum_devices, R.drawable.ic_drift_speaker))
        if (BuildConfig.FLAVOR == "tempus") add(Cat(R.string.ds_auto, R.string.ds_sum_auto, R.drawable.ds_car))
    }),
    Group(R.string.ds_group_library, listOf(
        Cat(R.string.ds_library, R.string.ds_sum_library, R.drawable.ds_library),
        Cat(R.string.ds_offline, R.string.ds_sum_offline, R.drawable.ds_download),
    )),
    Group(R.string.ds_group_app, listOf(
        Cat(R.string.ds_look, R.string.ds_sum_look, R.drawable.ds_palette),
        Cat(R.string.ds_privacy, R.string.ds_sum_privacy, R.drawable.ds_shield),
        Cat(R.string.ds_advanced, R.string.ds_sum_advanced, R.drawable.ds_tune),
        Cat(R.string.ds_about, R.string.ds_sum_about, R.drawable.ds_info),
    )),
)

// Drift Lite: only what is needed without a server
private val liteGroups = listOf(
    Group(R.string.ds_group_listening, listOf(
        Cat(R.string.ds_sound, R.string.ds_sum_lite_sound, R.drawable.ds_sound),
        Cat(R.string.ds_devices, R.string.ds_sum_lite_devices, R.drawable.ic_drift_speaker),
    )),
    Group(R.string.ds_group_app, listOf(
        Cat(R.string.ds_offline, R.string.ds_sum_lite_offline, R.drawable.ds_download),
        Cat(R.string.ds_look, R.string.ds_sum_lite_look, R.drawable.ds_palette),
        Cat(R.string.ds_advanced, R.string.ds_sum_lite_advanced, R.drawable.ds_tune),
    )),
)

private const val LITE_ETH = "0x601987B3ACfe5F48a8a8e4958d4949a43d007464"

private val SBgS get() = DriftColors.bg
private val SCardS get() = DriftColors.surface
private val SHiS get() = DriftColors.text
private val SLoS get() = DriftColors.textLo
private val SAccS get() = com.eddyizm.tempus.helper.DriftAccent.compose

@Composable
private fun SettingsHome(open: (Int) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun it_activity() = ctx as? android.app.Activity
    val prefs = App.getInstance().preferences
    var saver by remember { mutableStateOf(prefs.getBoolean("data_saving_mode", false)) }
    // "Private session" = no scrobbling to Navidrome/Last.fm
    var privateSession by remember { mutableStateOf(!Preferences.isScrobblingEnabled()) }
    val lite = BuildConfig.LITE
    var liteName by remember { mutableStateOf(com.eddyizm.tempus.lite.LiteApp.name) }
    var editName by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(liteName) }
    val user = if (lite) liteName else Preferences.getUser().orEmpty()
    if (editName) com.eddyizm.tempus.lite.NameDialog(stringResource(R.string.lite_your_name), draft, { draft = it }, stringResource(R.string.lite_save), { editName = false }) {
        com.eddyizm.tempus.lite.LiteApp.name = draft; liteName = draft.trim(); editName = false
    }

    LazyColumn(Modifier.fillMaxSize().background(SBgS).navigationBarsPadding(), contentPadding = PaddingValues(bottom = 180.dp)) {
        item {
            Text(stringResource(R.string.ds_settings), color = SHiS, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp))
        }
        // Account on top, as a card: who you are and on which server
        item {
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(SCardS).clickable { if (lite) { draft = liteName; editName = true } else open(R.string.ds_account) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(SAccS), contentAlignment = Alignment.Center) {
                    Text(user.take(1).uppercase().ifEmpty { "?" }, color = Color.Black, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(user.ifEmpty { stringResource(if (lite) R.string.lite_your_name else R.string.ds_account) }, color = SHiS, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    if (!lite) Text(Preferences.getServer().orEmpty().removePrefix("https://").removePrefix("http://"),
                        color = SLoS, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else Text(stringResource(R.string.lite_edit), color = SLoS, fontSize = 13.sp)
                }
                Image(painterResource(R.drawable.ds_chevron), null, colorFilter = ColorFilter.tint(SLoS), modifier = Modifier.size(22.dp))
            }
        }
        if (lite) item {
            // "Your music": taste picked at first launch, Home sections, Last.fm, as one card like the groups below
            var sectionsOpen by remember { mutableStateOf(false) }
            if (sectionsOpen) com.eddyizm.tempus.lite.LiteHomeSectionsDialog { sectionsOpen = false }
            val fm by com.eddyizm.tempus.lite.LiteLastFm.account.collectAsState()
            val view = androidx.compose.ui.platform.LocalView.current
            val rows = buildList<Triple<Int, Pair<String, String>, () -> Unit>> {
                add(Triple(R.drawable.ds_note, stringResource(R.string.lite_genres_and_artists) to stringResource(R.string.lite_genres_and_artists_sub)) {
                    com.eddyizm.tempus.lite.LiteApp.onboarded = false; (it_activity())?.recreate(); Unit
                })
                add(Triple(R.drawable.ds_grid, stringResource(R.string.lite_home_sections) to stringResource(R.string.lite_home_sections_sub)) { sectionsOpen = true })
                if (com.eddyizm.tempus.lite.LiteLastFm.available) add(Triple(R.drawable.ds_radio,
                    "Last.fm" to (if (fm != null) stringResource(R.string.lite_fy_connected_as, fm!!) else stringResource(R.string.lite_lastfm_sub))) {
                    runCatching { androidx.navigation.Navigation.findNavController(view).navigate(R.id.liteForYouFragment) }; Unit
                })
            }
            Text(stringResource(R.string.lite_your_music), color = com.eddyizm.tempus.helper.DriftAccent.composeText, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 8.dp))
            Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SCardS)) {
                rows.forEachIndexed { i, (icon, texts, action) ->
                    if (i > 0) Box(Modifier.padding(start = 58.dp).fillMaxWidth().height(1.dp).background(DriftColors.divider))
                    Row(Modifier.fillMaxWidth().clickable(onClick = action).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(icon), null, colorFilter = ColorFilter.tint(SHiS), modifier = Modifier.size(24.dp))
                        Column(Modifier.padding(start = 18.dp).weight(1f)) {
                            Text(texts.first, color = SHiS, fontSize = 16.sp)
                            Text(texts.second, color = SLoS, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                        Image(painterResource(R.drawable.ds_chevron), null, colorFilter = ColorFilter.tint(SLoS), modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        if (!lite) item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickTile(stringResource(R.string.ds_data_saver), stringResource(if (saver) R.string.ds_data_saver_on else R.string.ds_off), saver, R.drawable.ds_datasaver, Modifier.weight(1f)) {
                    saver = !saver; prefs.edit().putBoolean("data_saving_mode", saver).apply()
                }
                QuickTile(stringResource(R.string.ds_private_session), stringResource(if (privateSession) R.string.ds_private_session_on else R.string.ds_off), privateSession, R.drawable.ds_private, Modifier.weight(1f)) {
                    privateSession = !privateSession; prefs.edit().putBoolean("scrobbling", !privateSession).apply()
                }
            }
        }
        (if (lite) liteGroups else groups).forEach { g ->
            item {
                Text(stringResource(g.labelRes), color = com.eddyizm.tempus.helper.DriftAccent.composeText, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 8.dp))
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SCardS)) {
                    g.cats.forEachIndexed { i, c ->
                        if (i > 0) Box(Modifier.padding(start = 58.dp).fillMaxWidth().height(1.dp).background(DriftColors.divider))
                        Row(
                            Modifier.fillMaxWidth().clickable { open(c.titleRes) }.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(painterResource(c.icon), null, colorFilter = ColorFilter.tint(SHiS), modifier = Modifier.size(24.dp))
                            Column(Modifier.padding(start = 18.dp).weight(1f)) {
                                Text(stringResource(c.titleRes), color = SHiS, fontSize = 16.sp)
                                Text(stringResource(c.summaryRes), color = SLoS, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp))
                            }
                            Image(painterResource(R.drawable.ds_chevron), null, colorFilter = ColorFilter.tint(SLoS), modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
        if (lite) item {
            Text(stringResource(R.string.lite_info), color = com.eddyizm.tempus.helper.DriftAccent.composeText, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 8.dp))
            Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SCardS).padding(16.dp)) {
                Text("Drift Lite " + BuildConfig.VERSION_NAME, color = SHiS, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                listOf(
                    stringResource(R.string.lite_info_audio) to "YouTube Music",
                    stringResource(R.string.lite_info_catalog) to stringResource(R.string.lite_info_catalog_sources),
                    stringResource(R.string.lite_info_lyrics) to "LRCLIB",
                    stringResource(R.string.lite_info_server) to stringResource(R.string.lite_info_none),
                    stringResource(R.string.lite_info_data) to stringResource(R.string.lite_info_data_local),
                ).forEach { (k, v) ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(k, color = SLoS, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(v, color = SHiS, fontSize = 14.sp)
                    }
                }
                val act = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.lite_update_check), color = com.eddyizm.tempus.helper.DriftAccent.composeText, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { act?.let { com.eddyizm.tempus.lite.LiteUpdater.check(it, manual = true) } }
                        .padding(vertical = 6.dp))
            }
            // Donations: one ETH address, copied with a tap
            val ctx = androidx.compose.ui.platform.LocalContext.current
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SCardS)
                .clickable {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("ETH", LITE_ETH))
                    android.widget.Toast.makeText(ctx, R.string.lite_support_copied, android.widget.Toast.LENGTH_SHORT).show()
                }.padding(16.dp)) {
                Text(stringResource(R.string.lite_support), color = SHiS, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.lite_support_text), color = SLoS, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                Text("ETH", color = com.eddyizm.tempus.helper.DriftAccent.composeText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(LITE_ETH, color = SHiS, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }
        if (!lite) item {
            Spacer(Modifier.height(16.dp))
            Text((if (lite) "Drift Lite " else "Drift ") + BuildConfig.VERSION_NAME, color = SLoS, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp))
        }
    }
}

@Composable
private fun QuickTile(title: String, state: String, on: Boolean, icon: Int, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(86.dp).clip(RoundedCornerShape(8.dp)).background(DriftColors.card)
            .border(1.dp, if (on) SAccS else DriftColors.border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Image(painterResource(icon), null, colorFilter = ColorFilter.tint(if (on) SAccS else SHiS), modifier = Modifier.size(22.dp))
        Text(title, color = SHiS, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        Text(state, color = if (on) com.eddyizm.tempus.helper.DriftAccent.composeText else SLoS, fontSize = 11.sp, maxLines = 1)
    }
}
