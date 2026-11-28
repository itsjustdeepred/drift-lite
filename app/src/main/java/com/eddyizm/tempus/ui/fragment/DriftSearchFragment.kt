package com.eddyizm.tempus.ui.fragment

import com.eddyizm.tempus.ui.components.DriftColors
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.OptIn
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.navigation.fragment.NavHostFragment
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.eddyizm.tempus.App
import com.eddyizm.tempus.BuildConfig
import com.eddyizm.tempus.R
import com.eddyizm.tempus.glide.CustomGlideRequest
import com.eddyizm.tempus.service.MediaManager
import com.eddyizm.tempus.service.MediaService
import com.eddyizm.tempus.subsonic.models.AlbumID3
import com.eddyizm.tempus.subsonic.models.ArtistID3
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.subsonic.models.Genre
import com.eddyizm.tempus.ui.activity.MainActivity
import com.eddyizm.tempus.util.Constants
import com.google.common.util.concurrent.ListenableFuture
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * Drift: Search. Empty = "Browse all" with a genre grid; with text = library results
 * + "Not in your library" from Undertow (Deezer) with the Download button.
 */
@OptIn(UnstableApi::class)
class DriftSearchFragment : Fragment() {

    private var browser: ListenableFuture<MediaBrowser>? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { SearchScreen(actions) }
        }

    override fun onStart() {
        super.onStart()
        (activity as? MainActivity)?.let {
            it.setBottomNavigationBarVisibility(true)
            it.setBottomSheetVisibility(true)
        }
        browser = MediaBrowser.Builder(
            requireContext(), SessionToken(requireContext(), ComponentName(requireContext(), MediaService::class.java))
        ).buildAsync()
    }

    override fun onStop() {
        browser?.let { MediaBrowser.releaseFuture(it) }
        super.onStop()
    }

    private fun nav(dest: Int, b: Bundle) = NavHostFragment.findNavController(this).navigate(dest, b)

    // Drift: microphone permission for "Recognize song"
    private var micAnswer: ((Boolean, Boolean) -> Unit)? = null
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val blocked = !granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        micAnswer?.invoke(granted, blocked)
        micAnswer = null
    }

    private val actions by lazy {
        SearchActions(
            openAlbum = { nav(R.id.albumPageFragment, Bundle().apply { putParcelable(Constants.ALBUM_OBJECT, it) }) },
            openArtist = { nav(R.id.artistPageFragment, Bundle().apply { putParcelable(Constants.ARTIST_OBJECT, it) }) },
            openGenre = {
                nav(R.id.songListPageFragment, Bundle().apply {
                    putString(Constants.MEDIA_BY_GENRE, Constants.MEDIA_BY_GENRE)
                    putParcelable(Constants.GENRE_OBJECT, it)
                })
            },
            play = { songs, i ->
                browser?.let { b ->
                    MediaManager.startQueue(b, songs, i)
                    (activity as? MainActivity)?.setBottomSheetInPeek(true)
                }
            },
            toast = { Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show() },
            openRemoteAlbum = { id, t, a, c -> nav(R.id.driftRemoteAlbumFragment, DriftRemoteAlbumFragment.args(id, t, a, c)) },
            enqueue = { song -> browser?.let { b -> MediaManager.enqueue(b, song, false) } },
            hasMic = { ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
            askMic = { answer -> micAnswer = answer; micPermission.launch(Manifest.permission.RECORD_AUDIO) },
            openAppSettings = {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", requireContext().packageName, null)))
            },
            // coming from home with "Recognize": starts right away, only once
            takeAutoRecognize = { arguments?.getBoolean(ARG_RECOGNIZE) == true && run { arguments?.remove(ARG_RECOGNIZE); true } },
        )
    }

    companion object {
        /** Drift: argument to open Search straight into recognition (from home). */
        const val ARG_RECOGNIZE = "drift_recognize"
    }
}

class SearchActions(
    val openAlbum: (AlbumID3) -> Unit,
    val openArtist: (ArtistID3) -> Unit,
    val openGenre: (Genre) -> Unit,
    val play: (List<Child>, Int) -> Unit,
    val toast: (String) -> Unit,
    val openRemoteAlbum: (Long, String, String, String?) -> Unit,
    val enqueue: (Child) -> Unit,
    val hasMic: () -> Boolean,
    val askMic: ((Boolean, Boolean) -> Unit) -> Unit,
    val openAppSettings: () -> Unit,
    val takeAutoRecognize: () -> Boolean,
)

// ------------------------------------------------------------------ data

private data class LibResult(val artists: List<ArtistID3>, val albums: List<AlbumID3>, val songs: List<Child>)

private data class RemoteTrack(
    val id: Long, val title: String, val artist: String, val album: String, val albumId: Long, val cover: String?,
)

private data class RemoteAlbum(val id: Long, val title: String, val artist: String, val tracks: Int, val type: String?, val cover: String?)

private data class Job(val id: String, val mode: String, val deezerId: Long, val state: String, val detail: String, val quality: String?, val progress: Float? = null, val code: String? = null)

private object Sidecar {
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()
    val enabled get() = com.eddyizm.tempus.helper.Undertow.enabled

    private fun req(path: String) = com.eddyizm.tempus.helper.Undertow.req(path)

    private fun call(r: Request): String = http.newCall(r).execute().use {
        if (!it.isSuccessful) error("HTTP ${it.code}")
        it.body.string()
    }

    fun search(q: String): Pair<List<RemoteTrack>, List<RemoteAlbum>> {
        val o = JsonParser.parseString(call(req("/search?limit=20&q=" + java.net.URLEncoder.encode(q, "UTF-8")).build())).asJsonObject
        val tracks = o.getAsJsonArray("tracks").map { it.asJsonObject }.map {
            RemoteTrack(it["deezer_id"].asLong, it["title"].asString, it["artist"].asString, it["album"].asString,
                it["album_id"].asLong, it["cover"]?.takeIf { c -> !c.isJsonNull }?.asString)
        }
        val albums = o.getAsJsonArray("albums").map { it.asJsonObject }.map {
            RemoteAlbum(it["deezer_id"].asLong, it["title"].asString, it["artist"].asString,
                it["tracks"]?.takeIf { t -> !t.isJsonNull }?.asInt ?: 0,
                it["type"]?.takeIf { t -> !t.isJsonNull }?.asString,
                it["cover"]?.takeIf { c -> !c.isJsonNull }?.asString)
        }
        return tracks to albums
    }

    fun download(mode: String, deezerId: Long): Job {
        val body = """{"mode":"$mode","deezer_id":$deezerId}""".toRequestBody("application/json".toMediaType())
        return parseJob(JsonParser.parseString(call(req("/download").post(body).build())).asJsonObject)
    }

    fun jobs(): List<Job> = JsonParser.parseString(call(req("/jobs").build())).asJsonArray.map { parseJob(it.asJsonObject) }

    private fun parseJob(j: com.google.gson.JsonObject) = Job(
        j["id"].asString, j["mode"].asString, j["deezer_id"].asLong, j["state"].asString,
        j["detail"]?.takeIf { !it.isJsonNull }?.asString ?: "",
        j["quality"]?.takeIf { !it.isJsonNull }?.asString,
        j["progress"]?.takeIf { !it.isJsonNull }?.asFloat,
        j["code"]?.takeIf { !it.isJsonNull }?.asString,
    )
}

private fun norm(s: String?): String =
    Normalizer.normalize(s ?: "", Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()
        .replace(Regex("\\((feat|ft|with)[^)]*\\)|\\[[^]]*]"), " ")
        .replace(Regex("[^a-z0-9]+"), " ").trim()

private fun libSearch(q: String): LibResult {
    val r = App.getSubsonicClientInstance(false).searchingClient.search3(q, 20, 0, 12, 0, 8, 0).execute()
    val s = r.body()?.subsonicResponse?.searchResult3
    return LibResult(s?.artists.orEmpty(), s?.albums.orEmpty(), s?.songs.orEmpty())
}

private fun libGenres(): List<Genre> {
    val r = App.getSubsonicClientInstance(false).browsingClient.genres.execute()
    return r.body()?.subsonicResponse?.genres?.genres.orEmpty().sortedByDescending { it.songCount }
}

// ------------------------------------------------------------------ ui

private val SBg get() = DriftColors.bg
private val SAccent get() = com.eddyizm.tempus.helper.DriftAccent.compose
private val SHi get() = DriftColors.text
private val SLo get() = DriftColors.textLo
private val genreColors = listOf(
    0xFFE13300, 0xFF1E3264, 0xFF8D67AB, 0xFF148A08, 0xFFBA5D07, 0xFF503750, 0xFF0D73EC, 0xFFE8115B,
    0xFF27856A, 0xFF8C1932, 0xFF477D95, 0xFFAF2896, 0xFF509BF5, 0xFF7358FF, 0xFFD84000, 0xFF283EA8,
).map { Color(it) }

private fun active(state: String) = state in setOf("queued", "searching", "downloading", "upgrading")

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScreen(actions: SearchActions) {
    var query by rememberSaveable { mutableStateOf("") }
    var genres by remember { mutableStateOf<List<Genre>>(emptyList()) }
    var lib by remember { mutableStateOf<LibResult?>(null) }
    var remoteTracks by remember { mutableStateOf<List<RemoteTrack>>(emptyList()) }
    var remoteAlbums by remember { mutableStateOf<List<RemoteAlbum>>(emptyList()) }
    var remoteError by remember { mutableStateOf<String?>(null) }
    val jobs = remember { mutableStateMapOf<Long, Job>() }   // deezerId -> job
    var sheetFor by remember { mutableStateOf<RemoteTrack?>(null) }
    var refreshTick by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val ctx = LocalContext.current
    var recognizeRequests by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { if (actions.takeAutoRecognize()) recognizeRequests++ }

    LaunchedEffect(Unit) {
        genres = runCatching { withContext(Dispatchers.IO) { libGenres() } }.getOrDefault(emptyList())
    }

    LaunchedEffect(query, refreshTick) {
        val q = query.trim()
        if (q.length < 2) { lib = null; remoteTracks = emptyList(); remoteAlbums = emptyList(); remoteError = null; return@LaunchedEffect }
        delay(350)
        launch {
            lib = runCatching { withContext(Dispatchers.IO) { libSearch(q) } }.getOrNull()
        }
        if (Sidecar.enabled) launch {
            runCatching { withContext(Dispatchers.IO) { Sidecar.search(q) } }
                .onSuccess { (t, a) -> remoteTracks = t; remoteAlbums = a; remoteError = null }
                .onFailure { remoteError = withContext(Dispatchers.IO) { com.eddyizm.tempus.helper.Undertow.diagnose(com.eddyizm.tempus.R.string.drift_what_download_results) } }
        }
    }

    // polling of active jobs
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(jobs.values.count { active(it.state) }, lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
        while (jobs.values.any { active(it.state) }) {
            delay(3000)
            runCatching { withContext(Dispatchers.IO) { Sidecar.jobs() } }.onSuccess { all ->
                val mine = jobs.keys.toSet()
                all.filter { it.deezerId in mine }.groupBy { it.deezerId }.forEach { (k, v) ->
                    val newest = v.first()
                    val was = jobs[k]
                    jobs[k] = newest
                    if (was != null && was.state != newest.state && newest.state in setOf("upgrading", "done")) {
                        // the file is in the library: Navidrome sees it in ~30-60 s, search again
                        scope.launch { delay(40_000); refreshTick++ }
                    }
                }
            }
        }
        }
    }

    fun startDownload(mode: String, id: Long, key: Long = id) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { Sidecar.download(mode, id) } }
                .onSuccess { jobs[key] = it; actions.toast(ctx.getString(if (mode == "album") R.string.drift_search_album_requested else R.string.lite_download_started)) }
                .onFailure { actions.toast(ctx.getString(R.string.drift_error_detail, it.message.orEmpty())) }
        }
    }

    // remote songs already in the library -> hidden
    val libKeys = lib?.songs.orEmpty().map { norm(it.title) + "|" + norm(it.artist) }.toSet()
    val missing = remoteTracks.filter { norm(it.title) + "|" + norm(it.artist) !in libKeys }
        .distinctBy { norm(it.title) + "|" + norm(it.artist) }
    val libAlbumKeys = lib?.albums.orEmpty().map { norm(it.name) }.toSet()
    val missingAlbums = remoteAlbums.filter { norm(it.title) !in libAlbumKeys }

    Box(Modifier.fillMaxSize().background(SBg)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 180.dp)) {
            item {
                Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.lite_search), color = SHi, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(top = 16.dp, bottom = 14.dp))
                    SearchField(query, onChange = { query = it }, onClear = { query = ""; focus.clearFocus() },
                        onMic = { focus.clearFocus(); recognizeRequests++ })
                }
            }

            if (query.trim().length < 2) {
                item {
                    Text(stringResource(R.string.drift_search_browse_all), color = SHi, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 16.dp, top = 26.dp, bottom = 12.dp))
                }
                items(genres.take(40).chunked(2)) { row ->
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { g ->
                            GenreTile(g, genreColors[(genres.indexOf(g)).mod(genreColors.size)], Modifier.weight(1f)) { actions.openGenre(g) }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                val l = lib
                if (l != null && l.artists.isNotEmpty()) item {
                    Header(stringResource(R.string.lite_artists))
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(l.artists) { a ->
                            Column(Modifier.width(96.dp).clickable { actions.openArtist(a) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                LibCover(a.coverArtId, Modifier.size(96.dp).clip(CircleShape))
                                Text(a.name ?: "", color = SHi, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                }
                if (l != null && l.songs.isNotEmpty()) {
                    item { Header(stringResource(R.string.lite_songs)) }
                    items(l.songs.take(8)) { s ->
                        val idx = l.songs.indexOf(s)
                        TrackRow(cover = { LibCover(s.coverArtId, it) }, title = s.title ?: "", sub = "${s.artist ?: ""} · ${s.album ?: ""}",
                            onClick = { actions.play(l.songs, idx) })
                    }
                }
                if (l != null && l.albums.isNotEmpty()) item {
                    Header(stringResource(R.string.lite_albums))
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(l.albums) { a ->
                            Column(Modifier.width(130.dp).clickable { actions.openAlbum(a) }) {
                                LibCover(a.coverArtId, Modifier.size(130.dp).clip(RoundedCornerShape(6.dp)))
                                Text(a.name ?: "", color = SHi, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                                Text(a.artist ?: "", color = SLo, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (l != null && l.artists.isEmpty() && l.songs.isEmpty() && l.albums.isEmpty()) item {
                    Text(stringResource(R.string.drift_search_nothing_in_library, query.trim()), color = SLo, fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp))
                }

                // ------- not in library
                if (missing.isNotEmpty() || missingAlbums.isNotEmpty() || remoteError != null) item {
                    Column(Modifier.padding(top = 26.dp)) {
                        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(SAccent))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.drift_search_not_in_library), color = SHi, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                        }
                        Text(remoteError ?: stringResource(R.string.drift_search_remote_hint),
                            color = SLo, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp))
                    }
                }
                items(missing.take(12)) { t ->
                    val job = jobs[t.id] ?: jobs[t.albumId]
                    TrackRow(
                        cover = { RemoteCover(t.cover, it) },
                        title = t.title, sub = "${t.artist} · ${t.album}",
                        status = job?.let { statusText(ctx, it) },
                        bar = job?.takeIf { active(it.state) }?.let { j -> { com.eddyizm.tempus.ui.components.DriftDownloadBar(j.progress) } },
                        trailing = {
                            if (job == null) DownloadPill { sheetFor = t }
                            else if (active(job.state)) Text("…", color = SAccent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            else if (job.state == "done") Text("✓", color = SAccent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            else DownloadPill { sheetFor = t }
                        },
                        onClick = { if (job == null) sheetFor = t },
                    )
                }
                if (missingAlbums.isNotEmpty()) item {
                    Header(stringResource(R.string.drift_search_albums_to_download))
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(missingAlbums) { a ->
                            val job = jobs[a.id]
                            Column(Modifier.width(140.dp).clickable { actions.openRemoteAlbum(a.id, a.title, a.artist, a.cover) }) {
                                Box {
                                    RemoteCover(a.cover, Modifier.size(140.dp).clip(RoundedCornerShape(6.dp)))
                                    Box(Modifier.align(Alignment.BottomEnd).padding(6.dp)) {
                                        if (job == null) DownloadPill(small = true) { startDownload("album", a.id) }
                                        else Text(if (job.state == "failed") "!" else "✓", color = SAccent, fontWeight = FontWeight.Bold,
                                            modifier = Modifier.clip(CircleShape).background(Color(0xCC000000)).padding(horizontal = 8.dp, vertical = 2.dp))
                                    }
                                }
                                Text(a.title, color = SHi, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                                Text(listOfNotNull(a.artist, a.type?.replaceFirstChar { it.uppercase() }, pluralStringResource(R.plurals.lite_tracks, a.tracks, a.tracks)).joinToString(" · "),
                                    color = SLo, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                job?.let { Text(statusText(ctx, it), color = SAccent, fontSize = 11.sp, maxLines = 2) }
                                job?.takeIf { active(it.state) }?.let { com.eddyizm.tempus.ui.components.DriftDownloadBar(it.progress) }
                            }
                        }
                    }
                }
            }
        }
    }

    RecognizeHost(recognizeRequests, RecognizeHooks(
        hasMic = actions.hasMic, askMic = actions.askMic, openAppSettings = actions.openAppSettings,
        play = { actions.play(listOf(it), 0) }, enqueue = { actions.enqueue(it); actions.toast(ctx.getString(R.string.lite_added_to_queue)) },
        download = { startDownload("track", it) }, search = { query = it }, toast = actions.toast,
    ))

    sheetFor?.let { t ->
        ModalBottomSheet(onDismissRequest = { sheetFor = null }, containerColor = DriftColors.sheet) {
            Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RemoteCover(t.cover, Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)))
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(t.title, color = SHi, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${t.artist} · ${t.album}", color = SLo, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(18.dp))
                SheetOption(stringResource(R.string.drift_search_opt_track), stringResource(R.string.drift_search_opt_track_sub)) {
                    startDownload("track", t.id); sheetFor = null
                }
                SheetOption(stringResource(R.string.drift_search_opt_album, t.album), stringResource(R.string.drift_search_opt_album_sub)) {
                    startDownload("album", t.albumId); sheetFor = null
                }
            }
        }
    }
}

private fun statusText(ctx: Context, j: Job): String =
    com.eddyizm.tempus.helper.DriftMixes.codeText(j.code, j.state, j.mode, j.progress, j.quality) ?: when (j.state) {
        "queued" -> ctx.getString(R.string.drift_search_job_queued)
        "searching" -> ctx.getString(R.string.drift_search_job_searching)
        "downloading" -> ctx.getString(R.string.drift_search_job_downloading) + (j.progress?.let { " · ${it.toInt()}%" } ?: "")
        "upgrading" -> ctx.getString(R.string.drift_search_job_upgrading, j.quality ?: "YouTube")
        "done" -> ctx.getString(R.string.drift_search_job_done, j.quality ?: "").trimEnd(' ', '·')
        "failed" -> ctx.getString(R.string.drift_search_job_failed)
        else -> j.state
    }

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, onClear: () -> Unit, onMic: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(8.dp)).background(if (DriftColors.dark) Color.White else DriftColors.cardHi).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(R.drawable.ic_search), null, colorFilter = ColorFilter.tint(Color(0xFF121212)), modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(stringResource(R.string.drift_search_hint), color = Color(0xFF535353), fontSize = 16.sp, fontWeight = FontWeight.Medium)
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(color = Color(0xFF121212), fontSize = 16.sp, fontWeight = FontWeight.Medium),
                cursorBrush = SolidColor(Color(0xFF121212)), modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) Text("✕", color = Color(0xFF121212), fontSize = 18.sp, modifier = Modifier.clickable(onClick = onClear).padding(6.dp))
        // Drift: microphone to recognize the song playing nearby
        else if (onMic != null) RecognizeMicButton(Color(0xFF121212), onMic)
    }
}

@Composable
private fun GenreTile(g: Genre, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(96.dp).clip(RoundedCornerShape(8.dp))
            .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.75f))))
            .clickable(onClick = onClick),
    ) {
        Text(g.genre ?: "", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(12.dp).fillMaxWidth(0.75f))
        Box(
            Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 4.dp).size(56.dp).rotate(25f)
                .clip(RoundedCornerShape(4.dp)).background(Color(0x33000000)),
            contentAlignment = Alignment.Center,
        ) { Text("${g.albumCount}", color = Color(0xCCFFFFFF), fontSize = 13.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun Header(t: String) {
    Text(t, color = SHi, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 10.dp))
}

@Composable
private fun TrackRow(
    cover: @Composable (Modifier) -> Unit, title: String, sub: String, status: String? = null,
    trailing: (@Composable () -> Unit)? = null, bar: (@Composable () -> Unit)? = null, onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cover(Modifier.size(52.dp).clip(RoundedCornerShape(4.dp)))
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            Text(title, color = SHi, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = SLo, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            status?.let { Text(it, color = SAccent, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            bar?.invoke()
        }
        trailing?.invoke()
    }
}

@Composable
private fun DownloadPill(small: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (small) Color(0xE6000000) else Color.Transparent)
            .border(1.dp, SAccent, RoundedCornerShape(50)).clickable(onClick = onClick)
            .padding(horizontal = if (small) 10.dp else 14.dp, vertical = if (small) 4.dp else 6.dp),
    ) { Text(stringResource(R.string.lite_download), color = SAccent, fontSize = if (small) 12.sp else 13.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun SheetOption(title: String, sub: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp))
            .background(DriftColors.glass).border(1.dp, DriftColors.border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(16.dp),
    ) {
        Text(title, color = SHi, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(sub, color = SLo, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@kotlin.OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun LibCover(id: String?, modifier: Modifier) {
    Box(modifier.background(DriftColors.card)) {
        if (id != null) GlideImage(CustomGlideRequest.createUrl(id, 300), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@kotlin.OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun RemoteCover(url: String?, modifier: Modifier) {
    Box(modifier.background(DriftColors.card)) {
        if (url != null) GlideImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}
