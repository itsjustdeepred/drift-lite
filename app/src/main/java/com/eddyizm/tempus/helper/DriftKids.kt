package com.eddyizm.tempus.helper

import com.eddyizm.tempus.subsonic.models.Child

/**
 * Drift: no kids' music in "suggested" songs (similar, random, an artist's top songs), i.e. in every
 * mix the app computes: song radio, instant mixes, Android Auto "Made for you", auto-continuing queue.
 * Albums, playlists and searches are left as they are.
 *
 * The sets come from Undertow (/mixes, "kids" field) and are saved in the preferences.
 */
object DriftKids {

    private class Sets(val tracks: Set<String>, val albums: Set<String>, val artists: Set<String>)

    @Volatile private var sets: Sets? = null

    fun invalidate() { sets = null }

    private fun sets(): Sets = sets ?: runCatching {
        Sets(DriftMixes.kidsTracks(), DriftMixes.kidsAlbums(), DriftMixes.kidsArtists())
    }.getOrElse { Sets(emptySet(), emptySet(), emptySet()) }.also { sets = it }

    @JvmStatic
    fun isKid(c: Child): Boolean {
        val s = sets()
        return c.id in s.tracks || c.albumId in s.albums || c.artistId in s.artists
    }

    /**
     * Removes kids' songs. If nearly all of them were, the request started from kids' music
     * (radio from a kids' song, Kids genre): then the list is left as is.
     */
    @JvmStatic
    fun filter(songs: List<Child>?): List<Child>? {
        if (songs.isNullOrEmpty()) return songs
        val kept = songs.filterNot { isKid(it) }
        return if (kept.size * 2 < songs.size) songs else kept
    }
}
