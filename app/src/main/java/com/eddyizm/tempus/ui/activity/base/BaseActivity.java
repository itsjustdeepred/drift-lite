package com.eddyizm.tempus.ui.activity.base;

import android.Manifest;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.offline.DownloadService;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;

import com.eddyizm.tempus.helper.ThemeHelper;
import com.eddyizm.tempus.service.DownloaderService;
import com.eddyizm.tempus.service.MediaService;
import com.eddyizm.tempus.ui.dialog.BatteryOptimizationDialog;
import com.eddyizm.tempus.util.Flavors;
import com.eddyizm.tempus.util.Preferences;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;

@UnstableApi
public class BaseActivity extends AppCompatActivity {
    private static final String TAG = "BaseActivity";

    private String themeSignature = "";
    private boolean localNetworkRequestOpen = false;

    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeHelper.enableThemeSwitch(this);
        themeSignature = generateThemeSignature();
        super.onCreate(savedInstanceState);
        // Drift: AppCompat rebuilds the theme in super.onCreate (night mode), so the accent overlay goes on again here, before any view
        com.eddyizm.tempus.helper.DriftAccent.apply(this);
        Flavors.initializeCastContext(this);
        initializeDownloader();
        checkBatteryOptimization();
        checkPermission();
        checkAlwaysOnDisplay();
    }

    @Override
    protected void onStart() {
        // Fragments start inside super.onStart(), and an observer that reaches the browser can run there, so the browser has to exist first.
        initializeBrowser();
        super.onStart();
        ThemeHelper.setNavigationBarColor(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!ThemeHelper.themeSignature().equals(themeSignature)) {
            // Drift: the recreated activity reopens the same screen (e.g. Settings → Appearance), not Home
            if (this instanceof com.eddyizm.tempus.ui.activity.MainActivity)
                ((com.eddyizm.tempus.ui.activity.MainActivity) this).rememberScreenForRecreate();
            ActivityCompat.recreate(this);
        }
    }

    @Override
    protected void onStop() {
        releaseBrowser();
        super.onStop();
    }

    // Drift: asked at most once per app start, and in Lite not on top of the first-launch questions
    private static boolean batteryAsked = false;

    private void checkBatteryOptimization() {
        if (batteryAsked) return;
        if (com.eddyizm.tempus.BuildConfig.LITE && !com.eddyizm.tempus.lite.LiteApp.INSTANCE.getOnboarded()) return;
        if (detectBatteryOptimization() && Preferences.askForOptimization()) {
            batteryAsked = true;
            showBatteryOptimizationDialog();
        }
    }

    private void checkPermission() {
        // One request for everything, since Android refuses a second request while the first is still open.
        List<String> missing = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.ACCESS_LOCAL_NETWORK);
            }
        }

        if (!missing.isEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), 101);
            // Set after the call, since a recreate's repeat request is refused inside it with empty arrays while the first is still open.
            localNetworkRequestOpen = missing.contains(Manifest.permission.ACCESS_LOCAL_NETWORK);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101) localNetworkRequestOpen = false;
    }

    protected boolean isLocalNetworkRequestOpen() {
        return localNetworkRequestOpen;
    }

    private void checkAlwaysOnDisplay() {
        if (Preferences.isDisplayAlwaysOn()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private boolean detectBatteryOptimization() {
        String packageName = getPackageName();
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        return !powerManager.isIgnoringBatteryOptimizations(packageName);
    }

    private void showBatteryOptimizationDialog() {
        BatteryOptimizationDialog dialog = new BatteryOptimizationDialog();
        dialog.show(getSupportFragmentManager(), null);
    }

    private void initializeBrowser() {
        mediaBrowserListenableFuture = new MediaBrowser.Builder(this, new SessionToken(this, new ComponentName(this, MediaService.class))).buildAsync();
    }

    private void releaseBrowser() {
        MediaBrowser.releaseFuture(mediaBrowserListenableFuture);
    }

    public ListenableFuture<MediaBrowser> getMediaBrowserListenableFuture() {
        return mediaBrowserListenableFuture;
    }

    private void initializeDownloader() {
        try {
            DownloadService.start(this, DownloaderService.class);
        } catch (IllegalStateException e) {
            DownloadService.startForeground(this, DownloaderService.class);
        }
    }

    // Same string ThemeHelper compares in onResume, so the two can never drift apart (a mismatch recreates the activity forever)
    private String generateThemeSignature() {
        return ThemeHelper.themeSignature();
    }

}
