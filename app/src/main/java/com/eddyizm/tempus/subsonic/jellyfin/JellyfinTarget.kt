package com.eddyizm.tempus.subsonic.jellyfin

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Drift: a Subsonic request (`<server>/rest/<method>?...&p=drift~...`) aimed at a Jellyfin/Emby
 * server. [base] is the server address without `/rest`, so the emulation follows the address
 * in use (public or local) without knowing anything about it.
 */
data class JellyfinTarget(
    val base: HttpUrl,
    val method: String,
    val credential: JellyfinCredential,
    val username: String?,
    val url: HttpUrl,
) {
    companion object {
        @JvmStatic
        fun parse(url: HttpUrl): JellyfinTarget? {
            val credential = JellyfinCredential.parse(url.queryParameter("p")) ?: return null
            val segments = url.pathSegments
            if (segments.size < 2 || segments[segments.size - 2] != "rest") return null
            val method = segments.last().removeSuffix(".view")
            if (method.isEmpty()) return null

            val base = url.newBuilder().query(null).fragment(null)
            // Drift: strips "rest/<method>", keeping any server path (e.g. /jellyfin)
            base.removePathSegment(segments.size - 1)
            base.removePathSegment(segments.size - 2)
            return JellyfinTarget(base.build(), method, credential, url.queryParameter("u"), url)
        }

        @JvmStatic
        fun parse(url: String?): JellyfinTarget? {
            if (url == null || !url.contains(JellyfinCredential.PREFIX)) return null
            return url.toHttpUrlOrNull()?.let { parse(it) }
        }
    }
}
