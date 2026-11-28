package com.eddyizm.tempus.service

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import com.eddyizm.tempus.App
import com.eddyizm.tempus.BuildConfig
import com.eddyizm.tempus.R
import com.eddyizm.tempus.flavor.FlavorHooks
import com.eddyizm.tempus.helper.DriftSpotify
import com.eddyizm.tempus.repository.AutomotiveRepository
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.util.ConstantsAA
import com.eddyizm.tempus.util.MappingUtil
import com.eddyizm.tempus.util.Preferences
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Drift: Undertow content in Android Auto. "For you" mixes, followed playlists and charts become
 * playlist folders; each playlist shows the tracks already in the library, mapped like the rest of AA
 * (MappingUtil + session metadata), so covers, stream and queue are identical.
 * Everything runs off the main thread and results stay in memory for a while: in the car you browse
 * without waiting, and if Undertow does not answer the last good result or an empty folder is shown.
 */
@UnstableApi
object DriftAuto {
    private const val TAG = "DriftAuto"

    // Drift: folders (dynamic children)
    const val MIXES_ID = "[driftMixesID]"
    const val FOLLOWED_ID = "[driftFollowedID]"
    const val CHARTS_ID = "[driftChartsID]"
    // Drift Lite: its own tabs in the car (no Navidrome library)
    const val LITE_HOME_ID = "[liteHomeID]"
    const val LITE_PLAYLISTS_ID = "[litePlaylistsID]"

    // Drift: browsable and playable playlists (mediaId = prefix + mix key or Undertow playlist id)
    const val MIX_PREFIX = "[driftMix]"
    const val LIST_PREFIX = "[driftList]"

    private const val LISTS_TTL_MS = 60 * 60 * 1000L   // mixes and followed playlists change at most once a day
    private const val TRACKS_TTL_MS = 30 * 60 * 1000L  // library tracks may arrive meanwhile (downloads)
    private const val MAX_TRACKS = 300                  // one getSong per track: beyond this it gets slow in the car

    private val executor = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(3))

    private class Memo(val future: ListenableFuture<Any?>, val at: Long)
    private val memo = ConcurrentHashMap<String, Memo>()
    private val lastGood = ConcurrentHashMap<String, Any>()

    /** A personal mix (Drift: Undertow's "Made for you"). */
    data class Mix(val key: String, val title: String, val subtitle: String, val trackIds: List<String>)
    /** A list kept on the phone (Drift Lite: Liked, your playlists, Offline). */
    data class PhoneList(val ref: String, val title: String, val subtitle: String?, val icon: Int)

    val enabled get() = FlavorHooks.autoMixesAvailable() || BuildConfig.LITE

    fun mixesEnabled() = FlavorHooks.autoMixesAvailable() && Preferences.isAndroidAutoDriftMixesEnabled()
    fun followedEnabled() = enabled && Preferences.isAndroidAutoDriftFollowedEnabled()
    fun chartsEnabled() = enabled && Preferences.isAndroidAutoDriftChartsEnabled()

    fun isPlaylistNode(mediaId: String) = mediaId.startsWith(MIX_PREFIX) || mediaId.startsWith(LIST_PREFIX)

    fun isFolder(mediaId: String) = mediaId == MIXES_ID || mediaId == FOLLOWED_ID || mediaId == CHARTS_ID ||
        mediaId == LITE_HOME_ID || mediaId == LITE_PLAYLISTS_ID

    /**
     * Result shared per key: reused while fresh (even while still loading), reloaded when it expires
     * or when the last attempt failed. If loading fails it falls back to the last good value.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> cached(key: String, ttl: Long, load: () -> T?): ListenableFuture<T?> {
        val now = SystemClock.elapsedRealtime()
        memo[key]?.let { m ->
            val fresh = now - m.at < ttl
            val ok = !m.future.isDone || runCatching { m.future.get() }.getOrNull() != null
            if (fresh && ok) return m.future as ListenableFuture<T?>
        }
        val f: ListenableFuture<Any?> = executor.submit(Callable<Any?> {
            val v = try { load() } catch (e: Exception) { Log.w(TAG, key, e); null }
            if (v != null) lastGood[key] = v
            v ?: lastGood[key]
        })
        memo[key] = Memo(f, now)
        return f as ListenableFuture<T?>
    }

    /** Warms up the lists when Android Auto opens, so the first folder opens already filled. */
    fun prefetch() {
        if (mixesEnabled()) mixes()
        if (followedEnabled()) followed()
        if (chartsEnabled()) charts()
    }

    private fun mixes(): ListenableFuture<List<Mix>?> =
        cached("mixes", LISTS_TTL_MS) { FlavorHooks.autoMixes()?.filter { it.trackIds.isNotEmpty() } }

    /** Followed playlists as (id, title): titles from the catalog, or from the playlist when missing from the catalog. */
    private fun followed(): ListenableFuture<List<Pair<String, String>>?> = cached("followed", LISTS_TTL_MS) {
        val ids = DriftSpotify.follows() ?: return@cached null
        val names = DriftSpotify.catalog().orEmpty().associate { it.id to it.name }
        ids.mapNotNull { id -> (names[id] ?: DriftSpotify.playlist(id)?.name)?.let { id to it } }
    }

    private fun charts(): ListenableFuture<List<Pair<String, String>>?> = cached("charts", LISTS_TTL_MS) {
        DriftSpotify.catalog()?.filter { it.group == DriftSpotify.G_CHARTS }?.map { it.id to it.name }
    }

    private fun songsOf(ids: List<String>): List<Child>? =
        ids.distinct().take(MAX_TRACKS).takeIf { it.isNotEmpty() }?.let { DriftSpotify.songs(it) }?.takeIf { it.isNotEmpty() }

    /** Library tracks of a Drift playlist (mix or Undertow playlist), in playlist order. */
    private fun songs(nodeId: String): ListenableFuture<List<Child>?> = when {
        nodeId.startsWith(MIX_PREFIX) -> {
            val key = nodeId.removePrefix(MIX_PREFIX)
            Futures.transformAsync(mixes(), { mixes ->
                val ids = mixes?.firstOrNull { it.key == key }?.trackIds
                if (ids == null) Futures.immediateFuture(null) else cached("tracks:$nodeId", TRACKS_TTL_MS) { songsOf(ids) }
            }, MoreExecutors.directExecutor())
        }
        // Drift Lite: Liked, Offline and phone playlists (always up to date: no cache)
        nodeId.startsWith(LIST_PREFIX + "lite:") ->
            Futures.immediateFuture(FlavorHooks.autoPhoneListTracks(nodeId.removePrefix(LIST_PREFIX + "lite:"))?.takeIf { it.isNotEmpty() })
        nodeId.startsWith(LIST_PREFIX) -> cached("tracks:$nodeId", TRACKS_TTL_MS) {
            DriftSpotify.playlist(nodeId.removePrefix(LIST_PREFIX))?.let { p -> songsOf(p.tracks.mapNotNull { it.songId }) }
        }
        else -> Futures.immediateFuture(null)
    }

    /** Tracks as AA MediaItems (same mapping as Navidrome playlists); empty list if Undertow does not answer. */
    fun trackItems(nodeId: String, repo: AutomotiveRepository): ListenableFuture<List<MediaItem>> =
        Futures.transform(songs(nodeId), { songs ->
            if (songs.isNullOrEmpty()) emptyList()
            else {
                repo.setChildrenMetadata(songs)
                MappingUtil.mapMediaItems(songs, ConstantsAA.QUEUE_CACHED_SOURCE)
            }
        }, MoreExecutors.directExecutor())

    /** The playlists of a folder (or the mixes, which also go straight into the For you tab). */
    fun folderItems(folderId: String): ListenableFuture<List<MediaItem>> {
        val empty: ListenableFuture<List<MediaItem>> = Futures.immediateFuture(emptyList())
        return when (folderId) {
            MIXES_ID -> if (!mixesEnabled()) empty else Futures.transform(mixes(), { mixes ->
                mixes.orEmpty().map { playlistItem(MIX_PREFIX + it.key, it.title, it.subtitle.ifBlank { null }, R.drawable.ic_aa_for_you) }
            }, MoreExecutors.directExecutor())
            FOLLOWED_ID -> if (!followedEnabled()) empty else Futures.transform(followed(), { lists ->
                lists.orEmpty().map { (id, name) -> playlistItem(LIST_PREFIX + id, name, null, R.drawable.ic_aa_playlist) }
            }, MoreExecutors.directExecutor())
            CHARTS_ID -> if (!chartsEnabled()) empty else Futures.transform(charts(), { lists ->
                lists.orEmpty().map { (id, name) -> playlistItem(LIST_PREFIX + id, name, null, R.drawable.ic_aa_mostplayed) }
            }, MoreExecutors.directExecutor())
            LITE_HOME_ID -> Futures.transform(cached("lite_home", LISTS_TTL_MS) {
                DriftSpotify.cachedCatalog() ?: DriftSpotify.catalog()
            }, { list ->
                list.orEmpty().filter { it.group in setOf(DriftSpotify.G_RELEASES, DriftSpotify.G_FOR_YOU, DriftSpotify.G_MINE, DriftSpotify.G_CHARTS) }.take(40)
                    .map { playlistItem(LIST_PREFIX + it.id, it.name, DriftSpotify.groupLabel(App.getContext(), it), R.drawable.ic_aa_for_you) }
            }, MoreExecutors.directExecutor())
            LITE_PLAYLISTS_ID -> Futures.immediateFuture(
                FlavorHooks.autoPhoneLists().map { playlistItem(LIST_PREFIX + "lite:" + it.ref, it.title, it.subtitle, it.icon) })
            else -> empty
        }
    }

    fun folderResult(folderId: String): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        Futures.transform(folderItems(folderId), { LibraryResult.ofItemList(ImmutableList.copyOf(it), null) },
            MoreExecutors.directExecutor())

    private fun iconUri(resId: Int): Uri = "android.resource://${BuildConfig.APPLICATION_ID}/$resId".toUri()

    private fun listExtras() = Bundle().apply {
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
    }

    /** Drift folder (For you / Followed / Charts): the playlists inside are shown as a list. */
    fun folderItem(folderId: String, title: String, icon: Int): MediaItem =
        MediaItem.Builder().setMediaId(folderId).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS).setArtworkUri(iconUri(icon))
                .setExtras(listExtras()).build()
        ).build()

    /**
     * Drift playlist: browsing it shows Play / Shuffle and the tracks; it is also playable, so
     * a controller that starts it directly begins from the first track (MediaLibrarySessionCallback resolves it).
     */
    private fun playlistItem(mediaId: String, title: String, subtitle: String?, icon: Int): MediaItem =
        MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setArtist(subtitle).setSubtitle(subtitle)
                .setIsBrowsable(true).setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST).setArtworkUri(iconUri(icon))
                .setExtras(listExtras()).build()
        ).build()
}
