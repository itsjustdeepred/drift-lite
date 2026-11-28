package com.eddyizm.tempus.helper

import com.eddyizm.tempus.App
import com.eddyizm.tempus.subsonic.models.Child

/**
 * Charts and editorial playlists (Spotify, Deezer, Last.fm). The data comes from the build's [Source]:
 * Undertow in Drift (matched against the library, missing songs downloaded on request), the phone in Drift Lite.
 * Everything here runs off the main thread.
 */
object DriftSpotify {
    /** Name prefix of the playlists saved on the server from a chart or editorial playlist ("Rebuilt · ", translated). */
    @JvmStatic val PREFIX: String get() = App.getContext().getString(com.eddyizm.tempus.R.string.drift_rebuilt_prefix) + " · "
    /** Every prefix a saved playlist may carry: the ones of the other languages and of earlier versions. */
    @JvmStatic val ALL_PREFIXES = listOf("Rebuilt · ", "Ricomposta · ", "Reconstruida · ", "Reconstituée · ", "Neu zusammengestellt · ",
        "Recriada · ", "Spotify · ", "Drift · ")

    /** [group] is a stable key (G_*, or an Undertow group key); the title shown comes from [groupLabel]. */
    data class Entry(val id: String, val group: String, val name: String, val cover: String?, val count: Int,
                     val provider: String = "", val followed: Boolean = false, val sub: String? = null,
                     val groupTitle: String? = null)
    data class Track(val title: String, val artist: String, val duration: Int, val songId: String?)
    data class Playlist(val id: String, val name: String, val cover: String?, val tracks: List<Track>,
                        val followed: Boolean = false, val autoDownload: Boolean = false) {
        // Drift Lite: YouTube songs ("yt:...") can be played but are not in the library
        val inLibrary get() = tracks.count { it.songId != null && !com.eddyizm.tempus.flavor.FlavorHooks.isPhoneTrack(it.songId) }
    }
    data class ItemState(val state: String, val progress: Float)
    data class Bulk(val running: Boolean, val total: Int, val done: Int, val queued: Int, val notFound: Int,
                    val active: Boolean = running, val progress: Float = 0f, val downloaded: Int = 0, val failed: Int = 0,
                    val items: Map<Int, ItemState> = emptyMap())

    /** Where the catalog comes from: Undertow (Drift) or the phone (Drift Lite). */
    interface Source {
        val enabled: Boolean
        fun catalog(): List<Entry>?
        fun playlist(id: String): Playlist?
        /** Adds a playlist from a pasted link; returns the id or null. */
        fun add(url: String): String?
        fun downloadMissing(id: String): Bulk?
        fun downloadState(id: String): Bulk?
        fun follow(id: String, autoDownload: Boolean): Boolean
        fun unfollow(id: String): Boolean
        fun followed(): List<Followed>?
        fun follows(): List<String>?
        /** Daily refresh of the followed playlists the user saved; returns how many changed. */
        fun syncSaved(): Int
        /** Puts the songs of the followed playlists on the phone; [only] = a single playlist. */
        fun downloadFollowed(context: android.content.Context, only: String?): Int
        /** Songs for the given ids, in the same order. */
        fun songs(ids: List<String>): List<Child>
        /** Saves the playlist with the songs you have (on the server, for Drift). */
        fun save(p: Playlist): Boolean
    }

    private val source get() = com.eddyizm.tempus.flavor.FlavorHooks.catalog

    @JvmStatic val enabled get() = source.enabled

    // Catalog group keys (stable, also used by Android Auto); never compare against displayed titles
    const val G_RELEASES = "releases"
    const val G_FOR_YOU = "foryou"
    const val G_MINE = "mine"
    const val G_CHARTS = "charts"
    const val G_ADDED = "added"
    const val G_GENRES = "genres"
    const val G_DECADES = "decades"
    const val G_MOODS = "moods"
    const val G_FOLLOWED = "followed"
    const val G_ARTISTS = "artists"

    /** Display title of a catalog group; unknown Undertow keys fall back to the title sent by the server. */
    @JvmStatic
    fun groupLabel(ctx: android.content.Context, key: String, fallback: String? = null): String = when (key) {
        G_RELEASES -> ctx.getString(com.eddyizm.tempus.R.string.drift_group_releases)
        G_FOR_YOU -> ctx.getString(com.eddyizm.tempus.R.string.drift_group_for_you)
        // Drift Lite overrides drift_group_mine ("Saved"): there "Your playlists" are the ones made on the phone
        G_MINE -> ctx.getString(com.eddyizm.tempus.R.string.drift_group_mine)
        G_CHARTS -> ctx.getString(com.eddyizm.tempus.R.string.drift_group_charts)
        G_ADDED -> ctx.getString(com.eddyizm.tempus.R.string.drift_group_added)
        G_GENRES -> ctx.getString(com.eddyizm.tempus.R.string.drift_group_genres)
        G_DECADES -> ctx.getString(com.eddyizm.tempus.R.string.drift_group_decades)
        G_MOODS -> ctx.getString(com.eddyizm.tempus.R.string.drift_group_moods)
        G_FOLLOWED -> ctx.getString(com.eddyizm.tempus.R.string.drift_group_followed)
        G_ARTISTS -> ctx.getString(com.eddyizm.tempus.R.string.drift_group_artists)
        else -> fallback ?: key
    }

    @JvmStatic
    fun groupLabel(ctx: android.content.Context, e: Entry): String = groupLabel(ctx, e.group, e.groupTitle)

    private val cacheFile get() = java.io.File(App.getContext().filesDir, "drift_catalog_v2.json")

    /** Last catalog read, to show Home right away (refreshed afterwards). v2: groups are keys, not titles. */
    @JvmStatic
    fun cachedCatalog(): List<Entry>? = runCatching {
        @Suppress("SENSELESS_COMPARISON")
        com.google.gson.Gson().fromJson<List<Entry?>>(cacheFile.readText(), object : com.google.gson.reflect.TypeToken<List<Entry?>>() {}.type)
            .filter { it != null && it.id != null && it.group != null && it.name != null }.map { it!! }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** Followed playlist, with how many new songs the last nightly update brought. */
    data class Followed(val entry: Entry, val inLibrary: Int, val lastSync: Long, val lastNew: Int) {
        /** "New" dot: new songs since the last time you opened it. */
        val hasNew get() = lastNew > 0 && lastSync > App.getInstance().preferences.getLong("drift_follow_seen_" + entry.id, 0)
    }

    /** Opened: the dot disappears until the next new songs. */
    @JvmStatic
    fun markSeen(id: String) {
        App.getInstance().preferences.edit().putLong("drift_follow_seen_$id", System.currentTimeMillis() / 1000 + 1).apply()
    }

    const val KEY_OFFLINE = "drift_follow_offline"
    @JvmStatic val offlineEnabled get() = App.getInstance().preferences.getBoolean(KEY_OFFLINE, true)

    @JvmStatic
    fun catalog(): List<Entry>? = source.catalog()?.also { l -> if (l.isNotEmpty()) runCatching { cacheFile.writeText(com.google.gson.Gson().toJson(l)) } }
    @JvmStatic fun playlist(id: String): Playlist? = source.playlist(id)
    @JvmStatic fun add(url: String): String? = source.add(url)
    @JvmStatic fun downloadMissing(id: String): Bulk? = source.downloadMissing(id)
    @JvmStatic fun downloadState(id: String): Bulk? = source.downloadState(id)
    @JvmStatic fun follow(id: String, autoDownload: Boolean): Boolean = source.follow(id, autoDownload)
    @JvmStatic fun unfollow(id: String): Boolean = source.unfollow(id)
    @JvmStatic fun followed(): List<Followed>? = source.followed()
    @JvmStatic fun follows(): List<String>? = source.follows()
    @JvmStatic fun syncSaved(): Int = source.syncSaved()
    @JvmStatic fun downloadFollowed(context: android.content.Context, only: String? = null): Int =
        if (!offlineEnabled) 0 else source.downloadFollowed(context, only)
    @JvmStatic fun songs(ids: List<String>): List<Child> = source.songs(ids)
    @JvmStatic fun save(p: Playlist): Boolean = source.save(p)
}
