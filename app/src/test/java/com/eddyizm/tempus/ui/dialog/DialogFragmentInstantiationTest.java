package com.eddyizm.tempus.ui.dialog;

import androidx.fragment.app.DialogFragment;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DialogFragmentInstantiationTest {

    private final List<Class<? extends DialogFragment>> dialogClasses = Arrays.asList(
            DownloadStorageDialog.class,
            StreamingCacheStorageDialog.class,
            TrackInfoDialog.class,
            StarredSyncDialog.class,
            StarredAlbumSyncDialog.class,
            StarredArtistSyncDialog.class,
            ConnectionAlertDialog.class,
            BatteryOptimizationDialog.class,
            DeleteDownloadStorageDialog.class,
            ServerUnreachableDialog.class,
            PlaybackSpeedDialog.class,
            SleepTimerDialog.class,
            RatingDialog.class
    );

    @Test
    public void allDialogFragments_havePublicNoArgumentConstructor() throws Exception {
        for (Class<? extends DialogFragment> clazz : dialogClasses) {
            Constructor<? extends DialogFragment> constructor = clazz.getConstructor();
            assertNotNull("Class " + clazz.getSimpleName() + " must have a public no-arg constructor", constructor);
            DialogFragment instance = constructor.newInstance();
            assertNotNull("Instance of " + clazz.getSimpleName() + " should not be null", instance);
            assertTrue("Instance should be an instance of DialogFragment", instance instanceof DialogFragment);
        }
    }
}
