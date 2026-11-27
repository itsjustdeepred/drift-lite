package com.eddyizm.tempus.lite

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
 * Drift Lite: checks GitHub for a newer release at startup (at most every 6 hours, in the background).
 * If there is one, shows the version and a short changelog; "Update" downloads the APK and opens the installer.
 */
object LiteUpdater {
    private const val TAG = "LiteUpdater"
    private const val REPO = "itsjustdeepred/drift-lite"
    private const val KEY_LAST = "lite_update_checked_at"
    private const val KEY_SKIP = "lite_update_skip"

    private data class Release(val tag: String, val version: String, val notes: String, val apkUrl: String)

    @JvmStatic
    fun check(activity: Activity) {
        if (!BuildConfig.LITE) return
        val prefs = App.getInstance().preferences
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST, 0) < 6 * 3600_000L) return
        prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
        Thread {
            val r = latest() ?: return@Thread
            if (!isNewer(r.version, BuildConfig.VERSION_NAME) || prefs.getString(KEY_SKIP, null) == r.tag) return@Thread
            activity.runOnUiThread { if (!activity.isFinishing) show(activity, r) }
        }.start()
    }

    private fun latest(): Release? = try {
        val req = okhttp3.Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json").build()
        LiteYouTube.http.newCall(req).execute().use { resp ->
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
        val file = File(dir, "DriftLite-${r.version}.apk")
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
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".updates", file)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
