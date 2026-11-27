package com.eddyizm.tempus.helper

import android.content.Context
import com.eddyizm.tempus.App

/**
 * Drift: notifies new releases from the artists you listen to (Undertow's "missing releases").
 * The first check stores them silently, then one notification per unseen record (max 5 per run).
 */
object DriftReleases {
    private const val KEY = "drift_known_releases"
    private const val CHANNEL = "drift_releases"

    @JvmStatic
    fun check(context: Context) {
        val res = DriftMixes.fetch() ?: return
        val all = res.mixes.flatMap { it.missing }.distinctBy { it.deezerId }
        val prefs = App.getInstance().preferences
        val known = prefs.getString(KEY, null)
        val ids = all.map { it.deezerId.toString() }
        if (known != null) {
            val seen = known.split(',').toSet()
            all.filter { it.deezerId.toString() !in seen }.take(5).forEach { notify(context, it) }
        }
        prefs.edit().putString(KEY, ((known?.split(',').orEmpty()) + ids).distinct().takeLast(500).joinToString(",")).apply()
    }

    private fun notify(ctx: Context, r: DriftMixes.MissingRelease) {
        val nm = androidx.core.app.NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).createNotificationChannel(
                android.app.NotificationChannel(CHANNEL, ctx.getString(com.eddyizm.tempus.R.string.drift_group_releases), android.app.NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        val pi = android.app.PendingIntent.getActivity(ctx, r.deezerId.toInt(), open, android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = androidx.core.app.NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(com.eddyizm.tempus.R.drawable.ic_launcher_monochrome)
            .setContentTitle(ctx.getString(com.eddyizm.tempus.R.string.drift_releases_new_title, r.artist)).setContentText(r.title).setContentIntent(pi).setAutoCancel(true).build()
        runCatching { nm.notify(("rel" + r.deezerId).hashCode(), n) }
    }
}
