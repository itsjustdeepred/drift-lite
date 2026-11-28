package com.eddyizm.tempus.lite

import android.content.Context
import com.eddyizm.tempus.helper.DriftSpotify
import com.eddyizm.tempus.subsonic.models.Child

/** Drift Lite: charts and playlists read straight from the catalogs, followed and saved on the phone. */
object LiteCatalogSource : DriftSpotify.Source {
    override val enabled get() = true
    override fun catalog() = LiteCatalog.catalog().takeIf { it.isNotEmpty() }
    override fun playlist(id: String) = LiteCatalog.playlist(id)
    override fun add(url: String) = LiteCatalog.add(url)
    override fun downloadMissing(id: String) = LiteCatalog.download(id)
    override fun downloadState(id: String) = LiteCatalog.state(id)
    override fun follow(id: String, autoDownload: Boolean) = LiteCatalog.follow(id, autoDownload)
    override fun unfollow(id: String) = LiteCatalog.unfollow(id)
    override fun followed() = LiteCatalog.followed()
    override fun follows() = LiteCatalog.follows()
    override fun syncSaved() = LiteCatalog.refreshFollows()
    override fun downloadFollowed(context: Context, only: String?) = LiteCatalog.downloadFollowed(context, only)
    override fun songs(ids: List<String>): List<Child> = ids.mapNotNull { LiteTracks.child(it) }
    /** Nothing to save: there is no server. */
    override fun save(p: DriftSpotify.Playlist) = false
}
