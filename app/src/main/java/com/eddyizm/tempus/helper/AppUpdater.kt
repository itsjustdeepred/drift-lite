package com.eddyizm.tempus.helper

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.eddyizm.tempus.App
import com.eddyizm.tempus.BuildConfig
import com.eddyizm.tempus.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.JsonParser
import java.io.File

/**
 * Checks the app's GitHub releases for a newer version at startup (at most once an hour, in the background) or on
 * demand from Settings. If there is one, shows the version and a short changelog; "Update" downloads the APK and opens
 * the system installer. The first time Android asks to allow installs from the app: the install resumes on return.
 * The repository and the APK name come from the build (UPDATE_REPO, UPDATE_APK).
 */
object AppUpdater {
    private const val TAG = "AppUpdater"
    private val http = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()
    private const val KEY_LAST = "lite_update_checked_at"
    private const val KEY_SKIP = "lite_update_skip"
    private const val KEY_LAST_CODE = "lite_update_checked_code"
    private const val KEY_PENDING = "lite_update_pending_apk"

    private data class Release(val tag: String, val version: String, val notes: String, val apkUrl: String)

    @JvmStatic
    @JvmOverloads
    fun check(activity: Activity, manual: Boolean = false) {
        val prefs = App.getInstance().preferences
        // right after an install or update the check runs at once, then at most once an hour
        val sameBuild = prefs.getInt(KEY_LAST_CODE, 0) == BuildConfig.VERSION_CODE
        if (!manual && sameBuild && System.currentTimeMillis() - prefs.getLong(KEY_LAST, 0) < 3600_000L) return
        prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).putInt(KEY_LAST_CODE, BuildConfig.VERSION_CODE).apply()
        Thread {
            val r = latest()
            val newer = r != null && isNewer(r.version, BuildConfig.VERSION_NAME) && (manual || prefs.getString(KEY_SKIP, null) != r.tag)
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                if (newer) show(activity, r!!)
                else if (manual) android.widget.Toast.makeText(activity, if (r == null) R.string.lite_update_failed else R.string.lite_update_latest, android.widget.Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /** After the user allowed installs from the app in system settings: continue the pending install. */
    @JvmStatic
    fun resumePending(activity: Activity) {
        val prefs = App.getInstance().preferences
        val path = prefs.getString(KEY_PENDING, null) ?: return
        val file = File(path)
        if (!file.isFile) { prefs.edit().remove(KEY_PENDING).apply(); return }
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) return
        prefs.edit().remove(KEY_PENDING).apply()
        install(activity, file)
    }

    private fun latest(): Release? = try {
        val req = okhttp3.Request.Builder().url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val o = JsonParser.parseString(resp.body.string()).asJsonObject
            val tag = o["tag_name"]?.asString ?: return null
            val apk = o.getAsJsonArray("assets")?.map { it.asJsonObject }
                ?.firstOrNull { it["name"]?.asString?.endsWith(".apk") == true }?.get("browser_download_url")?.asString ?: return null
            Release(tag, tag.removePrefix("v"), o["body"]?.takeIf { !it.isJsonNull }?.asString.orEmpty(), apk)
        }
    } catch (e: Exception) { Log.w(TAG, "check", e); null }

    /** "1.2.10" > "1.2.9"; non-numeric parts are ignored. */
    internal fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(remote); val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Release notes as plain text: no markdown markers, at most ~12 lines. */
    private fun shortNotes(md: String): String = md.lines()
        .map { it.trim().removePrefix("#").removePrefix("#").removePrefix("#").trim().replace("**", "").replace(Regex("^[-*] "), "• ") }
        .filter { it.isNotEmpty() }.take(12).joinToString("\n")

    private fun show(activity: Activity, r: Release) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.lite_update_title, r.version))
            .setMessage(shortNotes(r.notes).ifEmpty { activity.getString(R.string.lite_update_no_notes) })
            .setPositiveButton(R.string.lite_update_install) { _, _ -> download(activity.applicationContext, r) }
            .setNegativeButton(R.string.lite_update_later, null)
            .setNeutralButton(R.string.lite_update_skip) { _, _ -> App.getInstance().preferences.edit().putString(KEY_SKIP, r.tag).apply() }
            .show()
    }

    private fun download(ctx: Context, r: Release) {
        val dir = File(ctx.getExternalFilesDir(null), "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val file = File(dir, "${BuildConfig.UPDATE_APK}-${r.version}.apk")
        android.widget.Toast.makeText(ctx, ctx.getString(R.string.lite_update_downloading, r.version), android.widget.Toast.LENGTH_SHORT).show()
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(DownloadManager.Request(Uri.parse(r.apkUrl))
            .setTitle(ctx.getString(R.string.lite_update_downloading, r.version))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(file)))
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                runCatching { c.unregisterReceiver(this) }
                if (file.isFile && file.length() > 0) install(c, file)
            }
        }
        ContextCompat.registerReceiver(ctx, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            if (Build.VERSION.SDK_INT >= 33) ContextCompat.RECEIVER_EXPORTED else ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun install(ctx: Context, file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            // first update: Android wants permission to install from the app; resumePending() continues on return
            App.getInstance().preferences.edit().putString(KEY_PENDING, file.absolutePath).apply()
            android.widget.Toast.makeText(ctx, R.string.lite_update_allow, android.widget.Toast.LENGTH_LONG).show()
            ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".updates", file)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
