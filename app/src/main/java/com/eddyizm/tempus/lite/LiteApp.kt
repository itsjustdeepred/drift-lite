package com.eddyizm.tempus.lite

import androidx.navigation.NavController
import androidx.navigation.fragment.FragmentNavigator
import com.eddyizm.tempus.App
import com.eddyizm.tempus.R
import com.eddyizm.tempus.util.Preferences

/** Drift Lite: serverless startup and its own screens instead of the library ones. */
object LiteApp {
    private val prefs get() = App.getInstance().preferences

    /**
     * No server: the Subsonic client points to an address that does not answer, so shared code
     * that tries to call it fails cleanly instead of crashing.
     */
    @JvmStatic
    fun prepare() {
        migrateData()
        if (Preferences.getServer().isNullOrEmpty() || Preferences.getServer() == "http://127.0.0.1:9") Preferences.setServer("http://lite.invalid")
        if (Preferences.getUser().isNullOrEmpty()) Preferences.setUser("lite")
    }

    private const val DATA_VERSION = 2
    private const val KEY_DATA_VERSION = "lite_data_version"

    /**
     * Release builds up to versionCode 3 saved JSON with obfuscated field names, unreadable now. Caches are dropped
     * (they rebuild themselves); saved lists are kept only for entries that still read correctly.
     */
    private fun migrateData() {
        if (prefs.getInt(KEY_DATA_VERSION, 0) >= DATA_VERSION) return
        val dir = java.io.File(App.getContext().filesDir, "lite")
        dir.listFiles()?.filter { it.name.startsWith("pl_") || it.name == "matches.json" }?.forEach { it.delete() }
        java.io.File(App.getContext().filesDir, "drift_catalog_v2.json").delete()
        val e = prefs.edit()
        prefs.all.keys.filter { it == "lite_releases" || it == "lite_releases_at" || it == "lite_discover" || it.startsWith("lite_for_you_") }
            .forEach { e.remove(it) }
        e.putInt(KEY_DATA_VERSION, DATA_VERSION).apply()
    }

    @JvmStatic
    fun swapDestinations(nc: NavController) {
        val g = nc.navInflater.inflate(R.navigation.nav_graph)
        fun swap(id: Int, cls: Class<*>) { (g.findNode(id) as? FragmentNavigator.Destination)?.setClassName(cls.name) }
        swap(R.id.homeFragment, com.eddyizm.tempus.ui.fragment.DriftSpotifyFragment::class.java)
        swap(R.id.searchFragment, LiteSearchFragment::class.java)
        swap(R.id.libraryFragment, LiteLibraryFragment::class.java)
        swap(R.id.playlistCatalogueFragment, LitePlaylistsFragment::class.java)
        swap(R.id.playlistPageFragment, LitePlaylistPageFragment::class.java)
        swap(R.id.downloadFragment, LiteOfflineFragment::class.java)
        nc.graph = g
    }

    // ------------------------------------------------------------------ profile (first launch)

    const val KEY_ONBOARDED = "lite_onboarded"
    const val KEY_NAME = "lite_user_name"
    const val KEY_GENRES = "lite_genres"

    var onboarded: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(v) { prefs.edit().putBoolean(KEY_ONBOARDED, v).apply() }
    var name: String
        get() = prefs.getString(KEY_NAME, "")!!
        set(v) { prefs.edit().putString(KEY_NAME, v.trim()).apply() }
    var genres: List<Int>
        get() = prefs.getString(KEY_GENRES, "")!!.split(',').mapNotNull { it.trim().toIntOrNull() }
        set(v) { prefs.edit().putString(KEY_GENRES, v.joinToString(",")).apply() }

    /** Deezer genre (stable id) with its name; [res] != 0 when the name is translated. */
    data class Genre(val id: Int, val name: String, val res: Int = 0)

    val GENRES = listOf(
        Genre(132, "Pop"), Genre(116, "Rap / Hip hop"), Genre(152, "Rock"), Genre(113, "Dance"),
        Genre(106, "Electronic", R.string.lite_genre_electronic), Genre(165, "R&B"), Genre(85, "Alternative"),
        Genre(197, "Latin", R.string.lite_genre_latin), Genre(129, "Jazz"), Genre(98, "Classical", R.string.lite_genre_classical),
        Genre(464, "Metal"), Genre(169, "Soul & Funk"), Genre(144, "Reggae"), Genre(466, "Folk"), Genre(84, "Country"), Genre(153, "Blues"),
    )

    /** Languages offered at first launch: tag (empty = the phone's, label from R.string.lite_phone_language) and name in that language. */
    val LANGUAGES = listOf("" to "", "it" to "Italiano", "en" to "English", "es" to "Español",
        "fr" to "Français", "de" to "Deutsch", "pt" to "Português")
}
