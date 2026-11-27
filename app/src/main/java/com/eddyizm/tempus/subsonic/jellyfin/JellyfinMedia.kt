package com.eddyizm.tempus.subsonic.jellyfin

import okhttp3.HttpUrl

/**
 * Drift: binary requests (stream, download, cover art) are not translated to JSON but
 * redirected to the equivalent Jellyfin URL. The request stays the same, headers included,
 * so Range passes through and seeking works. Used by the OkHttp interceptor (Retrofit, Glide) and by
 * the ExoPlayer resolver, which goes through DefaultHttpDataSource rather than OkHttp.
 */
object JellyfinMedia {

    data class Rewrite(val url: HttpUrl, val headers: Map<String, String>)

    private val BINARY_METHODS = setOf("stream", "download", "getCoverArt", "getAvatar")

    @JvmStatic
    fun isBinary(method: String): Boolean = method in BINARY_METHODS

    @JvmStatic
    fun rewrite(url: String?): Rewrite? = JellyfinTarget.parse(url)?.let { rewrite(it) }

    /**
     * Like [rewrite], but with the [current] session instead of the one in the URL when it belongs to
     * the same user: a track queued or downloaded before a new login carries the old token,
     * which Jellyfin has revoked.
     */
    @JvmStatic
    fun rewrite(url: String?, current: JellyfinCredential?): Rewrite? {
        val target = JellyfinTarget.parse(url) ?: return null
        val fresh = if (current != null && current.kind == target.credential.kind &&
            current.userId == target.credential.userId) target.copy(credential = current) else target
        return rewrite(fresh)
    }

    @JvmStatic
    fun rewrite(target: JellyfinTarget): Rewrite? {
        val q = target.url
        val id = q.queryParameter("id")
        val url: HttpUrl = when (target.method) {
            "getCoverArt" -> {
                if (id.isNullOrEmpty()) return null
                val b = target.base.newBuilder().addPathSegment("Items").addPathSegment(id)
                    .addPathSegment("Images").addPathSegment("Primary")
                q.queryParameter("size")?.toIntOrNull()?.takeIf { it > 0 }?.let {
                    b.addQueryParameter("maxWidth", it.toString())
                    b.addQueryParameter("maxHeight", it.toString())
                }
                b.addQueryParameter("quality", "90").build()
            }
            "download" -> {
                if (id.isNullOrEmpty()) return null
                staticStream(target, id)
            }
            "stream" -> {
                if (id.isNullOrEmpty()) return null
                stream(target, id)
            }
            else -> return null
        }
        return Rewrite(url, JellyfinClientInfo.authHeaders(target.credential))
    }

    // Drift: the original file, with Range; it is also what the download saves
    private fun staticStream(target: JellyfinTarget, id: String): HttpUrl =
        target.base.newBuilder().addPathSegment("Audio").addPathSegment(id).addPathSegment("stream")
            .addQueryParameter("static", "true")
            .build()

    private fun stream(target: JellyfinTarget, id: String): HttpUrl {
        val q = target.url
        val format = q.queryParameter("format")?.lowercase()
        val maxBitRate = q.queryParameter("maxBitRate")?.toIntOrNull() ?: 0
        val timeOffset = q.queryParameter("timeOffset")?.toLongOrNull() ?: 0L

        // No transcoding requested: original file, seek with Range
        if ((format == null || format == "raw") && maxBitRate <= 0 && timeOffset <= 0) {
            return staticStream(target, id)
        }

        // Drift: /universal plays the file as is if format and bitrate already fit (Range
        // included), otherwise it transcodes to the requested format
        val (container, codec) = codecFor(format)
        val b = target.base.newBuilder().addPathSegment("Audio").addPathSegment(id).addPathSegment("universal")
            .addQueryParameter("UserId", target.credential.userId)
            .addQueryParameter("DeviceId", JellyfinClientInfo.deviceId)
            .addQueryParameter("MaxStreamingBitrate", (if (maxBitRate > 0) maxBitRate * 1000 else 320_000).toString())
            .addQueryParameter("Container", if (format == null || format == "raw") "mp3,flac,aac,m4a,ogg,opus,wav" else container)
            .addQueryParameter("TranscodingContainer", container)
            .addQueryParameter("TranscodingProtocol", "http")
            .addQueryParameter("AudioCodec", codec)
            .addQueryParameter("EnableRedirection", "true")
            .addQueryParameter("EnableRemoteMedia", "false")
        if (timeOffset > 0) b.addQueryParameter("StartTimeTicks", (timeOffset * 10_000_000L).toString())
        return b.build()
    }

    private fun codecFor(format: String?): Pair<String, String> = when (format) {
        "opus" -> "ogg" to "opus"
        "ogg", "vorbis" -> "ogg" to "vorbis"
        "aac", "m4a" -> "aac" to "aac"
        "flac" -> "flac" to "flac"
        else -> "mp3" to "mp3"
    }
}
