package com.eddyizm.tempus.flavor

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import com.eddyizm.tempus.R
import com.eddyizm.tempus.lite.LiteApp
import com.eddyizm.tempus.lite.LiteCatalogSource
import com.eddyizm.tempus.lite.LiteCatalog
import com.eddyizm.tempus.lite.LiteLastFm
import com.eddyizm.tempus.lite.LiteLyrics
import com.eddyizm.tempus.lite.LitePlaylistPickerDialog
import com.eddyizm.tempus.lite.LiteStreamServer
import com.eddyizm.tempus.lite.LiteTracks
import com.eddyizm.tempus.lite.LiteYouTube
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.ui.fragment.DriftSpotifyPlaylistFragment
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * What the shared code asks of the build it runs in. Drift Lite: songs come from YouTube and are kept on the phone.
 * The full app has a class with the same name and functions that answers for a music server.
 */
object FlavorHooks {
    /** Songs that live on the phone (YouTube), not on a server. */
    @JvmStatic fun isPhoneTrack(id: String?): Boolean = LiteTracks.isLite(id)
    @JvmStatic fun phoneTrackUri(id: String): Uri? = LiteTracks.uri(id)
    @JvmStatic fun phoneTrack(id: String): Child? = LiteTracks.child(id)
    @JvmStatic fun isPhoneTrackLiked(id: String): Boolean = LiteTracks.isLiked(id)
    @JvmStatic fun setPhoneTrackLiked(id: String, liked: Boolean) = LiteTracks.setLiked(id, liked)

    /** URL a network speaker can play for a phone song (relayed by the phone). */
    @JvmStatic fun speakerUrl(id: String): String? = LiteStreamServer.urlFor(id)

    /** The player's source for phone songs: the real YouTube URL is resolved only when the track opens. */
    @JvmStatic fun mediaSource(item: MediaItem): MediaSource? {
        if (item.localConfiguration?.uri?.scheme != LiteTracks.SCHEME) return null
        val http = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent(LiteYouTube.UA).setAllowCrossProtocolRedirects(true)
        val resolving = androidx.media3.datasource.ResolvingDataSource.Factory(http) { spec -> spec.withUri(LiteTracks.resolve(spec.uri)) }
        return ProgressiveMediaSource.Factory(resolving, DefaultExtractorsFactory()).createMediaSource(item)
    }

    /** Synced lyrics straight from LRCLIB (blocking). */
    @JvmStatic fun fetchLyrics(id: String, title: String?, artist: String?, duration: Int): String? = LiteLyrics.fetch(id, title, artist, duration)

    /** Looks up the next song's stream ahead of time. */
    @JvmStatic fun prefetchNext(player: Player) = LiteTracks.prefetchNext(player)

    /** A song started (submission = false) or was played past halfway (true). */
    @JvmStatic fun onPlayed(item: MediaItem?, submission: Boolean) {
        LiteLastFm.scrobble(item, submission)
        if (submission && item != null) LiteTracks.countPlay(item.mediaId)
    }

    /** "Add to playlist": the playlists made on the phone. */
    @JvmStatic fun showPlaylistPicker(activity: androidx.fragment.app.FragmentActivity, songs: List<Child>) =
        LitePlaylistPickerDialog.show(activity, ArrayList(songs.map { it.id }))

    /** Artist page for a name (blocking): destination and arguments, or null if not found. */
    @JvmStatic fun artistPageByName(name: String): Pair<Int, Bundle>? {
        val hit = LiteCatalog.artistRefByName(name) ?: return null
        return R.id.driftSpotifyPlaylistFragment to DriftSpotifyPlaylistFragment.args(hit.first, hit.second.name, hit.second.picture)
    }

    /** Extra data saved in the settings backup file (followed playlists, saved songs). */
    @JvmStatic fun backupExtra(): Pair<String, JsonElement>? = "drift_lite" to LiteCatalog.backup()
    @JvmStatic fun restoreExtra(root: JsonObject) {
        runCatching { root.getAsJsonObject("drift_lite") }.getOrNull()?.let { LiteCatalog.restore(it) }
    }

    /** First-run setup finished. */
    @JvmStatic val onboarded: Boolean get() = LiteApp.onboarded

    /** Charts and editorial playlists. */
    @JvmStatic val catalog: com.eddyizm.tempus.helper.DriftSpotify.Source get() = LiteCatalogSource

    /** App start: no server and no login, so it goes straight to Home. Also checks for a new version. */
    @JvmStatic fun startWithoutLogin(activity: Activity): Boolean {
        LiteApp.prepare()
        com.eddyizm.tempus.helper.AppUpdater.check(activity)
        return true
    }
    /** Back in the app: an update waiting for the "install unknown apps" permission continues. */
    @JvmStatic fun onResume(activity: Activity) = com.eddyizm.tempus.helper.AppUpdater.resumePending(activity)
    /** After the daily refresh of followed playlists. */
    @JvmStatic fun afterDailySync(context: android.content.Context) = Unit

    /** Android Auto: no personal mixes without a server. */
    @JvmStatic fun autoMixesAvailable(): Boolean = false
    @JvmStatic fun autoMixes(): List<com.eddyizm.tempus.service.DriftAuto.Mix>? = null
    /** Android Auto: Liked, the playlists made on the phone, Offline. */
    @JvmStatic fun autoPhoneLists(): List<com.eddyizm.tempus.service.DriftAuto.PhoneList> {
        val ctx = com.eddyizm.tempus.App.getContext()
        return listOf(com.eddyizm.tempus.service.DriftAuto.PhoneList("liked", ctx.getString(R.string.lite_liked), null, R.drawable.ic_aa_star_title)) +
            com.eddyizm.tempus.lite.LitePlaylists.all().map {
                com.eddyizm.tempus.service.DriftAuto.PhoneList("pl:" + it.id, it.name,
                    ctx.resources.getQuantityString(R.plurals.lite_tracks, it.tracks.size, it.tracks.size), R.drawable.ic_aa_playlist)
            } +
            listOf(com.eddyizm.tempus.service.DriftAuto.PhoneList("offline", ctx.getString(R.string.lite_offline), null, R.drawable.ic_aa_downloaded))
    }
    /** Songs of a phone list (always current, no cache). */
    @JvmStatic fun autoPhoneListTracks(ref: String): List<Child>? {
        val ids = when {
            ref == "liked" -> LiteTracks.liked().map { it.id }
            ref == "offline" -> LiteTracks.downloaded().map { it.id }
            ref.startsWith("pl:") -> com.eddyizm.tempus.lite.LitePlaylists.get(ref.removePrefix("pl:"))?.tracks.orEmpty()
            else -> emptyList()
        }
        return ids.mapNotNull { LiteTracks.child(it) }
    }

    /** Songs from a server list with the user's exclusions applied: nothing to exclude here. */
    @JvmStatic fun filterSongs(songs: List<Child>?): List<Child>? = songs
    /** Waveform bars for the seek bar: none without Undertow. */
    @JvmStatic fun waveform(songId: String): IntArray? = null

    /** Server album and artist pages: none here (Drift Lite opens catalog pages instead). */
    @JvmStatic val albumPageId: Int get() = 0
    @JvmStatic val artistPageId: Int get() = 0
    /** No login screen. */
    @JvmStatic fun loginActionFrom(destinationId: Int): Int = 0
    @JvmStatic fun homeActionFrom(destinationId: Int): Int = if (destinationId == R.id.landingFragment) R.id.action_landingFragment_to_homeFragment else 0
    /** Albums and artists kept out of mixes: none. */
    @JvmStatic fun excludedAlbums(): Set<String> = emptySet()
    @JvmStatic fun excludedArtists(): Set<String> = emptySet()

    /** Settings for a server: none. */
    @JvmStatic fun setupServerSettings(fragment: androidx.preference.PreferenceFragmentCompat) = Unit
    /** "Edit your profile": first-run setup again. */
    @JvmStatic fun restartOnboarding() { LiteApp.onboarded = false }
    /** Links to server items (album, artist, playlist): not used without a server. */
    @JvmStatic fun openAssetLink(activity: com.eddyizm.tempus.ui.activity.MainActivity, link: com.eddyizm.tempus.util.AssetLinkUtil.AssetLink) = Unit
    /** Asks the companion server to find synced lyrics the music server lacks; true if there are some now (blocking). */
    @JvmStatic fun fetchServerLyrics(songId: String): Boolean = false

    /** The name shown on top of Settings (picked at first launch). */
    @JvmStatic var profileName: String?
        get() = LiteApp.name
        set(v) { LiteApp.name = v.orEmpty() }
    /** Settings: "Your music" card. */
    @androidx.compose.runtime.Composable fun SettingsMusicCard() = com.eddyizm.tempus.lite.LiteMusicCard()
}
