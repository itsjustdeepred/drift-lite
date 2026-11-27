package com.eddyizm.tempus.ui.fragment

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.media3.common.util.UnstableApi
import com.eddyizm.tempus.R
import com.eddyizm.tempus.equalizer.EqualizerManager
import com.eddyizm.tempus.equalizer.OutputEqController
import com.eddyizm.tempus.equalizer.OutputKind
import com.eddyizm.tempus.service.BaseMediaService
import com.eddyizm.tempus.service.MediaService
import com.eddyizm.tempus.ui.activity.MainActivity
import com.eddyizm.tempus.util.Preferences
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

@UnstableApi
class EqualizerFragment : Fragment() {

    private lateinit var activity: MainActivity
    private lateinit var root: View
    private lateinit var settingsToolbar: MaterialToolbar
    private var equalizerManager: EqualizerManager? = null
    // Drift: equalizer profile for the current audio output.
    private var outputEq: OutputEqController? = null
    private lateinit var eqBandsContainer: LinearLayout
    private lateinit var eqSwitch: Switch
    private lateinit var resetButton: Button
    private lateinit var safeSpace: Space
    private val bandSeekBars = mutableListOf<SeekBar>()
    private var receiverRegistered = false

    @OptIn(UnstableApi::class)
    override fun onAttach(context: Context) {
        super.onAttach(context)
        activity = requireActivity() as MainActivity
    }



    private val equalizerUpdatedReceiver = object : BroadcastReceiver() {
        @OptIn(UnstableApi::class)
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BaseMediaService.ACTION_EQUALIZER_UPDATED) {
                initUI()
                restoreEqualizerPreferences()
                updateProfileUi()
            }
        }
    }

    private val connection = object : ServiceConnection {
        @OptIn(UnstableApi::class)
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as BaseMediaService.LocalBinder
            equalizerManager = binder.getEqualizerManager()
            outputEq = binder.getOutputEq()
            initUI()
            restoreEqualizerPreferences()
            updateProfileUi()
            // Drift: the route can change without events (system output picker): check again.
            outputEq?.scheduleEvaluate()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            equalizerManager = null
            outputEq = null
        }
    }

    @OptIn(UnstableApi::class)
    override fun onStart() {
        super.onStart()
        Intent(requireContext(), MediaService::class.java).also { intent ->
            intent.action = BaseMediaService.ACTION_BIND_EQUALIZER
            requireActivity().bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                requireContext(),
                equalizerUpdatedReceiver,
                IntentFilter(BaseMediaService.ACTION_EQUALIZER_UPDATED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
    }

    @OptIn(UnstableApi::class)
    override fun onStop() {
        super.onStop()
        requireActivity().unbindService(connection)
        equalizerManager = null
        outputEq = null
        if (receiverRegistered) {
            try {
                requireContext().unregisterReceiver(equalizerUpdatedReceiver)
            } catch (_: Exception) {
                // ignore if not registered
            }
            receiverRegistered = false
        }

        activity.setBottomSheetVisibility(true);
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        root = inflater.inflate(R.layout.fragment_equalizer, container, false)
        eqSwitch = root.findViewById(R.id.equalizer_switch)
        eqSwitch.isChecked = Preferences.isEqualizerEnabled()
        eqSwitch.jumpDrawablesToCurrentState()

        initAppBar()

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        eqBandsContainer = view.findViewById(R.id.eq_bands_container)
        resetButton = view.findViewById(R.id.equalizer_reset_button)
        safeSpace = view.findViewById(R.id.equalizer_bottom_space)
    }

    private fun initUI() {
        val manager = equalizerManager
        val notSupportedView = view?.findViewById<LinearLayout>(R.id.equalizer_managed_externally_container)
        val switchRow = view?.findViewById<View>(R.id.equalizer_switch_row)

        if (manager == null || manager.getNumberOfBands().toInt() == 0) {
            switchRow?.visibility = View.GONE
            resetButton.visibility = View.GONE
            eqBandsContainer.visibility = View.GONE
            safeSpace.visibility = View.GONE
            notSupportedView?.visibility = View.VISIBLE
            return
        }

        notSupportedView?.visibility = View.GONE
        switchRow?.visibility = View.VISIBLE
        resetButton.visibility = View.VISIBLE
        eqBandsContainer.visibility = View.VISIBLE
        safeSpace.visibility = View.VISIBLE

        eqSwitch.setOnCheckedChangeListener(null)
        updateUiEnabledState(eqSwitch.isChecked)
        eqSwitch.setOnCheckedChangeListener { _, isChecked ->
            manager.setEnabled(isChecked)
            Preferences.setEqualizerEnabled(isChecked)
            updateUiEnabledState(isChecked)
        }

        createBandSliders()

        resetButton.setOnClickListener {
            resetEqualizer()
            saveBandLevelsToPreferences()
        }
    }

    private fun updateUiEnabledState(isEnabled: Boolean) {
        resetButton.isEnabled = isEnabled
        bandSeekBars.forEach { it.isEnabled = isEnabled }
    }

    private fun formatDb(value: Int): String = if (value > 0) "+$value dB" else "$value dB"

    private fun createBandSliders() {
        val manager = equalizerManager ?: return
        eqBandsContainer.removeAllViews()
        bandSeekBars.clear()
        val bands = manager.getNumberOfBands()
        val bandLevelRange = manager.getBandLevelRange() ?: shortArrayOf(-1500, 1500)
        val minLevelDb = bandLevelRange[0] / 100
        val maxLevelDb = bandLevelRange[1] / 100

        val savedLevels = Preferences.getEqualizerBandLevels(bands)
        for (i in 0 until bands) {
            val band = i.toShort()
            val freq = manager.getCenterFreq(band) ?: 0

            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    val topBottomMarginDp = 16
                    topMargin = topBottomMarginDp.dpToPx(context)
                    bottomMargin = topBottomMarginDp.dpToPx(context)
                }
                setPadding(0, 8, 0, 8)
            }

            val freqLabel = TextView(requireContext(), null, 0, R.style.LabelSmall).apply {
                text = if (freq >= 1000) {
                    if (freq % 1000 == 0) {
                        "${freq / 1000} kHz"
                    } else {
                        String.format("%.1f kHz", freq / 1000f)
                    }
                } else {
                    "$freq Hz"
                }
                gravity = Gravity.START
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
            }
            row.addView(freqLabel)

            val initialLevelDb = (savedLevels.getOrNull(i) ?: (manager.getBandLevel(band) ?: 0)) / 100
            val dbLabel = TextView(requireContext(), null, 0, R.style.LabelSmall).apply {
                text = formatDb(initialLevelDb)
                setPadding(12, 0, 0, 0)
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
            }

            val seekBar = SeekBar(requireContext()).apply {
                max = maxLevelDb - minLevelDb
                progress = initialLevelDb - minLevelDb
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 6f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        val thisLevelDb = progress + minLevelDb
                        if (fromUser) {
                            manager.setBandLevel(band, (thisLevelDb * 100).toShort())
                            saveBandLevelsToPreferences()
                        }
                        dbLabel.text = formatDb(thisLevelDb)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar) {}
                })
            }
            bandSeekBars.add(seekBar)
            row.addView(seekBar)
            row.addView(dbLabel)
            eqBandsContainer.addView(row)
        }
    }

    private fun resetEqualizer() {
        val manager = equalizerManager ?: return
        val bands = manager.getNumberOfBands()
        val bandLevelRange = manager.getBandLevelRange() ?: shortArrayOf(-1500, 1500)
        val minLevelDb = bandLevelRange[0] / 100
        val midLevelDb = 0

        for (i in 0 until bands) {
            manager.setBandLevel(i.toShort(), (0).toShort())
            bandSeekBars.getOrNull(i)?.progress = midLevelDb - minLevelDb
        }
        Preferences.setEqualizerBandLevels(ShortArray(bands.toInt()))
    }

    private fun saveBandLevelsToPreferences() {
        val manager = equalizerManager ?: return
        val bands = manager.getNumberOfBands()
        val levels = ShortArray(bands.toInt()) { i -> manager.getBandLevel(i.toShort()) ?: 0 }
        Preferences.setEqualizerBandLevels(levels)
    }

    private fun restoreEqualizerPreferences() {
        val manager = equalizerManager ?: return
        eqSwitch.isChecked = Preferences.isEqualizerEnabled()
        updateUiEnabledState(eqSwitch.isChecked)

        val bands = manager.getNumberOfBands()
        val bandLevelRange = manager.getBandLevelRange() ?: shortArrayOf(-1500, 1500)
        val minLevelDb = bandLevelRange[0] / 100

        val savedLevels = Preferences.getEqualizerBandLevels(bands)
        for (i in 0 until bands) {
            val savedDb = savedLevels[i] / 100
            manager.setBandLevel(i.toShort(), (savedDb * 100).toShort())
            bandSeekBars.getOrNull(i)?.progress = savedDb - minLevelDb
        }
    }

    // Drift: "Profile: <output>" header, separate-profile switch, AutoEq and saved profiles.
    private fun updateProfileUi() {
        val root = view ?: return
        val container = root.findViewById<LinearLayout>(R.id.eq_profile_container) ?: return
        val controller = outputEq
        val out = controller?.current
        val selected = Preferences.getSelectedEqualizer()
        // Only the built-in equalizer (1) and AutoEq (3) have per-output state to save.
        if (controller == null || !controller.isEnabled() || out == null || (selected != 1 && selected != 3)) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        root.findViewById<TextView>(R.id.eq_profile_title).text = getString(R.string.eq_profile_label, out.name)

        val note = root.findViewById<TextView>(R.id.eq_profile_note)
        val separateRow = root.findViewById<View>(R.id.eq_profile_separate_row)
        val autoEqRow = root.findViewById<View>(R.id.eq_profile_autoeq_row)
        if (out.kind == OutputKind.REMOTE) {
            note.text = getString(R.string.eq_profile_remote_note, out.name)
            note.visibility = View.VISIBLE
            separateRow.visibility = View.GONE
            autoEqRow.visibility = View.GONE
        } else {
            note.visibility = View.GONE
            separateRow.visibility = View.VISIBLE
            val separate = controller.isCurrentSeparate()
            val separateSwitch = root.findViewById<Switch>(R.id.eq_profile_separate_switch)
            val summary = root.findViewById<TextView>(R.id.eq_profile_separate_summary)
            separateSwitch.setOnCheckedChangeListener(null)
            separateSwitch.isChecked = separate
            separateSwitch.jumpDrawablesToCurrentState()
            summary.setText(if (separate) R.string.eq_profile_separate_on else R.string.eq_profile_separate_off)
            separateSwitch.setOnCheckedChangeListener { _, isChecked ->
                controller.setCurrentSeparate(isChecked)
                updateProfileUi()
            }

            if (selected == 3) {
                autoEqRow.visibility = View.VISIBLE
                val name = Preferences.getParametricEqName()
                root.findViewById<TextView>(R.id.eq_profile_autoeq).text =
                    if (name != null) getString(R.string.eq_profile_autoeq, name)
                    else getString(R.string.eq_profile_autoeq_none)
                val clear = root.findViewById<Button>(R.id.eq_profile_autoeq_clear)
                clear.visibility = if (name != null) View.VISIBLE else View.GONE
                clear.setOnClickListener {
                    // Removes AutoEq only from the active profile (e.g. no correction on the speaker).
                    Preferences.clearParametricEqProfile()
                    equalizerManager?.reapply()
                    updateProfileUi()
                }
            } else {
                autoEqRow.visibility = View.GONE
            }
        }

        val list = root.findViewById<LinearLayout>(R.id.eq_profiles_list)
        val label = root.findViewById<View>(R.id.eq_profiles_saved_label)
        list.removeAllViews()
        val saved = controller.savedProfiles()
        label.visibility = if (saved.isEmpty()) View.GONE else View.VISIBLE
        for ((key, name) in saved) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(requireContext(), null, 0, R.style.BodyMedium).apply {
                text = if (key == out.key) getString(R.string.eq_profile_current_suffix, name) else name
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(ImageButton(requireContext()).apply {
                setImageResource(R.drawable.ic_delete)
                contentDescription = getString(R.string.eq_profile_delete)
                background = null
                setPadding(24, 24, 24, 24)
                setOnClickListener {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.eq_profile_delete)
                        .setMessage(name)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            controller.deleteProfile(key)
                            updateProfileUi()
                        }
                        .show()
                }
            })
            list.addView(row)
        }
    }

    private fun initAppBar() {
        settingsToolbar = root.findViewById(R.id.equalizer_toolbar)
        settingsToolbar.setNavigationOnClickListener { v ->
            activity.navController.navigateUp()
        }
    }

}

private fun Int.dpToPx(context: Context): Int =
    (this * context.resources.displayMetrics.density).toInt()
