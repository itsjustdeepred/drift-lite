package com.eddyizm.tempus.util;

import android.content.Context;
import android.view.Menu;

import androidx.core.content.ContextCompat;

import com.eddyizm.tempus.R;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

public class Flavors {
    @SuppressWarnings("deprecation")
    public static void initializeCastContext(Context context) {
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS)
            CastContext.getSharedInstance(context, ContextCompat.getMainExecutor(context));
    }

    public static void setUpRouteButton(Context context, Menu menu) {
        CastButtonFactory.setUpMediaRouteButton(context, menu, R.id.media_route_menu_item);
    }

    /** The button still offers Cast with UPnP turned off, unless Play services are missing and Cast never started. */
    public static boolean routeButtonIsOnlyForUpnp(Context context) {
        return GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS;
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
