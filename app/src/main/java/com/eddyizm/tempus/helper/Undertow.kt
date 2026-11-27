package com.eddyizm.tempus.helper

import com.eddyizm.tempus.App
import com.eddyizm.tempus.BuildConfig
import com.eddyizm.tempus.R
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Drift: Undertow address and token (downloads, mixes, waveforms, lyrics, charts). Set in settings;
 * the build values are only defaults, so the app can be given to others.
 */
object Undertow {
    const val KEY_URL = "undertow_url"
    const val KEY_TOKEN = "undertow_token"

    private val prefs get() = App.getInstance().preferences
    val url: String get() = (prefs.getString(KEY_URL, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DRIFT_SIDECAR_URL).trim().trimEnd('/')
    val token: String get() = (prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DRIFT_SIDECAR_TOKEN).trim()
    // Drift: Undertow reads the Navidrome database; with Jellyfin/Emby, mixes, waveforms and charts go away
    @JvmStatic val enabled: Boolean get() = url.isNotEmpty() && token.isNotEmpty() && !isMediaBrowserServer

    /** true if the current server is Jellyfin/Emby (session stored in place of the password). */
    @JvmStatic val isMediaBrowserServer: Boolean
        get() = com.eddyizm.tempus.subsonic.jellyfin.JellyfinCredential.isCredential(com.eddyizm.tempus.util.Preferences.getPassword())

    fun req(path: String): Request.Builder = Request.Builder().url(url + path).header("Authorization", "Bearer $token")

    private const val NO_URL = "no_url"
    private const val BAD_TOKEN = "bad_token"
    private fun str(id: Int, vararg args: Any): String = App.getContext().getString(id, *args)

    /** Raw probe: null if it answers, otherwise NO_URL, BAD_TOKEN, "HTTP <code>" or the exception message. */
    private fun probe(): String? {
        if (url.isEmpty()) return NO_URL
        val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
        return try {
            http.newCall(req("/health").build()).execute().use { if (!it.isSuccessful) return "HTTP ${it.code}" }
            // /health is public: the token is checked on a protected endpoint
            http.newCall(req("/jobs").build()).execute().use { if (it.code == 401) BAD_TOKEN else null }
        } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
    }

    /** null if it answers, otherwise the reason (for the test in settings). Off the main thread. */
    @JvmStatic
    fun check(): String? = when (val err = probe()) {
        NO_URL -> str(R.string.drift_undertow_no_url)
        BAD_TOKEN -> str(R.string.drift_undertow_bad_token)
        else -> err
    }

    @JvmStatic fun panelUrl() = "$url/settings"

    /**
     * Why Undertow did not answer, in plain words (call off the main thread).
     * [what] is the string resource of what could not be read: R.string.drift_what_playlists, ...
     */
    @JvmStatic
    fun diagnose(@androidx.annotation.StringRes what: Int): String {
        val w = str(what)
        if (BuildConfig.LITE) return if (com.eddyizm.tempus.util.NetworkUtil.isOffline()) str(R.string.lite_diag_offline)
            else str(R.string.lite_diag_failed, w)
        if (!enabled) return str(R.string.drift_diag_not_configured)
        if (com.eddyizm.tempus.util.NetworkUtil.isOffline()) return str(R.string.drift_diag_offline, w)
        val err = probe() ?: return str(R.string.drift_diag_no_data, w)
        return when {
            err == BAD_TOKEN -> str(R.string.drift_diag_bad_token)
            err.startsWith("HTTP 5") -> str(R.string.drift_diag_server_error, err)
            err.startsWith("HTTP") -> str(R.string.drift_diag_http, err)
            else -> str(R.string.drift_diag_unreachable, url)
        }
    }

    private val lyricsTried = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Asks Undertow to find synced lyrics for a song Navidrome does not have.
     * true if there is now an .lrc (Navidrome serves it right away, no rescan). Once per song per session.
     */
    @JvmStatic
    fun fetchLyrics(songId: String): Boolean {
        if (!enabled || !lyricsTried.add(songId)) return false
        val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        return try {
            http.newCall(req("/lyrics/fetch/" + java.net.URLEncoder.encode(songId, "UTF-8"))
                .post(ByteArray(0).toRequestBody(null)).build()).execute().use { r ->
                r.isSuccessful && com.google.gson.JsonParser.parseString(r.body.string()).asJsonObject["status"]?.asString == "found"
            }
        } catch (e: Exception) { false }
    }
}
