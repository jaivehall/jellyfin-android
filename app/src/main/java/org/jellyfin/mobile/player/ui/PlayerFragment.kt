package org.jellyfin.mobile.player.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
import android.widget.ImageButton
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.annotation.RequiresApi
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.core.view.updatePadding
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.mediarouter.app.MediaRouteButton
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.widget.AssSubtitleView
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.databinding.ExoPlayerControlViewBinding
import org.jellyfin.mobile.databinding.FragmentPlayerBinding
import org.jellyfin.mobile.player.PlayerException
import org.jellyfin.mobile.player.PlayerViewModel
import org.jellyfin.mobile.player.cast.CastDiscoveryHandle
import org.jellyfin.mobile.player.cast.CastRouteInfo
import org.jellyfin.mobile.player.interaction.PlayOptions
import org.jellyfin.mobile.player.interaction.PlayerWebPreferences
import org.jellyfin.mobile.player.ui.playermenuhelper.PlayerMenuHelper
import org.jellyfin.mobile.utils.AndroidVersion
import org.jellyfin.mobile.utils.BackPressInterceptor
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.Constants.DEFAULT_CONTROLS_TIMEOUT_MS
import org.jellyfin.mobile.utils.Constants.PIP_MAX_RATIONAL
import org.jellyfin.mobile.utils.Constants.PIP_MIN_RATIONAL
import org.jellyfin.mobile.utils.SmartOrientationListener
import org.jellyfin.mobile.utils.brightness
import org.jellyfin.mobile.utils.extensions.aspectRational
import org.jellyfin.mobile.utils.extensions.getParcelableCompat
import org.jellyfin.mobile.utils.extensions.isLandscape
import org.jellyfin.mobile.utils.extensions.keepScreenOn
import org.jellyfin.mobile.utils.toast
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaStream
import org.koin.android.ext.android.inject
import java.util.Locale
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds
import org.jellyfin.mobile.player.cast.dlna.DlnaDevice
import androidx.media3.ui.R as Media3R

@Suppress("TooManyFunctions")
class PlayerFragment : Fragment(), BackPressInterceptor {
    private val appPreferences: AppPreferences by inject()
    private val assHandler: AssHandler by inject()
    private val viewModel: PlayerViewModel by viewModels()
    private var _playerBinding: FragmentPlayerBinding? = null
    private val playerBinding: FragmentPlayerBinding get() = _playerBinding!!
    private val playerView: PlayerView get() = playerBinding.playerView
    private val playerOverlay: View get() = playerBinding.playerOverlay
    private val loadingIndicator: View get() = playerBinding.loadingIndicator
    private var _playerControlsBinding: ExoPlayerControlViewBinding? = null
    private val playerControlsBinding: ExoPlayerControlViewBinding get() = _playerControlsBinding!!
    private val playerControlsView: View get() = playerControlsBinding.root
    private val toolbar: Toolbar get() = playerControlsBinding.toolbar
    private val fullscreenSwitcher: ImageButton get() = playerControlsBinding.fullscreenSwitcher
    private val castButton: ImageButton get() = playerControlsBinding.castButton
    private val castRouteButton: MediaRouteButton get() = playerControlsBinding.castRouteButton
    private var discoveryJob: Job? = null
    private var dlnaControlDialog: AlertDialog? = null
    private var dlnaProgressJob: Job? = null
    private var dlnaSeekJob: Job? = null
    private var dlnaBasePositionMs = 0L
    private var dlnaBaseElapsedMs = 0L
    private var dlnaDurationMs = 0L
    private var dlnaUserSeeking = false
    private var playerMenus: PlayerMenus? = null

    private lateinit var playerFullscreenHelper: PlayerFullscreenHelper
    lateinit var playerLockScreenHelper: PlayerLockScreenHelper
    lateinit var playerGestureHelper: PlayerGestureHelper

    private val currentVideoStream: MediaStream?
        get() = viewModel.mediaSourceOrNull?.selectedVideoStream

    /**
     * Listener that watches the current device orientation.
     * It makes sure that the orientation sensor can still be used (if enabled)
     * after toggling the orientation through the fullscreen button.
     *
     * If the requestedOrientation was reset directly after setting it in the fullscreenSwitcher click handler,
     * the orientation would get reverted before the user had any chance to rotate the device to the desired position.
     */
    private val orientationListener: OrientationEventListener by lazy { SmartOrientationListener(requireActivity()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val window = requireActivity().window
        playerFullscreenHelper = PlayerFullscreenHelper(window)

        // Observe ViewModel
        viewModel.player.observe(this) { player ->
            playerView.player = player
            // Automatically close fragment, unless we're in PiP mode
            if (player == null && !(AndroidVersion.isAtLeastN && requireActivity().isInPictureInPictureMode)) {
                parentFragmentManager.popBackStack()
            }
        }
        viewModel.playerState.observe(this) { playerState ->
            val isPlaying = viewModel.playerOrNull?.isPlaying == true
            requireActivity().window.keepScreenOn = isPlaying
            loadingIndicator.isVisible = playerState == Player.STATE_BUFFERING
        }
        viewModel.decoderType.observe(this) { type ->
            playerMenus?.updatedSelectedDecoder(type)
        }
        viewModel.error.observe(this) { message ->
            val safeMessage = message.ifEmpty { requireContext().getString(R.string.player_error_unspecific_exception) }
            requireContext().toast(safeMessage)
        }
        viewModel.queueManager.currentMediaSource.observe(this) { mediaSource ->
            if (mediaSource.selectedVideoStream?.isLandscape == false) {
                // For portrait videos, immediately enable fullscreen
                playerFullscreenHelper.enableFullscreen()
            } else if (appPreferences.exoPlayerStartLandscapeVideoInLandscape) {
                // Auto-switch to landscape for landscape videos if enabled
                requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }

            // Update title and player menus
            toolbar.title = mediaSource.getName(requireContext())
            playerMenus?.onQueueItemChanged(mediaSource, viewModel.queueManager.hasNext())
        }

        // Handle fragment arguments, extract playback options and start playback
        lifecycleScope.launch {
            val context = requireContext()
            val playOptions = requireArguments().getParcelableCompat<PlayOptions>(Constants.EXTRA_MEDIA_PLAY_OPTIONS)
            val preferences = requireArguments().getParcelableCompat<PlayerWebPreferences>(Constants.EXTRA_WEB_PREFERENCES)
            if (playOptions == null) {
                context.toast(R.string.player_error_invalid_play_options)
                return@launch
            }
            when (viewModel.queueManager.initializePlaybackQueue(playOptions, preferences)) {
                is PlayerException.InvalidPlayOptions -> context.toast(R.string.player_error_invalid_play_options)
                is PlayerException.NetworkFailure -> context.toast(R.string.player_error_network_failure)
                is PlayerException.UnsupportedContent -> context.toast(R.string.player_error_unsupported_content)
                null -> Unit // success
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _playerBinding = FragmentPlayerBinding.inflate(layoutInflater)
        _playerControlsBinding = ExoPlayerControlViewBinding.bind(playerBinding.root.findViewById(R.id.player_controls))
        return playerBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Insets handling
        ViewCompat.setOnApplyWindowInsetsListener(playerBinding.root) { _, insets ->
            playerFullscreenHelper.onWindowInsetsChanged(insets)

            val systemInsets = when {
                AndroidVersion.isAtLeastR -> insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
                else -> insets.getInsets(WindowInsetsCompat.Type.systemBars())
            }
            if (playerFullscreenHelper.isFullscreen) {
                playerView.setPadding(0)
                playerControlsView.updatePadding(
                    left = max(insets.displayCutout?.safeInsetLeft ?: 0, systemInsets.left),
                    top = max(insets.displayCutout?.safeInsetTop ?: 0, systemInsets.top),
                    right = max(insets.displayCutout?.safeInsetRight ?: 0, systemInsets.right),
                    bottom = max(insets.displayCutout?.safeInsetBottom ?: 0, systemInsets.bottom),
                )
            } else {
                playerView.updatePadding(
                    left = systemInsets.left,
                    top = systemInsets.top,
                    right = systemInsets.right,
                    bottom = systemInsets.bottom,
                )
                playerControlsView.setPadding(0) // Padding is handled by PlayerView
            }
            playerOverlay.updatePadding(
                left = systemInsets.left,
                top = systemInsets.top,
                right = systemInsets.right,
                bottom = systemInsets.bottom,
            )

            // Update fullscreen switcher icon
            val fullscreenDrawable = when {
                playerFullscreenHelper.isFullscreen -> R.drawable.ic_fullscreen_exit_white_32dp
                else -> R.drawable.ic_fullscreen_enter_white_32dp
            }
            fullscreenSwitcher.setImageResource(fullscreenDrawable)

            // Keep the fullscreen toggle pinned to the physical right edge only in
            // landscape fullscreen, without shifting any of the other controls.
            updateFullscreenButtonAlignment()

            insets
        }

        // Handle toolbar back button
        toolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }

        // Hidden route button powers the Google Cast chooser (proprietary builds only)
        viewModel.setupCastButton(castRouteButton)

        // Hide controls that only apply to local playback while casting (Cast or DLNA)
        fun updateLocalOnlyControls() {
            val casting = viewModel.isCasting.value == true || viewModel.isDlnaCasting.value == true
            listOf(
                playerControlsBinding.audioStreamsButton,
                playerControlsBinding.subtitlesButton,
                playerControlsBinding.qualityButton,
                playerControlsBinding.decoderButton,
                playerControlsBinding.infoButton,
            ).forEach { button -> button.isVisible = !casting }
        }
        viewModel.isCasting.observe(viewLifecycleOwner) { updateLocalOnlyControls() }
        viewModel.isDlnaCasting.observe(viewLifecycleOwner) { updateLocalOnlyControls() }

        // Single cast button: choose Google Cast or DLNA, or control an active session
        castButton.setOnClickListener { onCastButtonClicked() }

        // Create playback menus
        playerMenus = PlayerMenus(this, playerBinding, playerControlsBinding)

        // Set controller timeout
        suppressControllerAutoHide(false)

        // Disable controller animations
        playerView.setControllerAnimationEnabled(false)

        if (appPreferences.exoPlayerDirectPlayAss) {
            playerView.subtitleView?.apply {
                addView(AssSubtitleView(this.context, assHandler))
            }
        }

        playerLockScreenHelper = PlayerLockScreenHelper(this, playerBinding, orientationListener)
        playerGestureHelper = PlayerGestureHelper(this, playerBinding, playerLockScreenHelper)

        // Handle fullscreen switcher
        fullscreenSwitcher.setOnClickListener {
            toggleFullscreen()
        }
    }

    /**
     * Single entry point for casting. While a session is active, show its controls;
     * otherwise search both Google Cast and DLNA receivers in one go.
     */
    private fun onCastButtonClicked() {
        when {
            viewModel.isCasting.value == true -> castRouteButton.performClick()
            viewModel.dlnaManager.isCasting -> showDlnaControlDialog()
            else -> showUnifiedDevicePicker()
        }
    }

    private data class PickerEntry(val label: String, val onSelect: () -> Unit)

    /** Search both backends in parallel and present all receivers in a single list. */
    /**
     * Apply the rounded, semi-transparent hint background to a shown AlertDialog
     * so every cast-related overlay (searching, device list, warm-up, controls)
     * shares the same look.
     */
    private fun AlertDialog.applyHintBackground() {
        window?.setBackgroundDrawable(
            ContextCompat.getDrawable(context, R.drawable.bg_hint),
        )
    }

    private fun showUnifiedDevicePicker() {
        val context = requireContext()
        val progress = AlertDialog.Builder(context)
            .setTitle(R.string.player_cast_searching)
            .setCancelable(false)
            .setNegativeButton(R.string.player_dlna_cancel) { _, _ -> discoveryJob?.cancel() }
            .create()
        progress.show()
        progress.applyHintBackground()

        discoveryJob = viewLifecycleOwner.lifecycleScope.launch {
            var castHandle: CastDiscoveryHandle? = null
            try {
                val castRoutes = mutableListOf<CastRouteInfo>()
                val handle = viewModel.discoverCastRoutes { routes ->
                    castRoutes.apply {
                        clear()
                        addAll(routes)
                    }
                }
                castHandle = handle
                // Keep DLNA discovery in the main-scope coroutine like the previous
                // working version: DlnaController.discover() switches to IO internally
                // and manages a shared WiFi multicast lock, so running it inside a
                // separate Dispatchers.IO async alongside Cast discovery can race.
                val dlnaDeferred = async {
                    runCatching { viewModel.dlnaManager.discover() }.getOrDefault(emptyList())
                }
                // Give both backends the same discovery window.
                delay(CAST_DISCOVERY_DURATION_MS)
                handle.stop()
                castHandle = null
                val dlnaDevices = dlnaDeferred.await()
                progress.dismiss()
                if (!isAdded) return@launch

                val entries = mutableListOf<PickerEntry>()
                castRoutes.forEach { route ->
                    entries += PickerEntry("${route.name} (Google Cast)") {
                        viewModel.selectCastRoute(route.key)
                    }
                }
                dlnaDevices.forEach { device ->
                    entries += PickerEntry("${device.friendlyName} (DLNA)") {
                        startDlnaCasting(device)
                    }
                }
                if (entries.isEmpty()) {
                    context.toast(R.string.player_cast_no_devices)
                    return@launch
                }
                val labels = entries.map { it.label }.toTypedArray()
                val picker = AlertDialog.Builder(context)
                    .setTitle(R.string.player_cast_select_device)
                    .setItems(labels) { _, index -> entries[index].onSelect() }
                    .setNegativeButton(R.string.player_dlna_cancel, null)
                    .create()
                picker.show()
                picker.applyHintBackground()
            } finally {
                castHandle?.stop()
                if (progress.isShowing) progress.dismiss()
            }
        }
    }

    private fun discoverAndShowDevices() {
        val context = requireContext()
        val progress = AlertDialog.Builder(context)
            .setTitle(R.string.player_dlna_searching)
            .setCancelable(false)
            .setNegativeButton(R.string.player_dlna_cancel, null)
            .create()
        progress.show()
        progress.applyHintBackground()

        viewLifecycleOwner.lifecycleScope.launch {
            val devices = runCatching { viewModel.dlnaManager.discover() }.getOrDefault(emptyList())
            progress.dismiss()
            if (!isAdded) return@launch
            if (devices.isEmpty()) {
                context.toast(R.string.player_dlna_no_devices)
                return@launch
            }
            showDevicePicker(devices)
        }
    }

    private fun showDevicePicker(devices: List<DlnaDevice>) {
        val context = requireContext()
        val names = devices.map { it.friendlyName }.toTypedArray()
        val picker = AlertDialog.Builder(context)
            .setTitle(R.string.player_dlna_select_device)
            .setItems(names) { _, index -> startDlnaCasting(devices[index]) }
            .setNegativeButton(R.string.player_dlna_cancel, null)
            .create()
        picker.show()
        picker.applyHintBackground()
    }

    /**
     * Non-cancelable progress dialog with a spinner and message for the DLNA
     * stream warm-up phase. A plain title-only AlertDialog puts the text in the
     * top-aligned title panel, which looks off-center; the custom view keeps the
     * spinner + text vertically and horizontally centered.
     */
    private fun createCastingProgressDialog(context: Context, text: CharSequence): CastingProgress {
        val density = context.resources.displayMetrics.density
        val pad = (28 * density).toInt()
        val gap = (16 * density).toInt()
        val labelView = TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, gap, 0, 0)
        }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
            background = ContextCompat.getDrawable(context, R.drawable.bg_hint)
            addView(
                ProgressBar(context).apply {
                    indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
                },
            )
            addView(labelView)
        }
        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .setCancelable(false)
            .create()
        // Transparent window background so only the rounded view is visible
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        return CastingProgress(dialog, labelView)
    }

    /** Progress dialog whose message can be swapped while the cast ladder retries. */
    private class CastingProgress(
        private val dialog: AlertDialog,
        private val label: TextView,
    ) {
        val isShowing: Boolean get() = dialog.isShowing
        fun show() = dialog.show()
        fun setText(text: CharSequence) {
            if (dialog.isShowing) label.text = text
        }
        fun dismiss() = dialog.dismiss()
    }

    private fun startDlnaCasting(device: DlnaDevice) {
        val context = requireContext()
        val source = viewModel.mediaSourceOrNull
        if (source == null) {
            context.toast(R.string.player_error_unspecific_exception)
            return
        }
        val title = source.getName(context)
        val position = (viewModel.playerOrNull?.currentPosition ?: 0L).milliseconds

        // The stream warm-up / profile fallback chain in startCasting can take
        // several seconds (retries with backoff), so keep the user informed with
        // a non-cancelable progress dialog until the URL is handed to the TV.
        // A custom view (spinner + text) is used instead of a title so the content
        // is both vertically and horizontally centered. The text is swapped while
        // the compatibility ladder retries, so a long attempt does not look hung.
        val progress = createCastingProgressDialog(
            context,
            context.getString(R.string.player_dlna_connecting, device.friendlyName),
        )
        progress.show()
        val mainHandler = Handler(Looper.getMainLooper())

        viewLifecycleOwner.lifecycleScope.launch {
            // Pause local playback so audio/video only comes from the TV
            viewModel.playerOrNull?.pause()
            val ok = runCatching {
                viewModel.dlnaManager.startCasting(device, source, position, title) { attempt, total ->
                    if (attempt > 1) {
                        mainHandler.post {
                            progress.setText(context.getString(R.string.player_dlna_retry, attempt, total))
                        }
                    }
                }
            }.getOrDefault(false)
            progress.dismiss()
            if (!isAdded) return@launch
            if (ok) {
                // Seed the phone-side progress model from the position we handed to
                // the TV and the media duration. TV RelTime telemetry is unreliable
                // on some DMRs, so local time is the primary source of progress.
                dlnaBasePositionMs = position.inWholeMilliseconds
                dlnaBaseElapsedMs = SystemClock.elapsedRealtime()
                dlnaDurationMs = (viewModel.playerOrNull?.duration?.takeIf { it > 0 }
                    ?: viewModel.mediaSourceOrNull?.runTime?.inWholeMilliseconds ?: 0L)
                viewModel.setDlnaCasting(true)
                // Keep a persistent control overlay (pause / stop casting) instead
                // of a one-shot toast while the TV is playing.
                showDlnaControlDialog()
            } else {
                context.toast(R.string.player_dlna_start_failed)
            }
        }
    }

    /** Estimated TV playback position: baseline plus wall time while playing. */
    private fun dlnaPositionMs(): Long {
        val manager = viewModel.dlnaManager
        val elapsed = if (manager.isPlaying) SystemClock.elapsedRealtime() - dlnaBaseElapsedMs else 0L
        val cap = dlnaDurationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        return (dlnaBasePositionMs + elapsed).coerceIn(0L, cap)
    }

    private fun formatDlnaTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    /**
     * Persistent on-phone control for an active DLNA session: seek bar, elapsed /
     * total time, pause-resume and stop. Dragging the bar issues an AVTransport
     * Seek to the TV; progress advances from local time and is corrected whenever
     * the renderer reports a usable position.
     */
    private fun showDlnaControlDialog() {
        // Never stack a second control dialog on top of a showing one
        if (dlnaControlDialog?.isShowing == true) return
        val context = requireContext()
        val manager = viewModel.dlnaManager
        val density = context.resources.displayMetrics.density
        val pad = (20 * density).toInt()

        val timeText = TextView(context).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        val seekBar = SeekBar(context).apply {
            max = dlnaDurationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
        }
        val pauseButton = Button(context).apply {
            text = context.getString(if (manager.isPlaying) R.string.player_dlna_pause else R.string.player_dlna_resume)
        }
        val stopButton = Button(context).apply {
            text = context.getString(R.string.player_dlna_stop)
        }
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pauseButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(stopButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(timeText)
            addView(
                seekBar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = pad; bottomMargin = pad },
            )
            addView(buttonRow)
        }

        fun refreshUi() {
            if (dlnaUserSeeking) return
            val pos = dlnaPositionMs()
            seekBar.progress = pos.coerceAtMost(seekBar.max.toLong()).toInt()
            timeText.text = "${formatDlnaTime(pos)} / ${formatDlnaTime(dlnaDurationMs)}"
            pauseButton.text = context.getString(
                if (manager.isPlaying) R.string.player_dlna_pause else R.string.player_dlna_resume
            )
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    timeText.text = "${formatDlnaTime(progress.toLong())} / ${formatDlnaTime(dlnaDurationMs)}"
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                dlnaUserSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val targetMs = (seekBar?.progress ?: 0).toLong().coerceAtMost(dlnaDurationMs)
                // Keep UI frozen on the target position until the restart
                // finishes; otherwise the refresh loop snaps the bar back.
                timeText.text = context.getString(R.string.player_dlna_seeking)
                // A newer drag supersedes an in-flight restart: otherwise
                // overlapping SetAVTransportURI calls fight each other.
                dlnaSeekJob?.cancel()
                dlnaSeekJob = viewLifecycleOwner.lifecycleScope.launch {
                    val ok = runCatching { manager.seekByRestart(targetMs.milliseconds) }.getOrDefault(false)
                    if (ok) {
                        dlnaBasePositionMs = targetMs
                        dlnaBaseElapsedMs = SystemClock.elapsedRealtime()
                    } else {
                        context.toast(R.string.player_dlna_seek_failed)
                    }
                    dlnaUserSeeking = false
                    refreshUi()
                }
            }
        })

        pauseButton.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val current = dlnaPositionMs()
                val ok = runCatching { manager.togglePlayPause() }.getOrDefault(false)
                // Freeze the baseline at the moment of the toggle so progress
                // neither jumps ahead while paused nor rewinds on resume.
                dlnaBasePositionMs = current
                dlnaBaseElapsedMs = SystemClock.elapsedRealtime()
                refreshUi()
                if (ok) Unit else context.toast(R.string.player_dlna_control_failed)
            }
        }

        stopButton.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                // Read our own estimated position (TV telemetry may be absent).
                val position = dlnaPositionMs().milliseconds
                runCatching { manager.stop() }
                viewModel.setDlnaCasting(false)
                dlnaProgressJob?.cancel()
                dlnaProgressJob = null
                dlnaSeekJob?.cancel()
                dlnaSeekJob = null
                dlnaControlDialog?.dismiss()
                dlnaControlDialog = null
                viewModel.playerOrNull?.let { player ->
                    val maxMs = player.duration.takeIf { d -> d > 0 } ?: Long.MAX_VALUE
                    player.seekTo(position.inWholeMilliseconds.coerceAtMost(maxMs))
                    player.play()
                }
            }
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.player_dlna_connected, manager.deviceName))
            .setView(view)
            .setCancelable(false)
            .create()
            .also { dlnaControlDialog = it }
        dialog.show()
        dialog.applyHintBackground()

        // Refresh progress every second; correct the baseline with the TV's own
        // position whenever it reports something usable (> 0).
        dlnaProgressJob?.cancel()
        dlnaProgressJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive && dialog.isShowing) {
                runCatching { manager.getPosition() }.getOrNull()?.let { tvPos ->
                    // While dragging or a restart-seek is in flight the TV still
                    // reports the OLD session position; using it would yank the
                    // bar back to the pre-seek spot (verified 2026-11-19).
                    if (dlnaUserSeeking) return@let
                    val tvMs = tvPos.inWholeMilliseconds
                    if (tvMs > 0) {
                        dlnaBasePositionMs = tvMs
                        dlnaBaseElapsedMs = SystemClock.elapsedRealtime()
                    }
                }
                refreshUi()
                delay(1000)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        orientationListener.enable()
    }

    override fun onResume() {
        super.onResume()

        // When returning from another app, fullscreen mode for landscape orientation has to be set again
        if (isLandscape()) {
            playerFullscreenHelper.enableFullscreen()
        }

        // If playback ended during picture in picture we'll return to the main app when PiP is closed
        if (viewModel.playerOrNull == null) {
            parentFragmentManager.popBackStack()
        }
    }

    /**
     * Handle current orientation and update fullscreen state and switcher icon
     */
    private fun updateFullscreenState(configuration: Configuration) {
        // Do not handle any orientation changes while being in Picture-in-Picture mode
        if (AndroidVersion.isAtLeastN && activity?.isInPictureInPictureMode == true) {
            return
        }

        when {
            isLandscape(configuration) -> {
                // Landscape orientation is always fullscreen
                playerFullscreenHelper.enableFullscreen()
            }
            currentVideoStream?.isLandscape != false -> {
                // Disable fullscreen for landscape video in portrait orientation
                playerFullscreenHelper.disableFullscreen()
            }
        }
    }

    /**
     * Toggle fullscreen.
     *
     * If playing a portrait video, this just hides the status and navigation bars.
     * For landscape videos, additionally the screen gets rotated.
     */
    private fun toggleFullscreen() {
        val videoTrack = currentVideoStream
        if (videoTrack == null || videoTrack.isLandscape) {
            val current = resources.configuration.orientation
            requireActivity().requestedOrientation = when (current) {
                Configuration.ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            // No need to call playerFullscreenHelper in this case,
            // since the configuration change triggers updateFullscreenState,
            // which does it for us.
        } else {
            playerFullscreenHelper.toggleFullscreen()
        }
    }

    /**
     * Pin the fullscreen toggle to the physical right edge in landscape fullscreen.
     *
     * The button is constrained on both sides, so with a fixed width it sits at the
     * horizontal bias midpoint; in landscape that leaves it far from the edge. Also,
     * fullscreen applies safe-area padding to the whole control bar. Applying a negative
     * end margin cancels that padding for this button only, leaving every other control
     * in place.
     */
    private fun updateFullscreenButtonAlignment() {
        val params = fullscreenSwitcher.layoutParams as ConstraintLayout.LayoutParams
        val pinRight = playerFullscreenHelper.isFullscreen && isLandscape()
        val defaultMarginEnd = resources.getDimensionPixelSize(R.dimen.exo_bottom_controls_margin)
        // Cancel the safe-area padding so the button keeps the same small gap to the
        // physical edge, instead of staying inside the inset area with the other controls.
        val targetMarginEnd = if (pinRight) {
            defaultMarginEnd - playerControlsView.paddingRight
        } else {
            defaultMarginEnd
        }
        val targetBias = if (pinRight) 1f else 0.5f
        if (params.marginEnd != targetMarginEnd || params.horizontalBias != targetBias) {
            params.marginEnd = targetMarginEnd
            params.horizontalBias = targetBias
            fullscreenSwitcher.layoutParams = params
        }
    }

    /**
     * If true, the player controls will show indefinitely
     */
    fun suppressControllerAutoHide(suppress: Boolean) {
        playerView.controllerShowTimeoutMs = if (suppress) -1 else DEFAULT_CONTROLS_TIMEOUT_MS
    }

    fun isLandscape(configuration: Configuration = resources.configuration) =
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun onRewind() = viewModel.rewind()

    fun onFastForward() = viewModel.fastForward()

    fun onSeekByOffset(offsetMs: Long) = viewModel.seekByOffset(offsetMs)

    fun onPreviousChapter() = viewModel.previousChapter()

    fun onNextChapter() = viewModel.nextChapter()

    /**
     * @param callback called if track selection was successful and UI needs to be updated
     */
    fun onAudioTrackSelected(index: Int, callback: TrackSelectionCallback): Job = lifecycleScope.launch {
        if (viewModel.trackSelectionHelper.selectAudioTrack(index)) {
            callback.onTrackSelected(true)
        }
    }

    /**
     * @param callback called if track selection was successful and UI needs to be updated
     */
    fun onSubtitleSelected(index: Int, callback: TrackSelectionCallback): Job = lifecycleScope.launch {
        if (viewModel.trackSelectionHelper.selectSubtitleTrack(index)) {
            callback.onTrackSelected(true)
        }
    }

    /**
     * Toggle subtitles, selecting the first by [MediaStream.index] if there are multiple.
     *
     * @return true if subtitles are enabled now, false if not
     */
    fun toggleSubtitles(callback: TrackSelectionCallback) = lifecycleScope.launch {
        callback.onTrackSelected(viewModel.trackSelectionHelper.toggleSubtitles())
    }

    fun onBitrateChanged(bitrate: Int?, callback: TrackSelectionCallback) = lifecycleScope.launch {
        callback.onTrackSelected(viewModel.changeBitrate(bitrate))
    }

    /**
     * @return true if the playback speed was changed
     */
    fun onSpeedSelected(speed: Float): Boolean {
        return viewModel.setPlaybackSpeed(speed)
    }

    fun onPressSpeedUp(isPressing: Boolean): Boolean {
        return viewModel.setPressSpeedUp(isPressing, Constants.HOLD_SPEEDUP_MULTIPLIER)
    }

    fun onDecoderSelected(type: DecoderType) {
        viewModel.updateDecoderType(type)
    }

    fun onSkipToPrevious() {
        viewModel.skipToPrevious()
    }

    fun onSkipToNext() {
        viewModel.skipToNext()
    }

    fun onSkipMediaSegment(mediaSegmentDto: MediaSegmentDto?) {
        viewModel.skipMediaSegment(mediaSegmentDto)
    }

    fun onPopupDismissed() {
        if (!AndroidVersion.isAtLeastR) {
            updateFullscreenState(resources.configuration)
        }
    }

    fun onUserLeaveHint() {
        if (AndroidVersion.isAtLeastN && viewModel.playerOrNull != null) {
            requireActivity().enterPictureInPicture()
        }
    }

    @Suppress("NestedBlockDepth")
    @RequiresApi(Build.VERSION_CODES.N)
    private fun Activity.enterPictureInPicture() {
        if (AndroidVersion.isAtLeastO) {
            val params = PictureInPictureParams.Builder().apply {
                val aspectRational = currentVideoStream?.aspectRational?.let { aspectRational ->
                    when {
                        aspectRational < PIP_MIN_RATIONAL -> PIP_MIN_RATIONAL
                        aspectRational > PIP_MAX_RATIONAL -> PIP_MAX_RATIONAL
                        else -> aspectRational
                    }
                }
                setAspectRatio(aspectRational)
                val contentFrame: View = playerView.findViewById(Media3R.id.exo_content_frame)
                val contentRect = with(contentFrame) {
                    val (x, y) = intArrayOf(0, 0).also(::getLocationInWindow)
                    Rect(x, y, x + width, y + height)
                }
                setSourceRectHint(contentRect)
            }.build()
            enterPictureInPictureMode(params)
        } else {
            @Suppress("DEPRECATION")
            enterPictureInPictureMode()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        playerView.useController = !isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            playerMenus?.dismissPlaybackInfo()
            playerLockScreenHelper.hideUnlockButton()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Handler(Looper.getMainLooper()).post {
            if (!isAdded) return@post

            updateFullscreenState(newConfig)
            updateFullscreenButtonAlignment()
            playerGestureHelper.handleConfiguration(newConfig)
        }
    }

    override fun onStop() {
        super.onStop()
        orientationListener.disable()
    }

    override fun onDestroyView() {
        dlnaProgressJob?.cancel()
        dlnaProgressJob = null
        dlnaSeekJob?.cancel()
        dlnaSeekJob = null
        dlnaControlDialog?.dismiss()
        dlnaControlDialog = null
        dlnaUserSeeking = false
        super.onDestroyView()
        // Detach player from PlayerView
        playerView.player = null

        // Set binding references to null
        _playerBinding = null
        _playerControlsBinding = null
        playerMenus = null
    }

    override fun onDestroy() {
        super.onDestroy()
        with(requireActivity()) {
            // Reset screen orientation
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            playerFullscreenHelper.disableFullscreen()
            // Reset screen brightness
            window.brightness = BRIGHTNESS_OVERRIDE_NONE
        }
    }

    fun setPlayerMenuHelper(menuHelper: PlayerMenuHelper) {
        viewModel.setPlayerMenuHelper(menuHelper)
    }

    companion object {
        private const val CAST_DISCOVERY_DURATION_MS = 6_000L
    }
}
