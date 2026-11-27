package com.eddyizm.tempus.subsonic.jellyfin

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Drift: Subsonic emulation for Jellyfin/Emby. Install it as an application interceptor:
 * `/rest/<method>` requests carrying the Jellyfin session in the `p` parameter are
 * - redirected to the equivalent Jellyfin URL if binary (stream, download, cover art);
 * - resolved here, with calls to Jellyfin via `chain.proceed`, and returned as Subsonic JSON.
 * Everything else passes through unchanged, so nothing changes on a Subsonic server.
 */
class JellyfinInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val target = JellyfinTarget.parse(request.url) ?: return chain.proceed(request)

        if (JellyfinMedia.isBinary(target.method)) {
            val rewrite = JellyfinMedia.rewrite(target) ?: return notFound(chain)
            val builder = request.newBuilder().url(rewrite.url)
            rewrite.headers.forEach { (k, v) -> builder.header(k, v) }
            return chain.proceed(builder.build())
        }

        val translator = JellyfinTranslator(target.base, target.credential, target.username) { chain.proceed(it) }
        val json = translator.handle(target.method, target.url)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", JSON_TYPE)
            .body(json.toString().toResponseBody(JSON_TYPE.toMediaType()))
            .build()
    }

    private fun notFound(chain: Interceptor.Chain): Response = Response.Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(404)
        .message("Not Found")
        .body("".toResponseBody(null))
        .build()

    private companion object {
        const val JSON_TYPE = "application/json; charset=utf-8"
    }
}
