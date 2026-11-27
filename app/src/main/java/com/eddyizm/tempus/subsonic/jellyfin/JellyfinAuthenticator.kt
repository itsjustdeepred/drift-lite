package com.eddyizm.tempus.subsonic.jellyfin

import androidx.annotation.StringRes
import com.eddyizm.tempus.R
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Drift: Jellyfin/Emby login with username and password (POST /Users/AuthenticateByName). Returns
 * the session to store in place of the password. Must be called off the main thread.
 */
object JellyfinAuthenticator {

    /** [reason] is the localized text to show the user (format args in [args]); [message] is English for logs. */
    class AuthException(message: String, @StringRes val reason: Int, vararg val args: Any) : Exception(message)

    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class, AuthException::class)
    fun authenticate(
        serverUrl: String,
        kind: String,
        username: String,
        password: String,
        client: OkHttpClient = defaultClient(),
    ): JellyfinCredential {
        val base = serverUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw AuthException("Invalid server address", R.string.drift_jf_auth_bad_address)
        val url = base.newBuilder().addPathSegments("Users/AuthenticateByName").build()

        val body = JsonObject().apply {
            addProperty("Username", username)
            addProperty("Pw", password)
        }
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply { JellyfinClientInfo.authHeaders(kind, null).forEach { (k, v) -> header(k, v) } }
            .build()

        client.newCall(request).execute().use { response ->
            when {
                response.code == 401 || response.code == 403 ->
                    throw AuthException("Wrong username or password", R.string.drift_jf_auth_wrong_credentials)
                !response.isSuccessful ->
                    throw AuthException("Server replied HTTP ${response.code}", R.string.drift_jf_auth_http_error, response.code)
            }
            val json = try {
                JsonParser.parseString(response.body.string()).asJsonObject
            } catch (e: Exception) {
                throw AuthException("Invalid response: is this really a Jellyfin/Emby server?", R.string.drift_jf_auth_bad_response)
            }
            val token = Json.str(json, "AccessToken")
            val userId = Json.obj(json, "User")?.let { Json.str(it, "Id") }
            if (token.isNullOrEmpty() || userId.isNullOrEmpty()) {
                throw AuthException("Response without token: is this really a Jellyfin/Emby server?", R.string.drift_jf_auth_no_token)
            }
            return JellyfinCredential(kind, userId, token)
        }
    }

    private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
}
