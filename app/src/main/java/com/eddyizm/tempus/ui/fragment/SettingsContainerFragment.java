package com.eddyizm.tempus.ui.fragment;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.media.audiofx.AudioEffect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.offline.DownloadService;
import androidx.media3.exoplayer.scheduler.Requirements;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreference;

import com.eddyizm.tempus.BuildConfig;
import com.eddyizm.tempus.R;
import com.eddyizm.tempus.interfaces.DialogClickCallback;
import com.eddyizm.tempus.interfaces.ScanCallback;
import com.eddyizm.tempus.equalizer.EqualizerManager;
import com.eddyizm.tempus.equalizer.ParametricProfile;
import com.eddyizm.tempus.helper.ThemeHelper;
import com.eddyizm.tempus.service.DownloaderService;
import com.eddyizm.tempus.service.MediaService;
import com.eddyizm.tempus.ui.activity.MainActivity;
import com.eddyizm.tempus.ui.dialog.DeleteDownloadStorageDialog;
import com.eddyizm.tempus.ui.dialog.DownloadStorageDialog;
import com.eddyizm.tempus.ui.dialog.StarredAlbumSyncDialog;
import com.eddyizm.tempus.ui.dialog.StarredArtistSyncDialog;
import com.eddyizm.tempus.ui.dialog.StarredSyncDialog;
import com.eddyizm.tempus.ui.dialog.StreamingCacheStorageDialog;
import com.eddyizm.tempus.ui.login.LoginActivity;
import com.eddyizm.tempus.util.ClickablePreferenceCategory;
import com.eddyizm.tempus.util.DownloadUtil;
import com.eddyizm.tempus.subsonic.models.MusicFolder;
import com.eddyizm.tempus.util.ExternalAudioReader;
import com.eddyizm.tempus.util.Preferences;
import com.eddyizm.tempus.util.SettingsBackup;
import com.eddyizm.tempus.util.UIUtil;
import com.eddyizm.tempus.viewmodel.MainViewModel;
import com.eddyizm.tempus.viewmodel.SettingViewModel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@OptIn(markerClass = UnstableApi.class)
public class SettingsContainerFragment extends PreferenceFragmentCompat {
    // Drift Lite: the only settings that make sense without a server
    private static final java.util.Set<String> LITE_KEEP = new java.util.HashSet<>(java.util.Arrays.asList(
            "drift_follow_offline", "selected_equalizer", "eq_per_device", "system_equalizer", "builtin_equalizer",
            "parametric_eq_import", "loudness_preamp", "download_wifi_only", "auto_download_lyrics",
            "drift_accent_pick", "theme", "language", "drift_chart_country", "upnp_enabled", "always_on_display",
            "permissions", "settings_export", "settings_import", "settings_drive_backup", "version", "lite_profile"));


    private static final String TAG = "SettingsFragment";
    private MainActivity activity;

    private SettingViewModel settingViewModel;
    private MainViewModel mainViewModel;

    private ActivityResultLauncher<Intent> directoryPickerLauncher;

    private MediaService.LocalBinder mediaServiceBinder;
    private boolean isServiceBound = false;
    private boolean pendingEqualizerReload = false;
    private ActivityResultLauncher<Intent> equalizerResultLauncher;
    private ActivityResultLauncher<String[]> parametricEqPickerLauncher;
    private ActivityResultLauncher<String> settingsExportLauncher;
    private ActivityResultLauncher<String[]> settingsImportLauncher;

    private static final int PARAMETRIC_EQ_MAX_BYTES = 16 * 1024;
    private static final int SETTINGS_BACKUP_MAX_BYTES = 16 * 1024 * 1024; // Drift Lite: playlists and tracks too
    private static final String STATE_PENDING_EQUALIZER_RELOAD = "pending_equalizer_reload";

    private static final long SCAN_STATUS_POLL_INTERVAL_MS = 2000;
    private final Handler scanStatusHandler = new Handler(Looper.getMainLooper());

    private final Set<String> expandedCategories = new HashSet<>();

    private boolean musicFoldersObserved = false;

    // Null until the server answers with its folder list.
    private Integer musicFolderCount = null;

    private String searchQuery = "";

    // Drift: category picked from the Spotify-style entry screen (null = all)
    private String driftCategoryKey = null;

    public void setSearchQuery(String query) {
        this.searchQuery = UIUtil.normalizeForSearch(query).trim();
        applyAccordionState();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            pendingEqualizerReload = savedInstanceState.getBoolean(STATE_PENDING_EQUALIZER_RELOAD, false);
        }

        equalizerResultLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {}
        );

        parametricEqPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) importParametricEq(uri);
                });

        settingsExportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/json"),
                uri -> {
                    if (uri != null) exportSettings(uri);
                });

        settingsImportLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) importSettings(uri);
                });

        directoryPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        Intent data = result.getData();
                        if (data != null) {
                            Uri uri = data.getData();
                            if (uri != null) {
                                requireContext().getContentResolver().takePersistableUriPermission(
                                        uri,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                );

                                Preferences.setDownloadDirectoryUri(uri.toString());
                                ExternalAudioReader.refreshCache();
                                Toast.makeText(requireContext(), R.string.settings_download_folder_set, Toast.LENGTH_SHORT).show();
                                checkDownloadDirectory();
                            }
                        }
                    }
                });
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();

        View view = super.onCreateView(inflater, container, savedInstanceState);
        settingViewModel = new ViewModelProvider(requireActivity()).get(SettingViewModel.class);
        mainViewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);

        if (view != null) {
            getListView().setPadding(0, 0, 0, (int) getResources().getDimension(R.dimen.global_padding_bottom));
        }

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        activity.setBottomNavigationBarVisibility(false);
        activity.setBottomSheetVisibility(false);
    }

    // Drift: room at the bottom so the last items stay above the translucent bottom bar and mini player
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        androidx.recyclerview.widget.RecyclerView list = getListView();
        if (list != null) {
            list.setClipToPadding(false);
            list.setPadding(list.getPaddingLeft(), list.getPaddingTop(), list.getPaddingRight(),
                    Math.round(180 * getResources().getDisplayMetrics().density));
            // hidden until the section filter has run, otherwise every inherited setting flashes for a frame
            list.setAlpha(0f);
            // safety net: never leave the page blank if no filter pass comes
            list.postDelayed(() -> { if (list.getAlpha() < 1f) list.animate().alpha(1f).setDuration(120).start(); }, 700);
        }
    }

    // Drift: shows the list once the filtered items have been laid out
    private void revealList() {
        androidx.recyclerview.widget.RecyclerView list = getListView();
        if (list == null || list.getAlpha() == 1f) return;
        list.postDelayed(() -> list.animate().alpha(1f).setDuration(120).start(), 80);
    }

    @Override
    public void onResume() {
        super.onResume();

        expandedCategories.clear();
        if (driftCategoryKey != null) expandedCategories.add(driftCategoryKey);

        setStreamingCacheSize();
        setAppLanguage();
        setMusicLibrary();
        setVersion();
        setNetorkPingTimeoutBase();

        actionPermission();
        actionTheme();
        actionLogout();
        actionScan();
        actionSyncStarredAlbums();
        actionSyncStarredTracks();
        actionSyncStarredArtists();
        actionChangeStreamingCacheStorage();
        actionChangeDownloadStorage();
        actionSetSongPreloadBuffer();
        actionSetDownloadDirectory();
        actionDeleteDownloadStorage();
        actionKeepScreenOn();
        actionAutoDownloadLyrics();
        actionMiniPlayerHeart();
        actionLanRemote();
        actionSettingsBackup();

        bindMediaService();
        actionBuiltinEqualizer();
        actionEqualizerSelector();
        actionParametricEqImport();
        actionReplayGainPreamp();

        applyAccordionState();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        // An import recreates the screen before it can reach the player, so the reload is handed
        // to the next instance, which binds in onResume.
        outState.putBoolean(STATE_PENDING_EQUALIZER_RELOAD, pendingEqualizerReload);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        scanStatusHandler.removeCallbacksAndMessages(null);

        // The flag means "this view already has the observer", and a child fragment can outlive
        // its parent's view, so it has to be cleared with the view rather than the instance.
        musicFoldersObserved = false;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.global_preferences, rootKey);

        if (true) { // Drift: no GitHub updates (they would lead to Tempus)
            PreferenceCategory githubUpdateCategory = findPreference("settings_github_update_category_key");
            if (githubUpdateCategory != null) {
                getPreferenceScreen().removePreference(githubUpdateCategory);
            }
        }

        if (BuildConfig.FLAVOR.equals("degoogled")) {
            ClickablePreferenceCategory androidAutoCategory = findPreference("settings_androidauto_category_key");
            if (androidAutoCategory != null) {
                getPreferenceScreen().removePreference(androidAutoCategory);
            }
        }

        PreferenceScreen screen = getPreferenceScreen();
        if (screen != null) {
            for (int i = 0; i < screen.getPreferenceCount(); i++) {
                Preference pref = screen.getPreference(i);
                if (pref instanceof ClickablePreferenceCategory) {
                    ClickablePreferenceCategory category = (ClickablePreferenceCategory) pref;
                    if (category.getKey() == null) {
                        category.setKey("category_key_" + i);
                    }
                    category.setOnClickListener(cat -> {
                        String key = cat.getKey();
                        if ("lan_playback".equals(key)) {
                            startActivity(new Intent(requireContext(), com.eddyizm.tempus.lan.LanPlaybackActivity.class));
                            return;
                        }
                        if (expandedCategories.contains(key)) {
                            expandedCategories.remove(key);
                        } else {
                            expandedCategories.add(key);
                        }
                        applyAccordionState();
                    });
                }
            }
        }

        String driftCategory = getArguments() != null ? getArguments().getString(DriftSettingsHomeFragment.ARG_CATEGORY) : null;
        if (driftCategory != null && screen != null) {
            java.util.List<Preference> others = new java.util.ArrayList<>();
            for (int i = 0; i < screen.getPreferenceCount(); i++) {
                Preference pref = screen.getPreference(i);
                if (pref instanceof ClickablePreferenceCategory && pref.getTitle() != null
                        && driftCategory.contentEquals(pref.getTitle())) {
                    driftCategoryKey = pref.getKey();
                    // Opened on its own: no accordion header, the title is in the top bar
                    pref.setLayoutResource(R.layout.drift_pref_hidden_header);
                    applyIcons((androidx.preference.PreferenceGroup) pref);
                    ((ClickablePreferenceCategory) pref).setOnClickListener(null);
                } else {
                    others.add(pref);
                }
            }
        }

        Preference servers = findPreference("manage_servers");
        if (servers != null) {
            servers.setOnPreferenceClickListener(p -> {
                // Tempus server screen: add, edit (address, user, password), delete, test
                Intent i = new Intent(requireContext(), com.eddyizm.tempus.ui.login.LoginActivity.class);
                i.putExtra("HIDE_TAB_LAYOUT", true);
                i.putExtra("HIDE_TOPAPPBAR_LAYOUT", false);
                i.putExtra("SELECT_FRAGMENT", 3);
                startActivity(i);
                return true;
            });
        }

        // Drift: accent color, from the presets or with the picker
        Preference accent = findPreference("drift_accent_pick");
        if (accent != null) {
            accent.setSummary(com.eddyizm.tempus.helper.DriftAccent.name(com.eddyizm.tempus.helper.DriftAccent.getColor()));
            accent.setOnPreferenceClickListener(p -> { showAccentDialog(); return true; });
            getChildFragmentManager().setFragmentResultListener("dialog_result_key", this, (k, b) -> {
                String hex = b.getString("color_key");
                if (hex == null) return;
                try {
                    applyAccent(android.graphics.Color.parseColor(hex.replace("HEX:", "")));
                } catch (IllegalArgumentException ignored) { }
            });
        }

        // Drift: Undertow is configured here (build values only remain as defaults)
        androidx.preference.EditTextPreference uUrl = findPreference(com.eddyizm.tempus.helper.Undertow.KEY_URL);
        if (uUrl != null) {
            if (uUrl.getText() == null || uUrl.getText().isEmpty()) uUrl.setText(com.eddyizm.tempus.helper.Undertow.INSTANCE.getUrl());
            uUrl.setOnBindEditTextListener(e -> e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI));
        }
        androidx.preference.EditTextPreference uToken = findPreference(com.eddyizm.tempus.helper.Undertow.KEY_TOKEN);
        if (uToken != null) {
            uToken.setOnBindEditTextListener(e -> e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD));
            uToken.setSummaryProvider(p -> com.eddyizm.tempus.helper.Undertow.INSTANCE.getToken().isEmpty() ? getString(R.string.drift_undertow_token_unset) : getString(R.string.drift_undertow_token_set));
        }
        Preference uTest = findPreference("undertow_test");
        if (uTest != null) uTest.setOnPreferenceClickListener(p -> {
            p.setSummary(getString(R.string.drift_undertow_testing));
            new Thread(() -> {
                String err = com.eddyizm.tempus.helper.Undertow.check();
                if (isAdded()) requireActivity().runOnUiThread(() -> p.setSummary(err == null ? getString(R.string.drift_undertow_connected) : getString(R.string.drift_undertow_no_answer, err)));
            }).start();
            return true;
        });
        Preference uPanel = findPreference("undertow_panel");
        if (uPanel != null) uPanel.setOnPreferenceClickListener(p -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(com.eddyizm.tempus.helper.Undertow.panelUrl())));
            } catch (Exception e) {
                android.widget.Toast.makeText(requireContext(), getString(R.string.drift_no_browser), android.widget.Toast.LENGTH_SHORT).show();
            }
            return true;
        });

        initChartCountry();
        initDriveBackup();

        // Drift Lite: no Undertow, only offline for followed playlists remains
        if (com.eddyizm.tempus.BuildConfig.LITE) {
            for (String k : new String[]{com.eddyizm.tempus.helper.Undertow.KEY_URL, com.eddyizm.tempus.helper.Undertow.KEY_TOKEN, "undertow_test", "undertow_panel",
                    "manage_servers", "logout"}) {
                Preference x = findPreference(k);
                if (x != null) x.setVisible(false);
            }
            // only the items that work without a server: everything else goes away, along with sections left empty
            java.util.Set<String> keep = LITE_KEEP;
            liteFilter(getPreferenceScreen(), keep);
            Preference profile = findPreference("lite_profile");
            if (profile != null) {
                profile.setVisible(true);
                profile.setOnPreferenceClickListener(p -> {
                    com.eddyizm.tempus.lite.LiteApp.INSTANCE.setOnboarded(false);
                    requireActivity().recreate();
                    return true;
                });
            }
        }

        Preference lanOpen = findPreference("lan_playback");
        if (lanOpen != null) {
            lanOpen.setOnPreferenceClickListener(p -> {
                startActivity(new Intent(requireContext(), com.eddyizm.tempus.lan.LanPlaybackActivity.class));
                return true;
            });
        }

        SwitchPreference downloadWifiOnlyPreference = findPreference("download_wifi_only");
        if (downloadWifiOnlyPreference != null) {
            downloadWifiOnlyPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                if (newValue instanceof Boolean) {
                    boolean enabled = (Boolean) newValue;
                    Preferences.setDownloadWifiOnly(enabled);
                    Requirements requirements = enabled
                            ? new Requirements(Requirements.NETWORK_UNMETERED)
                            : new Requirements(0);
                    DownloadService.sendSetRequirements(requireContext(), DownloaderService.class, requirements, false);
                }
                return true;
            });
        }
    }

    private void applyAccordionState() {
        PreferenceScreen screen = getPreferenceScreen();
        if (screen == null) return;

        resetVisibility(screen);

        checkSystemEqualizer();
        checkCacheStorage();
        checkStorage();
        checkDownloadDirectory();
        checkEqualizerBands();
        checkMusicLibrary();
        checkLanPlayback();

        if (!searchQuery.isEmpty()) {
            filterPreferences(screen);
        } else {
            for (int i = 0; i < screen.getPreferenceCount(); i++) {
                Preference pref = screen.getPreference(i);
                if (pref instanceof PreferenceCategory) {
                    PreferenceCategory category = (PreferenceCategory) pref;
                    boolean expanded = expandedCategories.contains(category.getKey());
                    category.setIcon(expanded ? R.drawable.ic_arrow_down : R.drawable.ic_navigate_next);
                    if (!expanded) {
                        for (int j = 0; j < category.getPreferenceCount(); j++) {
                            category.getPreference(j).setVisible(false);
                        }
                    }
                }
            }
        }

        // Drift: only one category shown, the others stay in the model but hidden
        if (driftCategoryKey != null) {
            for (int i = 0; i < screen.getPreferenceCount(); i++) {
                Preference top = screen.getPreference(i);
                top.setVisible(driftCategoryKey.equals(top.getKey()));
            }
        }

        if (com.eddyizm.tempus.BuildConfig.LITE) liteFilter(screen, LITE_KEEP);
        if (getListView() != null && getListView().getAdapter() != null) {
            getListView().getAdapter().notifyDataSetChanged();
        }
        revealList();
    }

    // Drift: one icon per item, uniform tint; descriptive items without a key stay aligned with the text
    private static final java.util.Map<String, Integer> ICONS = new java.util.HashMap<>();
    static {
        ICONS.put("logout", R.drawable.ds_logout);
        ICONS.put("drift_accent_pick", R.drawable.ds_palette);
        ICONS.put("drift_chart_country", R.drawable.ds_globe);
        ICONS.put("manage_servers", R.drawable.ds_server);
        ICONS.put("continuous_play", R.drawable.ds_infinite);
        ICONS.put("number_tracks_keep_in_queue", R.drawable.ds_queue);
        ICONS.put("fallback_to_random_tracks", R.drawable.ds_shuffle);
        ICONS.put("min_star_rating", R.drawable.ds_star);
        ICONS.put("custom_command_first_button", R.drawable.ds_bell);
        ICONS.put("custom_command_second_button", R.drawable.ds_bell);
        ICONS.put("audio_transcode_priority", R.drawable.ds_server);
        ICONS.put("audio_transcode_format_wifi", R.drawable.ds_wifi);
        ICONS.put("max_bitrate_wifi", R.drawable.ds_speed);
        ICONS.put("audio_transcode_format_mobile", R.drawable.ds_cell);
        ICONS.put("max_bitrate_mobile", R.drawable.ds_speed);
        ICONS.put("audio_transcode_download", R.drawable.ds_download);
        ICONS.put("audio_transcode_download_priority", R.drawable.ds_server);
        ICONS.put("audio_transcode_format_download", R.drawable.ds_note);
        ICONS.put("max_bitrate_download", R.drawable.ds_speed);
        ICONS.put("selected_equalizer", R.drawable.ds_eqlist);
        ICONS.put("eq_per_device", R.drawable.ds_eqlist); // Drift: per-output equalizer
        ICONS.put("system_equalizer", R.drawable.ds_sound);
        ICONS.put("builtin_equalizer", R.drawable.ds_sound);
        ICONS.put("parametric_eq_import", R.drawable.ds_upload);
        ICONS.put("replay_gain_mode", R.drawable.ds_volume);
        ICONS.put("replay_gain_prevent_clipping", R.drawable.ds_volume);
        ICONS.put("replay_gain_preamp", R.drawable.ds_volume);
        ICONS.put("loudness_preamp", R.drawable.ds_volume);
        ICONS.put("upnp_enabled", R.drawable.ic_drift_speaker);
        ICONS.put("lan_remote_enabled", R.drawable.ds_remote);
        ICONS.put("lan_playback", R.drawable.ds_phone);
        ICONS.put("androidauto_first_tab", R.drawable.ds_tab);
        ICONS.put("androidauto_second_tab", R.drawable.ds_tab);
        ICONS.put("androidauto_third_tab", R.drawable.ds_tab);
        ICONS.put("androidauto_fourth_tab", R.drawable.ds_tab);
        ICONS.put("androidauto_home_view", R.drawable.ds_grid);
        ICONS.put("androidauto_album_view", R.drawable.ds_grid);
        ICONS.put("androidauto_playlist_view", R.drawable.ds_grid);
        ICONS.put("androidauto_radio_view", R.drawable.ds_grid);
        ICONS.put("androidauto_podcast_view", R.drawable.ds_grid);
        ICONS.put("androidauto_shuffle_genre_songs", R.drawable.ds_shuffle);
        ICONS.put("androidauto_shuffle_starred_tracks", R.drawable.ds_shuffle);
        ICONS.put("androidauto_shuffle_playlists", R.drawable.ds_shuffle);
        ICONS.put("androidauto_shuffle_downloaded_tracks", R.drawable.ds_shuffle);
        ICONS.put("androidauto_starred_for_made_for_you", R.drawable.ds_star);
        // Drift: Undertow nodes in Android Auto
        ICONS.put("androidauto_drift_mixes", R.drawable.ds_nav_mix);
        ICONS.put("androidauto_drift_followed", R.drawable.ds_heart);
        ICONS.put("androidauto_drift_charts", R.drawable.ds_numbers);
        ICONS.put("scan_library", R.drawable.ds_refresh);
        ICONS.put("active_music_folder_id", R.drawable.ds_folder);
        ICONS.put("home_sort_playlists", R.drawable.ds_sort);
        ICONS.put("allow_playlist_duplicates", R.drawable.ds_queue);
        ICONS.put("artist_sort_by_album_count", R.drawable.ds_person);
        ICONS.put("sort_search_chronologically", R.drawable.ds_history);
        ICONS.put("music_directory_section_visibility", R.drawable.ds_folder);
        ICONS.put("podcast_section_visibility", R.drawable.ds_radio);
        ICONS.put("radio_section_visibility", R.drawable.ds_radio);
        ICONS.put("wifi_only", R.drawable.ds_wifi);
        ICONS.put("data_saving_mode", R.drawable.ds_datasaver);
        ICONS.put("download_wifi_only", R.drawable.ds_wifi);
        ICONS.put("sync_starred_tracks_for_offline_use", R.drawable.ds_heart);
        ICONS.put("sync_starred_albums_for_offline_use", R.drawable.ds_album);
        ICONS.put("sync_starred_artists_for_offline_use", R.drawable.ds_person);
        ICONS.put("auto_download_lyrics", R.drawable.ds_lyrics);
        ICONS.put("song_preload_buffer", R.drawable.ds_cached);
        ICONS.put("precache_tracks_count", R.drawable.ds_cached);
        ICONS.put("precache_wifi_only", R.drawable.ds_wifi);
        ICONS.put("download_storage", R.drawable.ds_storage);
        ICONS.put("set_download_directory", R.drawable.ds_folder);
        ICONS.put("delete_download_storage", R.drawable.ds_delete);
        ICONS.put("streaming_cache_size", R.drawable.ds_cached);
        ICONS.put("streaming_cache_storage", R.drawable.ds_storage);
        ICONS.put("image_cache_size", R.drawable.ds_image);
        ICONS.put("image_size", R.drawable.ds_image);
        ICONS.put("theme", R.drawable.ds_theme);
        ICONS.put("language", R.drawable.ds_language);
        ICONS.put("tile_size", R.drawable.ds_grid);
        ICONS.put("rounded_corner", R.drawable.ds_rounded);
        ICONS.put("rounded_corner_size", R.drawable.ds_rounded);
        ICONS.put("album_detail", R.drawable.ds_album);
        ICONS.put("audio_quality_per_item", R.drawable.ds_hq);
        ICONS.put("track_number_visible", R.drawable.ds_numbers);
        ICONS.put("album_year_visible", R.drawable.ds_event);
        ICONS.put("rating_per_item", R.drawable.ds_star);
        ICONS.put("song_rating_per_item", R.drawable.ds_star);
        ICONS.put("always_on_display", R.drawable.ds_phone);
        ICONS.put("enable_drawer_on_portrait", R.drawable.ds_menu);
        ICONS.put("hide_bottom_navbar_on_portrait", R.drawable.ds_visibility);
        ICONS.put("scrobbling", R.drawable.ds_history);
        ICONS.put("share", R.drawable.ds_share);
        ICONS.put("queue_syncing", R.drawable.ds_sync);
        ICONS.put("network_ping_timeout_base", R.drawable.ds_timer);
        ICONS.put("permissions", R.drawable.ds_lock);
        ICONS.put("settings_export", R.drawable.ds_upload);
        ICONS.put("settings_import", R.drawable.ds_restore);
        ICONS.put("version", R.drawable.ds_info);
        ICONS.put("github_update_check", R.drawable.ds_sync);
    }

    private void applyIcons(PreferenceGroup group) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference p = group.getPreference(i);
            p.setIconSpaceReserved(true);
            Integer id = p.getKey() != null ? ICONS.get(p.getKey()) : null;
            if (id == null && p.getIntent() != null) id = R.drawable.ds_link;
            if (id != null && !(p instanceof PreferenceCategory)) {
                android.graphics.drawable.Drawable d = androidx.appcompat.content.res.AppCompatResources.getDrawable(requireContext(), id);
                if (d != null) {
                    d = d.mutate();
                    // Drift: light grey on dark, dark grey on light
                    d.setTint(com.eddyizm.tempus.ui.components.DriftColors.getDark() ? 0xFFDADAE0 : 0xFF4A4A55);
                    p.setIcon(d);
                }
            }
            if (p instanceof PreferenceGroup) applyIcons((PreferenceGroup) p);
        }
    }

    private void resetVisibility(PreferenceGroup group) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference pref = group.getPreference(i);
            pref.setVisible(true);
            if (pref instanceof PreferenceCategory) {
                pref.setIcon(null);
            }
            if (pref instanceof PreferenceGroup) {
                resetVisibility((PreferenceGroup) pref);
            }
        }
    }

    private boolean filterPreferences(PreferenceGroup group) {
        return filterPreferences(group, false);
    }

    private boolean filterPreferences(PreferenceGroup group, boolean forceVisible) {
        boolean hasVisibleChild = false;
        boolean isRoot = group == getPreferenceScreen();
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference pref = group.getPreference(i);
            boolean matches = forceVisible;

            if (!matches) {
                String title = pref.getTitle() != null ? UIUtil.normalizeForSearch(pref.getTitle().toString()) : "";
                String summary = pref.getSummary() != null ? UIUtil.normalizeForSearch(pref.getSummary().toString()) : "";
                if (title.contains(searchQuery) || summary.contains(searchQuery)) {
                    matches = true;
                }
            }

            if (pref instanceof PreferenceGroup) {
                boolean wasVisible = pref.isVisible();
                boolean childMatches = filterPreferences((PreferenceGroup) pref, matches && wasVisible);
                boolean groupVisible = (matches || childMatches) && wasVisible;
                pref.setVisible(groupVisible);
                if (pref instanceof PreferenceCategory && isRoot) {
                    pref.setIcon(R.drawable.ic_arrow_down);
                }
                if (groupVisible) {
                    hasVisibleChild = true;
                }
            } else {
                boolean wasVisible = pref.isVisible();
                boolean shouldBeVisible = matches && wasVisible;
                pref.setVisible(shouldBeVisible);
                if (shouldBeVisible) {
                    hasVisibleChild = true;
                }
            }
        }
        return hasVisibleChild;
    }

    private void checkSystemEqualizer() {
        Preference equalizer = findPreference("system_equalizer");
        Preference builtinEqualizer = findPreference("builtin_equalizer");

        int selectedEqValue = Preferences.getSelectedEqualizer();

        if (builtinEqualizer != null) {
            builtinEqualizer.setVisible(selectedEqValue == 1);
        }

        Preference parametricEqImport = findPreference("parametric_eq_import");
        if (parametricEqImport != null) {
            parametricEqImport.setVisible(selectedEqValue == 3);
        }

        if (equalizer != null) {
            Intent intent = new Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL);

            if ((intent.resolveActivity(requireActivity().getPackageManager()) != null)) {
                equalizer.setOnPreferenceClickListener(preference -> {
                    equalizerResultLauncher.launch(intent);
                    return true;
                });
                equalizer.setVisible(selectedEqValue == 2);
            } else {
                equalizer.setVisible(false);
            }
        }
    }

    private void checkCacheStorage() {
        Preference storage = findPreference("streaming_cache_storage");

        if (storage == null) return;

        try {
            if (requireContext().getExternalFilesDirs(null)[1] == null) {
                storage.setVisible(false);
            } else {
                storage.setSummary(Preferences.getStreamingCacheStoragePreference() == 0 ? R.string.download_storage_internal_dialog_negative_button : R.string.download_storage_external_dialog_positive_button);
            }
        } catch (Exception exception) {
            storage.setVisible(false);
        }
    }

    private void checkStorage() {
        Preference storage = findPreference("download_storage");

        if (storage == null) return;

        try {
            if (requireContext().getExternalFilesDirs(null)[1] == null) {
                storage.setVisible(false);
            } else {
                int pref = Preferences.getDownloadStoragePreference();
                if (pref == 0) {
                    storage.setSummary(R.string.download_storage_internal_dialog_negative_button);
                } else if (pref == 1) {
                    storage.setSummary(R.string.download_storage_external_dialog_positive_button);
                } else {
                    storage.setSummary(R.string.download_storage_directory_dialog_neutral_button);
                }
            }
        } catch (Exception exception) {
            storage.setVisible(false);
        }
    }

    private void checkDownloadDirectory() {
        Preference storage = findPreference("download_storage");
        Preference directory = findPreference("set_download_directory");

        if (directory == null) return;

        String current = Preferences.getDownloadDirectoryUri();
        if (current != null) {
            if (storage != null) storage.setVisible(false);
            directory.setVisible(true);
            directory.setIcon(R.drawable.ic_close);
            directory.setTitle(R.string.settings_clear_download_folder);
            directory.setSummary(current);
        } else {
            if (storage != null) storage.setVisible(true);
            if (Preferences.getDownloadStoragePreference() == 2) {
                directory.setVisible(true);
                directory.setIcon(R.drawable.ic_folder);
                directory.setTitle(R.string.settings_set_download_folder);
                directory.setSummary(R.string.settings_choose_download_folder);
            } else {
                directory.setVisible(false);
            }
        }
    }

    private void setNetorkPingTimeoutBase() {
        EditTextPreference networkPingTimeoutBase = findPreference("network_ping_timeout_base");

        if (networkPingTimeoutBase != null) {
            networkPingTimeoutBase.setSummaryProvider(EditTextPreference.SimpleSummaryProvider.getInstance());
            networkPingTimeoutBase.setOnBindEditTextListener(editText -> {
                editText.setInputType(InputType.TYPE_CLASS_NUMBER);
                editText.setFilters(new InputFilter[]{ (source, start, end, dest, dstart, dend) -> {
                    for (int i = start; i < end; i++) {
                        if (!Character.isDigit(source.charAt(i))) {
                            return "";
                        }
                    }
                    return null;
                }});
            });

            networkPingTimeoutBase.setOnPreferenceChangeListener((preference, newValue) -> {
                String input = (String) newValue;
                return input != null && !input.isEmpty();
            });
        }
    }

    private void setStreamingCacheSize() {
        ListPreference streamingCachePreference = findPreference("streaming_cache_size");

        if (streamingCachePreference != null) {
            streamingCachePreference.setSummaryProvider(new Preference.SummaryProvider<ListPreference>() {
                @Nullable
                @Override
                public CharSequence provideSummary(@NonNull ListPreference preference) {
                    CharSequence entry = preference.getEntry();

                    if (entry == null) return null;

                    long currentSizeMb = DownloadUtil.getStreamingCacheSize(requireActivity()) / (1024 * 1024);

                    return getString(R.string.settings_summary_streaming_cache_size, entry, String.valueOf(currentSizeMb));
                }
            });
        }
    }

    private void setAppLanguage() {
        ListPreference localePref = (ListPreference) findPreference("language");
        if (localePref == null) return;

        Map<String, String> locales = UIUtil.getLangPreferenceDropdownEntries(requireContext());

        CharSequence[] entries = locales.keySet().toArray(new CharSequence[locales.size()]);
        CharSequence[] entryValues = locales.values().toArray(new CharSequence[locales.size()]);

        localePref.setEntries(entries);
        localePref.setEntryValues(entryValues);

        String value = localePref.getValue();
        if ("default".equals(value)) {
            localePref.setSummary(requireContext().getString(R.string.settings_system_language));
        } else {
            localePref.setSummary(Locale.forLanguageTag(value).getDisplayName());
        }

        localePref.setOnPreferenceChangeListener((preference, newValue) -> {
            if ("default".equals(newValue)) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList());
                preference.setSummary(requireContext().getString(R.string.settings_system_language));
            } else {
                LocaleListCompat appLocale = LocaleListCompat.forLanguageTags((String) newValue);
                AppCompatDelegate.setApplicationLocales(appLocale);
                preference.setSummary(Locale.forLanguageTag((String) newValue).getDisplayName());
            }
            return true;
        });
    }

    private void setMusicLibrary() {
        ListPreference libraryPref = (ListPreference) findPreference("active_music_folder_id");
        if (libraryPref == null) return;

        // Opening a ListPreference whose entries are still null throws, and the server folders are
        // asynchronous and may never arrive, so seed the one entry that needs no server.
        if (libraryPref.getEntries() == null) {
            libraryPref.setEntries(new CharSequence[]{getString(R.string.settings_music_library_all)});
            libraryPref.setEntryValues(new CharSequence[]{Preferences.MUSIC_FOLDER_ALL});
        }

        // The widget's own key is shared by every server, so left alone it would show whichever
        // library was picked last, anywhere. Load this server's choice in so the screen cannot
        // claim a filter that is not being applied.
        String activeMusicFolderId = Preferences.getActiveMusicFolderId();
        libraryPref.setValue(activeMusicFolderId != null ? activeMusicFolderId : Preferences.MUSIC_FOLDER_ALL);

        setMusicLibrarySummary(libraryPref);

        libraryPref.setOnPreferenceChangeListener((preference, newValue) -> {
            libraryPref.setValue((String) newValue);
            mainViewModel.setActiveMusicFolderId((String) newValue);
            setMusicLibrarySummary(libraryPref);
            return true;
        });

        // onResume runs this on every return, and the call below is what fires the request, so ask
        // once per view. A failed load is then not retried until the screen is left and reopened,
        // which beats enqueueing another request and observer on every resume while a server is down.
        if (musicFoldersObserved) return;
        musicFoldersObserved = true;

        settingViewModel.getMusicFolders(getViewLifecycleOwner()).observe(getViewLifecycleOwner(), musicFolderObserver);
    }

    private final Observer<List<MusicFolder>> musicFolderObserver = new Observer<List<MusicFolder>>() {
        @Override
        public void onChanged(List<MusicFolder> folders) {
            ListPreference libraryPref = (ListPreference) findPreference("active_music_folder_id");
            if (libraryPref == null || folders == null) return;

            List<CharSequence> entries = new ArrayList<>();
            List<CharSequence> entryValues = new ArrayList<>();

            entries.add(getString(R.string.settings_music_library_all));
            entryValues.add(Preferences.MUSIC_FOLDER_ALL);

            List<String> ids = new ArrayList<>();
            for (MusicFolder folder : folders) {
                if (folder.getId() == null) continue;
                ids.add(folder.getId());
                entries.add(folder.getName() != null ? folder.getName() : folder.getId());
                entryValues.add(folder.getId());
            }

            libraryPref.setEntries(entries.toArray(new CharSequence[0]));
            libraryPref.setEntryValues(entryValues.toArray(new CharSequence[0]));

            // A library removed, or one this account can no longer see, leaves a stale id that
            // would filter everything away. Discarding the choice is permanent though, so it takes
            // positive evidence: other libraries came back and this one was not among them. An
            // empty response is not that, and a server offering none shows nothing either way.
            String storedMusicFolderId = Preferences.getActiveMusicFolderId();
            if (storedMusicFolderId != null && !ids.isEmpty() && !ids.contains(storedMusicFolderId)) {
                libraryPref.setValue(Preferences.MUSIC_FOLDER_ALL);
                mainViewModel.setActiveMusicFolderId(null);
            }

            musicFolderCount = ids.size();
            applyAccordionState();

            setMusicLibrarySummary(libraryPref);
        }
    };

    private void checkLanPlayback() {
        Preference lanPref = findPreference("lan_playback");
        if (lanPref != null) lanPref.setVisible(Preferences.isLanRemoteEnabled());
    }

    private void actionLanRemote() {
        SwitchPreference preference = findPreference(Preferences.LAN_REMOTE_ENABLED);
        if (preference == null) return;

        preference.setOnPreferenceChangeListener((pref, newValue) -> {
            if (!Boolean.TRUE.equals(newValue)) {
                // Turning it off ends whatever is running: a controlled device, an open pairing, the listener.
                com.eddyizm.tempus.lan.LanRemoteSession.disconnect();
                com.eddyizm.tempus.lan.LanReceiverService.Companion.closePairing();
                requireContext().stopService(new Intent(requireContext(), com.eddyizm.tempus.lan.LanReceiverService.class));
            }
            // Posted so the new value is saved before checkLanPlayback reads it, and run through
            // applyAccordionState so an active search filter still applies to the row it reveals.
            getListView().post(() -> {
                if (getView() != null) applyAccordionState();
            });
            return true;
        });
    }

    /**
     * Below two libraries there is nothing to choose between, so the setting is noise.
     *
     * Call applyAccordionState rather than this, including from the folder response. That method
     * resets every category child to visible, runs these checks, and only then re-hides the
     * children of collapsed categories. Calling this alone performs only the middle step, so a row
     * it reveals would stay showing under a category the user has collapsed.
     */
    private void checkMusicLibrary() {
        Preference libraryPref = findPreference("active_music_folder_id");
        if (libraryPref == null) return;

        // Visible while a filter is in force, since hiding it would remove the only way to clear
        // one. Also visible on a null count, which means the server has not answered yet or at all:
        // showing a control with nothing to choose between beats hiding one that was needed.
        libraryPref.setVisible(musicFolderCount == null
                || musicFolderCount > 1
                || Preferences.getActiveMusicFolderId() != null);
    }

    private void setMusicLibrarySummary(ListPreference libraryPref) {
        CharSequence entry = libraryPref.getEntry();
        if (entry != null) {
            libraryPref.setSummary(entry);
            return;
        }

        // getEntry() is null while the value is not among the entries: the folder list has not
        // arrived, or it arrived without enough evidence to clear a stale id. Saying "all
        // libraries" would contradict what is still being sent, so name the id, poorly as it reads.
        String activeMusicFolderId = Preferences.getActiveMusicFolderId();
        libraryPref.setSummary(activeMusicFolderId != null
                ? activeMusicFolderId
                : getString(R.string.settings_music_library_all));
    }

    private void setVersion() {
        findPreference("version").setSummary(BuildConfig.VERSION_NAME);
    }

    private void actionPermission() {
        findPreference("permissions").setOnPreferenceClickListener( preference -> {
            Intent tempus = new Intent(requireActivity(), LoginActivity.class);
            tempus.putExtra("HIDE_TAB_LAYOUT", true);
            tempus.putExtra("HIDE_TOPAPPBAR_LAYOUT", false);
            tempus.putExtra("SELECT_FRAGMENT", 1);
            startActivity(tempus);
            return true;
        });
    }

    private void actionTheme() {
        // Drift: the current choice under the title (System default / Light / Dark, plus true black)
        Preference themePref = findPreference("theme");
        if (themePref != null) {
            String[] titles = getResources().getStringArray(R.array.theme_list_titles);
            int idx = java.util.Arrays.asList(getResources().getStringArray(R.array.theme_list_values)).indexOf(Preferences.getTheme());
            String summary = idx >= 0 ? titles[idx] : titles[titles.length - 1];
            if (Preferences.isDarkThemeBlack() && !"light".equals(Preferences.getTheme())) summary += " · " + getString(R.string.drift_true_black);
            themePref.setSummary(summary);
        }
        findPreference("theme").setOnPreferenceClickListener( preference -> {
            Intent tempus = new Intent(requireActivity(), LoginActivity.class);
            tempus.putExtra("HIDE_TAB_LAYOUT", true);
            tempus.putExtra("HIDE_TOPAPPBAR_LAYOUT", false);
            tempus.putExtra("SELECT_FRAGMENT", 2);
            startActivity(tempus);
            return true;
        });
    }
    private void actionLogout() {
        findPreference("logout").setOnPreferenceClickListener(preference -> {
            activity.quit();
            return true;
        });
    }

    private void actionScan() {
        findPreference("scan_library").setOnPreferenceClickListener(preference -> {
            settingViewModel.launchScan(new ScanCallback() {
                @Override
                public void onError(Exception exception) {
                    if (getView() == null) return;
                    findPreference("scan_library").setSummary(exception.getMessage());
                }

                @Override
                public void onSuccess(boolean isScanning, long count) {
                    if (getView() == null) return;
                    findPreference("scan_library").setSummary(getString(R.string.settings_scan_result, count));
                    if (isScanning) scheduleScanStatusPoll();
                }
            });

            return true;
        });
    }

    private void actionSyncStarredTracks() {
        findPreference("sync_starred_tracks_for_offline_use").setOnPreferenceChangeListener((preference, newValue) -> {
            if (newValue instanceof Boolean) {
                if ((Boolean) newValue) {
                    StarredSyncDialog dialog = new StarredSyncDialog(() -> {
                        ((SwitchPreference)preference).setChecked(false);
                    });
                    dialog.show(activity.getSupportFragmentManager(), null);
                }
            }
            return true;
        });
    }

    private void actionSyncStarredAlbums() {
        findPreference("sync_starred_albums_for_offline_use").setOnPreferenceChangeListener((preference, newValue) -> {
            if (newValue instanceof Boolean) {
                if ((Boolean) newValue) {
                    StarredAlbumSyncDialog dialog = new StarredAlbumSyncDialog(() -> {
                        ((SwitchPreference)preference).setChecked(false);
                    });
                    dialog.show(activity.getSupportFragmentManager(), null);
                }
            }
            return true;
        });
    }

    private void actionSyncStarredArtists() {
        findPreference("sync_starred_artists_for_offline_use").setOnPreferenceChangeListener((preference, newValue) -> {
            if (newValue instanceof Boolean) {
                if ((Boolean) newValue) {
                    StarredArtistSyncDialog dialog = new StarredArtistSyncDialog(() -> {
                        ((SwitchPreference)preference).setChecked(false);
                    });
                    dialog.show(activity.getSupportFragmentManager(), null);
                }
            }
            return true;
        });
    }

    private void actionChangeStreamingCacheStorage() {
        findPreference("streaming_cache_storage").setOnPreferenceClickListener(preference -> {
            StreamingCacheStorageDialog dialog = new StreamingCacheStorageDialog(new DialogClickCallback() {
                @Override
                public void onPositiveClick() {
                    findPreference("streaming_cache_storage").setSummary(R.string.streaming_cache_storage_external_dialog_positive_button);
                }

                @Override
                public void onNegativeClick() {
                    findPreference("streaming_cache_storage").setSummary(R.string.streaming_cache_storage_internal_dialog_negative_button);
                }
            });
            dialog.show(activity.getSupportFragmentManager(), null);
            return true;
        });
    }

    private void actionChangeDownloadStorage() {
        findPreference("download_storage").setOnPreferenceClickListener(preference -> {
            DownloadStorageDialog dialog = new DownloadStorageDialog(new DialogClickCallback() {
                @Override
                public void onPositiveClick() {
                    findPreference("download_storage").setSummary(R.string.download_storage_external_dialog_positive_button);
                    checkDownloadDirectory();
                }

                @Override
                public void onNegativeClick() {
                    findPreference("download_storage").setSummary(R.string.download_storage_internal_dialog_negative_button);
                    checkDownloadDirectory();
                }

                @Override
                public void onNeutralClick() {
                    findPreference("download_storage").setSummary(R.string.download_storage_directory_dialog_neutral_button);
                    checkDownloadDirectory();
                }
            });
            dialog.show(activity.getSupportFragmentManager(), null);
            return true;
        });
    }

    private void actionSetSongPreloadBuffer() {
        ListPreference pref = findPreference("song_preload_buffer");

        Context ctx = getContext();
        Resources res = getResources();

        String[] titles = res.getStringArray(R.array.song_preload_buffer_titles);
        String[] values = res.getStringArray(R.array.song_preload_buffer_values);

        final int DEFAULT_IDX = 2; // 60 seconds
        final String DEFAULT_VAL = values[DEFAULT_IDX];

        String selectedValue = PreferenceManager
                .getDefaultSharedPreferences(ctx)
                .getString("song_preload_buffer", DEFAULT_VAL);

        int valueIdx = Arrays.asList(values).indexOf(selectedValue);
        int titleIdx = (valueIdx == -1) ? DEFAULT_IDX : valueIdx;

        pref.setSummary(
                ctx.getString(R.string.settings_song_preload_buffer_summary,
                        titles[titleIdx])
        );
    }

    private void actionSetDownloadDirectory() {
        Preference pref = findPreference("set_download_directory");
        if (pref != null) {
            pref.setOnPreferenceClickListener(preference -> {
                String current = Preferences.getDownloadDirectoryUri();

                if (current != null) {
                    Preferences.setDownloadDirectoryUri(null);
                    Preferences.setDownloadStoragePreference(0);
                    ExternalAudioReader.refreshCache();
                    Toast.makeText(requireContext(), R.string.settings_download_folder_cleared, Toast.LENGTH_SHORT).show();
                    checkStorage();
                    checkDownloadDirectory();
                } else {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            | Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    directoryPickerLauncher.launch(intent);
                }
                return true;
            });
        }
    }

    private void actionDeleteDownloadStorage() {
        findPreference("delete_download_storage").setOnPreferenceClickListener(preference -> {
            DeleteDownloadStorageDialog dialog = new DeleteDownloadStorageDialog();
            dialog.show(activity.getSupportFragmentManager(), null);
            return true;
        });
    }

    private void actionMiniPlayerHeart() {
        SwitchPreference preference = findPreference("mini_shuffle_button_visibility");
        if (preference == null) {
            return;
        }

        preference.setChecked(Preferences.showShuffleInsteadOfHeart());
        preference.setOnPreferenceChangeListener((pref, newValue) -> {
            if (newValue instanceof Boolean) {
                Preferences.setShuffleInsteadOfHeart((Boolean) newValue);
            }
            return true;
        });
    }

    private void actionAutoDownloadLyrics() {
        SwitchPreference preference = findPreference("auto_download_lyrics");
        if (preference == null) {
            return;
        }

        preference.setChecked(Preferences.isAutoDownloadLyricsEnabled());
        preference.setOnPreferenceChangeListener((pref, newValue) -> {
            if (newValue instanceof Boolean) {
                Preferences.setAutoDownloadLyricsEnabled((Boolean) newValue);
            }
            return true;
        });
    }

    private void scheduleScanStatusPoll() {
        scanStatusHandler.removeCallbacksAndMessages(null);
        scanStatusHandler.postDelayed(this::getScanStatus, SCAN_STATUS_POLL_INTERVAL_MS);
    }

    private void getScanStatus() {
        settingViewModel.getScanStatus(new ScanCallback() {
            @Override
            public void onError(Exception exception) {
                if (getView() == null) return;
                findPreference("scan_library").setSummary(exception.getMessage());
            }

            @Override
            public void onSuccess(boolean isScanning, long count) {
                if (getView() == null) return;
                findPreference("scan_library").setSummary(getString(R.string.settings_scan_result, count));
                if (isScanning) scheduleScanStatusPoll();
            }
        });
    }

    private void actionKeepScreenOn() {
        findPreference("always_on_display").setOnPreferenceChangeListener((preference, newValue) -> {
            if (newValue instanceof Boolean) {
                if ((Boolean) newValue) {
                    activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            }
            return true;
        });
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mediaServiceBinder = (MediaService.LocalBinder) service;
            isServiceBound = true;
            if (pendingEqualizerReload) {
                mediaServiceBinder.reloadEqualizer();
                pendingEqualizerReload = false;
            }
            checkEqualizerBands();
            applyAccordionState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mediaServiceBinder = null;
            isServiceBound = false;
        }
    };

    private void bindMediaService() {
        Intent intent = new Intent(requireActivity(), MediaService.class);
        intent.setAction(MediaService.ACTION_BIND_EQUALIZER);
        requireActivity().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        isServiceBound = true;
    }

    private void checkEqualizerBands() {
        if (mediaServiceBinder != null) {
            EqualizerManager eqManager = mediaServiceBinder.getEqualizerManager();
            short numBands = eqManager.getNumberOfBands();
            Preference appEqualizer = findPreference("app_equalizer");
            if (appEqualizer != null) {
                appEqualizer.setVisible(numBands > 0);
            }
        }
    }

    private void actionReplayGainPreamp() {
        SeekBarPreference preampPref = findPreference("replay_gain_preamp");
        if (preampPref == null) return;

        // Seed the widget with the currently persisted value so it shows the
        // correct position when the settings screen opens.
        preampPref.setValue((int) Preferences.getLoudnessPreamp());

        preampPref.setOnPreferenceChangeListener((preference, newValue) -> {
            if (!(newValue instanceof Integer)) return true;

            int dB = (Integer) newValue;
            Preferences.setLoudnessPreamp((float) dB);

            // Immediately re-apply gain to whatever is playing so the user can
            // hear the effect without restarting playback.
            if (isServiceBound && mediaServiceBinder != null) {
                com.eddyizm.tempus.util.ReplayGainUtil.reapplyCurrentTrackGain(
                        mediaServiceBinder.getPlayer());
            }

            return true;
        });
    }

    private void actionEqualizerSelector() {
        final ListPreference selectedEqualizer = findPreference("selected_equalizer");
        if (selectedEqualizer != null) {
            selectedEqualizer.setSummary(selectedEqualizer.getEntry());
            selectedEqualizer.setOnPreferenceChangeListener(
                    (preference, newValue) -> {
                        String newValStr = (String) newValue;
                        int idx = selectedEqualizer.findIndexOfValue(newValStr);
                        CharSequence newEntry = "";
                        if (idx >= 0 && selectedEqualizer.getEntries() != null) {
                            newEntry = selectedEqualizer.getEntries()[idx];
                        }
                        selectedEqualizer.setSummary(newEntry);
                        Preferences.setSelectedEqualizer(newValStr);

                        checkSystemEqualizer();

                        if (mediaServiceBinder != null) {
                            mediaServiceBinder.reloadEqualizer();
                        } else {
                            pendingEqualizerReload = true;
                        }
                        return true;
                    });
        }
    }

    private void actionParametricEqImport() {
        Preference importPref = findPreference("parametric_eq_import");
        if (importPref == null) return;

        String text = Preferences.getParametricEqProfile();
        ParametricProfile profile = text != null ? ParametricProfile.parse(text) : null;
        if (profile != null) {
            double preampDb = profile.getPreampDb();
            importPref.setSummary(getString(preampDb > 0
                            ? R.string.settings_parametric_eq_summary_louder
                            : R.string.settings_parametric_eq_summary,
                    Preferences.getParametricEqName(),
                    String.format(Locale.getDefault(), "%.1f", Math.abs(preampDb))));
        }

        importPref.setOnPreferenceClickListener(preference -> {
            parametricEqPickerLauncher.launch(new String[]{"text/*", "application/octet-stream"});
            return true;
        });
    }

    private void importParametricEq(Uri uri) {
        String text = readParametricEqFile(uri);
        ParametricProfile profile = text != null ? ParametricProfile.parse(text) : null;
        if (profile == null) {
            int message = text != null && ParametricProfile.isPerEarExport(text)
                    ? R.string.settings_parametric_eq_per_ear
                    : R.string.settings_parametric_eq_invalid;
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
            return;
        }

        String name = displayName(uri);
        Preferences.setParametricEqProfile(profile.toText(), name);
        if (mediaServiceBinder != null) {
            mediaServiceBinder.reloadEqualizer();
        } else {
            pendingEqualizerReload = true;
        }
        actionParametricEqImport();
        Toast.makeText(requireContext(), getString(R.string.settings_parametric_eq_imported, name), Toast.LENGTH_SHORT).show();
    }

    private String readParametricEqFile(Uri uri) {
        return readTextFile(uri, PARAMETRIC_EQ_MAX_BYTES);
    }

    private String readTextFile(Uri uri, int maxBytes) {
        try (InputStream in = requireContext().getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1 && bytes.size() <= maxBytes) {
                bytes.write(buffer, 0, read);
            }
            return bytes.size() <= maxBytes
                    ? new String(bytes.toByteArray(), StandardCharsets.UTF_8)
                    : null;
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    /** Drift Lite: hides every item outside LITE_KEEP, and sections left with nothing in it. Safe to call repeatedly. */
    private boolean liteFilter(androidx.preference.PreferenceGroup group, java.util.Set<String> keep) {
        boolean any = false;
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference p = group.getPreference(i);
            if (p instanceof androidx.preference.PreferenceGroup) {
                boolean has = liteFilter((androidx.preference.PreferenceGroup) p, keep);
                if (!has) p.setVisible(false);
                any |= has;
            } else if (p.getKey() == null || !keep.contains(p.getKey())) {
                p.setVisible(false);
            } else {
                any = true;
            }
        }
        return any;
    }

    private boolean liteHasVisible(androidx.preference.PreferenceGroup g) {
        for (int i = 0; i < g.getPreferenceCount(); i++) if (g.getPreference(i).isVisible()) return true;
        return false;
    }

    /** Drift: chart country, automatic or chosen by hand; a change drops the cached catalog. */
    private void initChartCountry() {
        androidx.preference.ListPreference p = findPreference(com.eddyizm.tempus.helper.DriftCountry.KEY);
        if (p == null) return;
        String[] codes = com.eddyizm.tempus.helper.DriftCountry.CHOICES;
        String[] labels = new String[codes.length];
        for (int i = 0; i < codes.length; i++) labels[i] = com.eddyizm.tempus.helper.DriftCountry.label(codes[i], getString(R.string.drift_chart_country_auto));
        p.setEntries(labels);
        p.setEntryValues(codes);
        p.setOnPreferenceChangeListener((pref, value) -> {
            new java.io.File(requireContext().getFilesDir(), "drift_catalog_v2.json").delete();
            return true;
        });
    }

    private void actionSettingsBackup() {
        findPreference("settings_export").setOnPreferenceClickListener(preference -> {
            String date = new java.text.SimpleDateFormat("yyyyMMdd", Locale.US).format(new java.util.Date());
            settingsExportLauncher.launch((com.eddyizm.tempus.BuildConfig.LITE ? "drift-lite-backup-" : "drift-settings-") + date + ".json");
            return true;
        });
        findPreference("settings_import").setOnPreferenceClickListener(preference -> {
            settingsImportLauncher.launch(new String[]{"application/json", "text/*", "application/octet-stream"});
            return true;
        });
    }

    /** Drift Lite: writes the backup to a file and hands it to Google Drive's "Save to Drive" screen. */
    private void initDriveBackup() {
        Preference drive = findPreference("settings_drive_backup");
        if (drive == null || !com.eddyizm.tempus.BuildConfig.LITE) return;
        drive.setVisible(true);
        drive.setOnPreferenceClickListener(p -> {
            try {
                String json = SettingsBackup.exportSettings(PreferenceManager.getDefaultSharedPreferences(requireContext()),
                        SettingsBackup.keys(requireContext()));
                java.io.File dir = new java.io.File(requireContext().getCacheDir(), "backup");
                dir.mkdirs();
                String date = new java.text.SimpleDateFormat("yyyyMMdd", Locale.US).format(new java.util.Date());
                java.io.File file = new java.io.File(dir, "drift-lite-backup-" + date + ".json");
                try (OutputStream out = new java.io.FileOutputStream(file)) { out.write(json.getBytes(StandardCharsets.UTF_8)); }
                Uri uri = androidx.core.content.FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".updates", file);
                Intent send = new Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent drive_ = new Intent(send).setPackage("com.google.android.apps.docs");
                // Google Drive installed: straight to "Save to Drive"; otherwise the share sheet
                if (drive_.resolveActivity(requireContext().getPackageManager()) != null) startActivity(drive_);
                else startActivity(Intent.createChooser(send, getString(R.string.lite_drive_backup_title)));
            } catch (Exception e) {
                Toast.makeText(requireContext(), R.string.settings_export_failed, Toast.LENGTH_LONG).show();
            }
            return true;
        });
    }

    private void exportSettings(Uri uri) {
        String json = SettingsBackup.exportSettings(
                PreferenceManager.getDefaultSharedPreferences(requireContext()),
                SettingsBackup.keys(requireContext()));
        try (OutputStream out = requireContext().getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IOException("No output stream");
            out.write(json.getBytes(StandardCharsets.UTF_8));
            Toast.makeText(requireContext(), R.string.settings_exported, Toast.LENGTH_SHORT).show();
        } catch (IOException | SecurityException e) {
            Toast.makeText(requireContext(), R.string.settings_export_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void importSettings(Uri uri) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        String oldLanguage = prefs.getString("language", "default");
        String oldEqualizer = prefs.getString("selected_equalizer", "0");

        String json = readTextFile(uri, SETTINGS_BACKUP_MAX_BYTES);
        int count = json == null ? -1 : SettingsBackup.importSettings(json, prefs, SettingsBackup.keys(requireContext()));
        if (count < 0) {
            Toast.makeText(requireContext(), R.string.settings_import_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(requireContext(), getString(R.string.settings_imported, count), Toast.LENGTH_SHORT).show();

        // Written directly, so the effects the preference listeners would have had are applied here.
        if (!Preferences.isLanRemoteEnabled()) {
            com.eddyizm.tempus.lan.LanRemoteSession.disconnect();
            com.eddyizm.tempus.lan.LanReceiverService.Companion.closePairing();
            requireContext().stopService(new Intent(requireContext(), com.eddyizm.tempus.lan.LanReceiverService.class));
        }
        DownloadService.sendSetRequirements(requireContext(), DownloaderService.class,
                Preferences.isDownloadWifiOnly()
                        ? new Requirements(Requirements.NETWORK_UNMETERED)
                        : new Requirements(0), false);
        if (!oldEqualizer.equals(prefs.getString("selected_equalizer", "0"))) {
            // The file picker pauses this screen, which drops the service link, so this normally
            // waits for the recreated screen to bind.
            if (mediaServiceBinder != null) {
                mediaServiceBinder.reloadEqualizer();
            } else {
                pendingEqualizerReload = true;
            }
        }

        ThemeHelper.applyTheme(prefs.getString(Preferences.THEME, ThemeHelper.DEFAULT_MODE));
        String language = prefs.getString("language", "default");
        if (language.equals(oldLanguage)) {
            requireActivity().recreate();
        } else {
            // Recreates the activity itself once the new locale is in place.
            AppCompatDelegate.setApplicationLocales("default".equals(language)
                    ? LocaleListCompat.getEmptyLocaleList()
                    : LocaleListCompat.forLanguageTags(language));
        }
    }

    private String displayName(Uri uri) {
        String name = DocumentFile.fromSingleUri(requireContext(), uri).getName();
        if (name == null) name = uri.getLastPathSegment();
        if (name == null) name = "AutoEq";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private void actionBuiltinEqualizer() {
        Preference appEqualizer = findPreference("builtin_equalizer");
        if (appEqualizer != null) {
            appEqualizer.setOnPreferenceClickListener(preference -> {
                NavController navController = NavHostFragment.findNavController(this);
                NavOptions navOptions = new NavOptions.Builder()
                        .setLaunchSingleTop(true)
                        .setPopUpTo(R.id.equalizerFragment, true)
                        .build();
                activity.setBottomNavigationBarVisibility(true);
                activity.setBottomSheetVisibility(true);
                navController.navigate(R.id.equalizerFragment, null, navOptions);
                return true;
            });
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (isServiceBound) {
            requireActivity().unbindService(serviceConnection);
            isServiceBound = false;
            mediaServiceBinder = null;
        }
    }

    private void applyAccent(int color) {
        com.eddyizm.tempus.helper.DriftAccent.set(color);
        requireActivity().recreate();
    }

    private View scrollOf(View v) {
        android.widget.ScrollView sv = new android.widget.ScrollView(requireContext());
        sv.addView(v);
        return sv;
    }

    private void showAccentDialog() {
        android.content.Context ctx = requireContext();
        float d = getResources().getDisplayMetrics().density;
        android.widget.GridLayout grid = new android.widget.GridLayout(ctx);
        grid.setColumnCount(4);
        grid.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), 0);
        androidx.appcompat.app.AlertDialog[] dialog = new androidx.appcompat.app.AlertDialog[1];
        int current = com.eddyizm.tempus.helper.DriftAccent.getColor();
        int nearest = com.eddyizm.tempus.helper.DriftAccent.nearestPreset(current);
        android.util.TypedValue tv = new android.util.TypedValue();
        ctx.getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurface, tv, true);
        int textColor = tv.data;
        for (com.eddyizm.tempus.helper.DriftAccent.Preset preset : com.eddyizm.tempus.helper.DriftAccent.PRESETS) {
            android.widget.LinearLayout cell = new android.widget.LinearLayout(ctx);
            cell.setOrientation(android.widget.LinearLayout.VERTICAL);
            cell.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            cell.setPadding((int) (4 * d), (int) (8 * d), (int) (4 * d), (int) (8 * d));
            boolean selected = preset.getColor() == nearest;
            // the dot; the selected one gets a ring with a gap around it and a check mark in the middle
            android.widget.FrameLayout dot = new android.widget.FrameLayout(ctx);
            android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable();
            fill.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            fill.setColor(preset.getColor());
            if (selected) {
                android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
                ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                ring.setStroke((int) (2.5f * d), textColor);
                int gap = (int) (5 * d);
                android.graphics.drawable.LayerDrawable both = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{ring, fill});
                both.setLayerInset(1, gap, gap, gap, gap);
                dot.setBackground(both);
                android.widget.TextView check = new android.widget.TextView(ctx);
                check.setText("✓");
                check.setTextSize(20);
                check.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                check.setTextColor(com.eddyizm.tempus.helper.DriftAccent.onAccent(preset.getColor()));
                check.setGravity(android.view.Gravity.CENTER);
                dot.addView(check, new android.widget.FrameLayout.LayoutParams(-1, -1));
            } else {
                int inset = (int) (5 * d);
                dot.setBackground(new android.graphics.drawable.InsetDrawable(fill, inset));
            }
            cell.addView(dot, new android.widget.LinearLayout.LayoutParams((int) (50 * d), (int) (50 * d)));
            android.widget.TextView label = new android.widget.TextView(ctx);
            label.setText(preset.getName());
            if (selected) label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            label.setGravity(android.view.Gravity.CENTER);
            label.setPadding(0, (int) (6 * d), 0, 0);
            label.setTextSize(12);
            label.setMaxLines(1);
            cell.addView(label);
            cell.setOnClickListener(v -> { dialog[0].dismiss(); applyAccent(preset.getColor()); });
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams(
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f),
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            grid.addView(cell, lp);
        }
        dialog[0] = new com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.drift_accent_color)
                .setView(scrollOf(grid))
                .setNeutralButton(R.string.drift_accent_custom, (di, w) ->
                        com.eddyizm.tempus.ui.dialog.ColorPickerDialog.newInstance(getString(R.string.drift_accent_color))
                                .show(getChildFragmentManager(), "drift_accent_picker"))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
