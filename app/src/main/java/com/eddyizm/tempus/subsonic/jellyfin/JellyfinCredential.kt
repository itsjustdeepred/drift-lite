package com.eddyizm.tempus.subsonic.jellyfin

import java.util.UUID

/**
 * Drift: server types the app can use. Subsonic is native; Jellyfin and Emby go through
 * emulation (see [JellyfinInterceptor]).
 */
object ServerType {
    const val SUBSONIC = "subsonic"
    const val JELLYFIN = "jellyfin"
    const val EMBY = "emby"

    @JvmStatic
    fun isMediaBrowser(type: String?): Boolean = type == JELLYFIN || type == EMBY
}

/**
 * Drift: the Jellyfin/Emby session (user + access token) stored where the app keeps a Subsonic
 * server's password. Written as a single string so it passes intact through Preferences, the server
 * table and the `p` parameter of every `/rest/...` URL the app builds: the interceptor reads it back
 * from there with no shared state. The real password is not stored.
 */
data class JellyfinCredential(val kind: String, val userId: String, val token: String) {
    val isEmby: Boolean get() = kind == ServerType.EMBY

    fun encode(): String = "$PREFIX$kind~$userId~$token"

    override fun toString(): String = "JellyfinCredential(kind=$kind, userId=$userId)"

    companion object {
        const val PREFIX = "drift~"
        private val PATTERN = Regex("^drift~(jellyfin|emby)~([A-Za-z0-9-]+)~([A-Za-z0-9-]+)$")

        @JvmStatic
        fun parse(value: String?): JellyfinCredential? {
            if (value == null || !value.startsWith(PREFIX)) return null
            val m = PATTERN.matchEntire(value) ?: return null
            return JellyfinCredential(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }

        @JvmStatic
        fun isCredential(value: String?): Boolean = parse(value) != null
    }
}

/**
 * Drift: how the app identifies itself to Jellyfin/Emby. The DeviceId must stay the same across
 * launches (Jellyfin binds the token to the device): App sets it at startup from a saved id.
 */
object JellyfinClientInfo {
    const val CLIENT = "Drift"
    const val DEVICE = "Android"

    @JvmStatic
    @Volatile
    var deviceId: String = UUID.randomUUID().toString()

    @JvmStatic
    @Volatile
    var version: String = "1.0"

    private fun mediaBrowser(token: String?): String {
        val base = "MediaBrowser Client=\"$CLIENT\", Device=\"$DEVICE\", DeviceId=\"$deviceId\", Version=\"$version\""
        return if (token.isNullOrEmpty()) base else "$base, Token=\"$token\""
    }

    /** Authentication headers for [kind]; [token] null before login. */
    @JvmStatic
    fun authHeaders(kind: String, token: String?): Map<String, String> =
        if (kind == ServerType.EMBY) {
            // Drift: Emby wants the X-Emby-* header; Jellyfin 12 no longer accepts it
            buildMap {
                put("X-Emby-Authorization", mediaBrowser(token))
                if (!token.isNullOrEmpty()) put("X-Emby-Token", token)
            }
        } else {
            mapOf("Authorization" to mediaBrowser(token))
        }

    @JvmStatic
    fun authHeaders(credential: JellyfinCredential): Map<String, String> =
        authHeaders(credential.kind, credential.token)
}
