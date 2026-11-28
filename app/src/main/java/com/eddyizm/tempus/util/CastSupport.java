package com.eddyizm.tempus.util;

import android.content.Context;

import androidx.core.content.ContextCompat;

import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

/** Google Cast: started only when Play services are available. */
public class CastSupport {
    @SuppressWarnings("deprecation")
    public static void initializeCastContext(Context context) {
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS)
            CastContext.getSharedInstance(context, ContextCompat.getMainExecutor(context));
    }

    /** Drift: adds the Google Cast categories to the device selector. */
    public static void addCastCategories(Context context, androidx.mediarouter.media.MediaRouteSelector.Builder builder) {
        try {
            if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS) {
                androidx.mediarouter.media.MediaRouteSelector cast = CastContext.getSharedInstance(context).getMergedSelector();
                if (cast != null) builder.addSelector(cast);
            }
        } catch (Exception ignored) { }
    }
}
