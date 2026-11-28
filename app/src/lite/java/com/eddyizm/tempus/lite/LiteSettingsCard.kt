package com.eddyizm.tempus.lite

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eddyizm.tempus.R
import com.eddyizm.tempus.ui.components.DriftColors

/** Settings: "Your music", the taste picked at first launch, Home sections and Last.fm, as one card. */
@Composable
fun LiteMusicCard() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
        // "Your music": taste picked at first launch, Home sections, Last.fm, as one card like the groups below
        var sectionsOpen by remember { mutableStateOf(false) }
        if (sectionsOpen) com.eddyizm.tempus.lite.LiteHomeSectionsDialog { sectionsOpen = false }
        val fm by com.eddyizm.tempus.lite.LiteLastFm.account.collectAsState()
        val view = androidx.compose.ui.platform.LocalView.current
        val rows = buildList<Triple<Int, Pair<String, String>, () -> Unit>> {
            add(Triple(R.drawable.ds_note, stringResource(R.string.lite_genres_and_artists) to stringResource(R.string.lite_genres_and_artists_sub)) {
                com.eddyizm.tempus.lite.LiteApp.onboarded = false; (ctx as? android.app.Activity)?.recreate(); Unit
            })
            add(Triple(R.drawable.ds_grid, stringResource(R.string.lite_home_sections) to stringResource(R.string.lite_home_sections_sub)) { sectionsOpen = true })
            if (com.eddyizm.tempus.lite.LiteLastFm.available) add(Triple(R.drawable.ds_radio,
                "Last.fm" to (if (fm != null) stringResource(R.string.lite_fy_connected_as, fm!!) else stringResource(R.string.lite_lastfm_sub))) {
                runCatching { androidx.navigation.Navigation.findNavController(view).navigate(R.id.liteForYouFragment) }; Unit
            })
        }
        Text(stringResource(R.string.lite_your_music), color = com.eddyizm.tempus.helper.DriftAccent.composeText, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 8.dp))
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(DriftColors.surface)) {
            rows.forEachIndexed { i, (icon, texts, action) ->
                if (i > 0) Box(Modifier.padding(start = 58.dp).fillMaxWidth().height(1.dp).background(DriftColors.divider))
                Row(Modifier.fillMaxWidth().clickable(onClick = action).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(icon), null, colorFilter = ColorFilter.tint(DriftColors.text), modifier = Modifier.size(24.dp))
                    Column(Modifier.padding(start = 18.dp).weight(1f)) {
                        Text(texts.first, color = DriftColors.text, fontSize = 16.sp)
                        Text(texts.second, color = DriftColors.textLo, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                    Image(painterResource(R.drawable.ds_chevron), null, colorFilter = ColorFilter.tint(DriftColors.textLo), modifier = Modifier.size(20.dp))
                }
            }
        }
}
