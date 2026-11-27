package com.eddyizm.tempus.helper

import android.content.Context
import android.telephony.TelephonyManager
import com.eddyizm.tempus.App

/**
 * Drift: country used for charts and searches. The in-app language changes Locale.getDefault()
 * (e.g. "en" -> US), so the real country comes from a manual choice, then the network/SIM, then the system region.
 */
object DriftCountry {
    const val KEY = "drift_chart_country"

    /** Countries offered in settings (ISO codes); "" = automatic. */
    @JvmField val CHOICES = arrayOf("", "US", "GB", "IT", "DE", "FR", "ES", "NL", "BE", "CH", "AT", "PT", "SE", "PL",
        "BR", "MX", "AR", "CA", "AU", "JP")

    @JvmStatic
    fun get(): String {
        App.getInstance().preferences.getString(KEY, "")?.takeIf { it.length == 2 }?.let { return it }
        runCatching {
            val tm = App.getContext().getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            (tm.networkCountryIso.ifEmpty { tm.simCountryIso }).takeIf { it.length == 2 }?.let { return it.uppercase() }
        }
        val sys = android.content.res.Resources.getSystem().configuration.locales[0].country
        return sys.ifEmpty { java.util.Locale.getDefault().country }.ifEmpty { "US" }.uppercase()
    }

    /** Country name in the app language, for the settings list. */
    @JvmStatic
    fun label(code: String, auto: String): String =
        if (code.isEmpty()) auto else java.util.Locale("", code).getDisplayCountry(App.getContext().resources.configuration.locales[0])
}
