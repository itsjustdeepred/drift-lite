package com.eddyizm.tempus.ui.components

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
import com.eddyizm.tempus.ui.activity.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SAcc get() = com.eddyizm.tempus.helper.DriftAccent.compose
private val SHi get() = DriftColors.text
private val SLo get() = DriftColors.textLo
private val SCard get() = DriftColors.card

/** A playlist, album or chart tile: cover (or a playlist icon), name and a short line under it. */
@kotlin.OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun SpotifyCard(e: DriftSpotify.Entry, modifier: Modifier = Modifier.width(150.dp), hasNew: Boolean = false, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(SCard)) {
            if (e.cover != null) GlideImage(e.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_playlist), null, tint = SLo,
                modifier = Modifier.align(Alignment.Center).size(44.dp))
            // Drift: "new tracks" dot on followed playlists
            if (hasNew) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(14.dp).clip(CircleShape)
                .background(DriftColors.bg).padding(2.dp).clip(CircleShape).background(SAcc))
        }
        Text(e.name, color = SHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp))
        Text(e.sub ?: pluralStringResource(R.plurals.lite_tracks, e.count, e.count), color = SLo, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
