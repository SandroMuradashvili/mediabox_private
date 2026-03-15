package ge.mediabox.mediabox.ui.player

import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import ge.mediabox.mediabox.R
import ge.mediabox.mediabox.data.repository.ChannelRepository
import ge.mediabox.mediabox.databinding.ActivityPlayerBinding
import ge.mediabox.mediabox.ui.LangPrefs
import ge.mediabox.mediabox.ui.LogoManager
import kotlinx.coroutines.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.media3.ui.AspectRatioFrameLayout
import android.widget.TextView // Fixes "Unresolved reference TextView"
import android.widget.Toast
import androidx.core.content.edit // Fixes "Too many arguments for edit()" and "putInt"
import androidx.media3.common.util.Log

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private val repository = ChannelRepository
    private var currentChannelIndex = 0
    private var channels = repository.getAllChannels()
    private var isInitialized = false
    private var isControlsVisible = false
    private var isEpgVisible = false
    private var isTimeRewindVisible = false
    private var isLiveMode = true
    private var livePausedAt: Long? = null
    private var isPlayerPlaying = true
    private var userIntentionallyPaused = false
    private var playingArchiveChannelId: Int = -1
    private var archiveBaseTimestamp: Long = 0L
    private var lastArchiveUrlTemplate: String? = null

    // Zap & Cache Logic
    private val zapHandler = Handler(Looper.getMainLooper())
    private var zapRunnable: Runnable? = null
    private var isBrowsing = false
    private var prefetchJob: Job? = null
    private var streamExpiryJob: Job? = null
    private var currentStreamUrl: String? = null

    private val hideControlsHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControls() }

    private var numberInputBuffer = ""
    private val numberInputHandler = Handler(Looper.getMainLooper())
    private val numberInputRunnable = Runnable { executeNumberSwitch() }


    private lateinit var controlOverlayManager: ControlOverlayManager
    private lateinit var epgOverlayManager: EpgOverlayManager
    private lateinit var timeRewindManager: TimeRewindOverlayManager
    private lateinit var trackSelectionManager: TrackSelectionOverlayManager
    private var pendingStreamJob: Job? = null
    private var pendingProgramJob: Job? = null

    private var currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

    private val authToken: String? get() = getSharedPreferences("AuthPrefs", MODE_PRIVATE).getString("auth_token", null)

    // Time Constants
    private val INACTIVITY_TIMEOUT_MS = 3 * 60 * 60 * 1000L // 3 Hours
    private val COUNTDOWN_DURATION_MS = 300 * 1000L        // 300 Seconds

    // Timer Management
    private val inactivityHandler = Handler(Looper.getMainLooper())
    private var countdownTimer: android.os.CountDownTimer? = null
    private var isInactivityPopupShowing = false

    // The task that triggers after 3 hours of no buttons being pressed
    private val inactivityRunnable = Runnable {
        showInactivityPopup()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        Log.d("PlayerActivityDebug", "Key event received! Action: ${event.action}, KeyCode: ${event.keyCode}")
        if (event.action == KeyEvent.ACTION_DOWN) {
            resetInactivityTimer()

            if (isInactivityPopupShowing) {
                dismissInactivityPopup()
                return true
            }

            // --- NEW: CATCH NUMBER KEYS ---
            if (event.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                handleNumberInput(event.keyCode - KeyEvent.KEYCODE_0)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleNumberInput(digit: Int) {
        // 1. Cancel any pending switch
        numberInputHandler.removeCallbacks(numberInputRunnable)

        // 2. Add digit to buffer (limit to 4 digits)
        if (numberInputBuffer.length < 4) {
            numberInputBuffer += digit
        }

        // 3. Update UI
        binding.root.findViewById<View>(R.id.numberInputCard).visibility = View.VISIBLE
        binding.root.findViewById<TextView>(R.id.tvNumberInputDisplay).text = numberInputBuffer

        // 4. Wait 1.5 seconds. If no more keys, switch channel.
        numberInputHandler.postDelayed(numberInputRunnable, 1500)
    }

    private fun executeNumberSwitch() {
        val targetNumber = numberInputBuffer.toIntOrNull() ?: return

        // Clear buffer and hide UI
        numberInputBuffer = ""
        binding.root.findViewById<View>(R.id.numberInputCard).visibility = View.GONE

        // Find the channel index where the channel.number matches what was typed
        val targetIndex = channels.indexOfFirst { it.number == targetNumber }

        if (targetIndex != -1) {
            if (channels[targetIndex].isLocked) {
                val isKa = LangPrefs.isKa(this)
                Toast.makeText(this,
                    if (isKa) "არხი დაბლოკილია" else "Channel is locked",
                    Toast.LENGTH_SHORT).show()
            } else {
                playChannel(targetIndex)
            }
        } else {
            // Channel number not found
            val isKa = LangPrefs.isKa(this)
            Toast.makeText(this,
                if (isKa) "არხი ვერ მოიძებნა" else "Channel not found",
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetInactivityTimer() {
        inactivityHandler.removeCallbacks(inactivityRunnable)
        inactivityHandler.postDelayed(inactivityRunnable, INACTIVITY_TIMEOUT_MS)
    }

    private fun showInactivityPopup() {
        isInactivityPopupShowing = true
        val isKa = LangPrefs.isKa(this)

        binding.inactivityOverlay.visibility = View.VISIBLE

        binding.tvInactivityTitle.text = if (isKa) "კვლავ უყურებთ?" else "Are you still watching?"
        binding.tvInactivityHint.text = if (isKa) "გასაგრძელებლად დააჭირეთ ნებისმიერ ღილაკს" else "Press any button to stay"

        countdownTimer?.cancel()
        countdownTimer = object : android.os.CountDownTimer(COUNTDOWN_DURATION_MS, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                binding.tvInactivityMessage.text = if (isKa)
                    "აპლიკაცია დაიხურება $seconds წამში"
                else
                    "The app will close in $seconds seconds"
            }
            override fun onFinish() {
                if (isInactivityPopupShowing) finish()
            }
        }.start()
    }

    private fun dismissInactivityPopup() {
        isInactivityPopupShowing = false
        countdownTimer?.cancel()
        binding.inactivityOverlay.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resetInactivityTimer()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawableResource(android.R.color.black)

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            val isKa = LangPrefs.isKa(this@PlayerActivity)
            repository.initialize(authToken, isKa)
            channels = repository.getAllChannels()

            initializePlayer()
            setupOverlays()
            setupControlButtons()

            // --- MODIFY THIS LOGIC ---
            val savedChannelId = getSharedPreferences("AppPrefs", MODE_PRIVATE)
                .getInt("last_viewed_channel_id", -1)

            // Try to find the index of the saved channel, ensuring it's not locked
            var startIndex = channels.indexOfFirst { it.id == savedChannelId && !it.isLocked }

            // If no saved channel or it's now locked, fall back to the first unlocked channel
            if (startIndex == -1) {
                startIndex = channels.indexOfFirst { !it.isLocked }
            }

            if (startIndex >= 0) {
                currentChannelIndex = startIndex
                zapHandler.postDelayed({
                    playChannel(startIndex)
                }, 300)
            }
            isInitialized = true
        }
    }



    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun initializePlayer() {
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val bandwidthMeter = DefaultBandwidthMeter.Builder(this)
            .setInitialBitrateEstimate(25_000_000L)
            .build()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 800, 1500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val hlsFactory = androidx.media3.exoplayer.hls.HlsMediaSource.Factory(
            androidx.media3.datasource.DefaultDataSource.Factory(this)
        ).setAllowChunklessPreparation(true)

        player = ExoPlayer.Builder(this, renderersFactory)
            .setBandwidthMeter(bandwidthMeter)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(hlsFactory)
            .build().also { exo ->
                binding.playerView.player = exo
                exo.addListener(playerListener)
                exo.playWhenReady = true
            }
    }

    private val playerListener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            binding.videoPlaceholder.visibility = View.GONE
        }
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
                binding.videoPlaceholder.visibility = View.VISIBLE
            }
        }
        override fun onPlayerError(error: PlaybackException) {
            binding.videoPlaceholder.visibility = View.VISIBLE
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) returnToLive()
        }
        override fun onTracksChanged(tracks: Tracks) { if (::trackSelectionManager.isInitialized) trackSelectionManager.onTracksChanged(tracks) }
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) { updateQualityDisplay(videoSize.height) }
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlayerPlaying = playing
            if (isLiveMode && !playing && livePausedAt == null && userIntentionallyPaused) {
                livePausedAt = System.currentTimeMillis()
            } else if (playing && livePausedAt != null) {
                livePausedAt = null
            }
            updateLiveIndicatorState()
        }
    }

    private fun changeChannel(direction: Int) {
        isBrowsing = true
        zapRunnable?.let { zapHandler.removeCallbacks(it) }

        var index = currentChannelIndex
        repeat(channels.size) {
            index = (index + direction + channels.size) % channels.size
            if (!channels[index].isLocked) {
                currentChannelIndex = index
                updateOverlayInfo()
                showTopBarTemporarily()
                val runnable = Runnable {
                    isBrowsing = false
                    playChannel(currentChannelIndex)
                }
                zapRunnable = runnable
                zapHandler.postDelayed(runnable, 400)
                return
            }
        }
    }

    private fun playChannel(index: Int) {
        if (index !in channels.indices || channels[index].isLocked) return
        currentChannelIndex = index
        val channel = channels[index]

        // --- ADD THIS: Save the channel ID to persist "Continue Watching" ---
        getSharedPreferences("AppPrefs", MODE_PRIVATE).edit()
            .putInt("last_viewed_channel_id", channel.id)
            .apply()

        isLiveMode = true
        livePausedAt = null
        archiveBaseTimestamp = 0
        playingArchiveChannelId = -1
        lastArchiveUrlTemplate = null

        if (!isBrowsing) binding.videoPlaceholder.visibility = View.VISIBLE

        updateOverlayInfo()
        fetchProgramsForCurrentChannel() // Uses cache
        prefetchNeighbors(index)

        // Fix: Passing authToken to the cached getStreamUrl method
        loadStream { repository.getStreamUrl(channel.id, authToken) }
    }

    private fun loadStream(urlProvider: suspend () -> String?) {
        pendingStreamJob?.cancel()
        pendingStreamJob = lifecycleScope.launch {
            // 1. We call the provider (which is repository.getStreamUrl)
            // This will check the 55-minute cache before hitting your server.
            val url = urlProvider()

            if (url != null) {
                currentStreamUrl = url
                player?.setMediaItem(MediaItem.fromUri(url))
                player?.prepare()
                player?.play()

                // 2. Clear the placeholder to show video
                binding.videoPlaceholder.visibility = View.GONE

                scheduleStreamRefresh()
                updateOverlayInfo()
                updateLiveIndicatorState()
            }
        }
    }

    private fun prefetchNeighbors(currentIndex: Int) {
        prefetchJob?.cancel()
        prefetchJob = lifecycleScope.launch {
            delay(2000)
            val next = (currentIndex + 1) % channels.size
            val prev = (currentIndex - 1 + channels.size) % channels.size

            // Fix: Pass 'authToken' to the repository method
            if (!channels[next].isLocked) {
                repository.getStreamUrl(channels[next].id, authToken)
            }

            if (!channels[prev].isLocked) {
                repository.getStreamUrl(channels[prev].id, authToken)
            }
        }
    }

    private fun scheduleStreamRefresh() {
        streamExpiryJob?.cancel()
        val url = currentStreamUrl ?: return
        val expiryMs = repository.extractExpiryFromUrl(url)
        val currentTime = System.currentTimeMillis()
        if (expiryMs > currentTime) {
            val refreshDelay = (expiryMs - currentTime) - 60000L
            if (refreshDelay > 0L) {
                streamExpiryJob = lifecycleScope.launch {
                    delay(refreshDelay)
                    refreshStreamSeamlessly()
                }
            } else lifecycleScope.launch { refreshStreamSeamlessly() }
        }
    }

    private suspend fun refreshStreamSeamlessly() {
        val channel = channels.getOrNull(currentChannelIndex) ?: return
        val newUrl = withContext(Dispatchers.IO) {
            if (isLiveMode) {
                repository.getStreamUrl(channel.id, authToken)
            } else {
                // FIX: Pass the current playback time and the authToken
                repository.getArchiveUrl(channel.id, getCurrentAbsoluteTime(), authToken)
            }
        }
        if (!newUrl.isNullOrBlank() && newUrl != currentStreamUrl) {
            currentStreamUrl = newUrl
            player?.setMediaItem(MediaItem.fromUri(newUrl), false)
            scheduleStreamRefresh()
        }
    }

    private fun updateOverlayInfo() {
        val channel = channels.getOrNull(currentChannelIndex) ?: return
        val currentTs = getCurrentAbsoluteTime()

        applyAspectRatio(currentResizeMode)

        // --- LOG FOR MATCHING ---
        android.util.Log.d("BACKEND_HELP", "Checking Match for Device Time: $currentTs")
        if (channel.programs.isEmpty()) {
            android.util.Log.w("BACKEND_HELP", "No programs loaded in memory for ${channel.name}")
        }

        // Find current program
        val currentProgram = channel.programs.find { currentTs in it.startTime until it.endTime }

        // Update the HUD
        controlOverlayManager.updateChannelInfo(
            channel,
            currentProgram,
            if (isLiveMode && livePausedAt == null) null else currentTs,
            isPlayerPlaying
        )

        // Refresh the Rewind Manager's knowledge of the archive limit
        if (::timeRewindManager.isInitialized && isTimeRewindVisible) {
            // This forces the picker to re-check the "Outside window" error
        }
    }

    // Inside PlayerActivity.kt

    private fun playArchiveAt(channelId: Int, timestampMs: Long) {
        // 1. Boundary check: if user seeks into the future, go live
        if (timestampMs >= System.currentTimeMillis()) {
            returnToLive()
            return
        }

        val targetIndex = channels.indexOfFirst { it.id == channelId }
        if (targetIndex != -1) currentChannelIndex = targetIndex

        // UI and State updates
        isBrowsing = false
        binding.videoPlaceholder.visibility = View.VISIBLE
        val channel = channels[currentChannelIndex]
        isLiveMode = false
        livePausedAt = null
        archiveBaseTimestamp = timestampMs
        playingArchiveChannelId = channelId

        // Refresh EPG for this context
        fetchProgramsForCurrentChannel()

        loadStream {
            // The Repository now internally handles the 5-minute expiry check
            repository.getArchiveUrl(channel.id, timestampMs, authToken)
        }

        if (isControlsVisible) rescheduleHideControls()
    }

    private fun fetchProgramsForCurrentChannel() {
        val channel = channels.getOrNull(currentChannelIndex) ?: return

        pendingProgramJob?.cancel()
        pendingProgramJob = lifecycleScope.launch {
            // Correctly calls the repository with caching
            val programs = repository.getProgramsForChannel(channel.id)

            channel.programs = programs
            withContext(Dispatchers.Main) {
                updateOverlayInfo()
            }
        }
    }

    private fun getCurrentAbsoluteTime(): Long {
        return if (isLiveMode) {
            // If we are live, the "absolute" time is right now
            System.currentTimeMillis()
        } else {
            // If we are in archive, it's the start time of the archive + current player position
            archiveBaseTimestamp + (player?.currentPosition ?: 0L)
        }
    }
    override fun onPause() {
        super.onPause()
        // 1. Stop the audio immediately
        player?.playWhenReady = false

        // 2. Hide the inactivity popup so it doesn't look "frozen" when you return
        if (isInactivityPopupShowing) {
            dismissInactivityPopup()
        }

        // 3. Clean up timers
        inactivityHandler.removeCallbacks(inactivityRunnable)
        countdownTimer?.cancel()
    }
    override fun onResume() {
        super.onResume()
        resetInactivityTimer()

        if (!isInitialized) return

        if (!userIntentionallyPaused) {
            if (isLiveMode) {
                playChannel(currentChannelIndex)
            } else {
                player?.playWhenReady = true
            }
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        numberInputHandler.removeCallbacks(numberInputRunnable)
        inactivityHandler.removeCallbacksAndMessages(null)
        countdownTimer?.cancel()
        pendingStreamJob?.cancel(); pendingProgramJob?.cancel()
        zapHandler.removeCallbacksAndMessages(null)
        hideControlsHandler.removeCallbacksAndMessages(null)
        player?.release(); player = null
    }

    private fun setupOverlays() {
        controlOverlayManager = ControlOverlayManager(binding = binding, onFavoriteToggle = { toggleFavorite() })

        // --- LOAD BRANDING LOGO ONCE HERE ---
        val topLogo = binding.controlOverlay.root.findViewById<ImageView>(R.id.ivTopLogo)
        if (topLogo != null) {
            LogoManager.loadLogo(topLogo)
        }

        epgOverlayManager = EpgOverlayManager(activity = this, binding = binding, channels = channels,
            onChannelSelected = { index -> currentChannelIndex = index; playChannel(index); hideEpg() },
            onArchiveSelected = { instruction ->
                val parts = instruction.split(":")
                if (parts.size >= 4) playArchiveAt(parts[1].toIntOrNull() ?: -1, parts[3].toLongOrNull() ?: 0L)
                hideEpg()
            }
        )
        val rewindOverlayView = layoutInflater.inflate(R.layout.overlay_time_rewind, binding.root, false).also { it.visibility = View.GONE; binding.root.addView(it) }
        timeRewindManager = TimeRewindOverlayManager(activity = this, overlayView = rewindOverlayView,
            channelIdProvider = { channels.getOrNull(currentChannelIndex)?.id ?: -1 },
            onTimeSelected = { ts -> playArchiveAt(channels[currentChannelIndex].id, ts) },
            onDismiss = { isTimeRewindVisible = false })

        val trackOverlayView = layoutInflater.inflate(R.layout.overlay_track_selection, binding.root, false).also { it.visibility = View.GONE; binding.root.addView(it) }
        trackSelectionManager = TrackSelectionOverlayManager(this, trackOverlayView, { player })
        trackSelectionManager.init()
    }

    private fun setupControlButtons() {
        binding.root.apply {
            findViewById<ImageButton>(R.id.btnRewind15s)?.setOnClickListener  { rewindSeconds(15) }
            findViewById<ImageButton>(R.id.btnRewind1m)?.setOnClickListener   { rewindSeconds(60) }
            findViewById<ImageButton>(R.id.btnRewind5m)?.setOnClickListener   { rewindSeconds(300) }
            findViewById<ImageButton>(R.id.btnForward15s)?.setOnClickListener { forwardSeconds(15) }
            findViewById<ImageButton>(R.id.btnForward1m)?.setOnClickListener  { forwardSeconds(60) }
            findViewById<ImageButton>(R.id.btnForward5m)?.setOnClickListener  { forwardSeconds(300) }
            findViewById<ImageButton>(R.id.btnTimeRewind)?.setOnClickListener { showTimeRewind() }
            findViewById<ImageButton>(R.id.btnPlayPause)?.setOnClickListener  { togglePlayPause() }
            findViewById<View>(R.id.btnLive)?.setOnClickListener { returnToLive() }
            findViewById<View>(R.id.btnQualityLayout)?.setOnClickListener { hideControls(); trackSelectionManager.show(TrackSelectionOverlayManager.Mode.VIDEO) }
            findViewById<ImageButton>(R.id.btnAudioLanguage)?.setOnClickListener { hideControls(); trackSelectionManager.show(TrackSelectionOverlayManager.Mode.AUDIO) }
            findViewById<View>(R.id.btnAspectRatio)?.setOnClickListener { toggleAspectRatio() }
        }
        loadSavedAspectRatio()
    }

    private fun toggleAspectRatio() {
        currentResizeMode = when (currentResizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        applyAspectRatio(currentResizeMode)
    }

    private fun applyAspectRatio(mode: Int) {
        binding.playerView.resizeMode = mode

        // Update UI Text
        val isKa = LangPrefs.isKa(this)
        val label = when (mode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> if (isKa) "ორიგინალი" else "Fit"
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> if (isKa) "შევსება" else "Stretch"
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> if (isKa) "ზუმი" else "Zoom"
            else -> "Fit"
        }
        binding.root.findViewById<TextView>(R.id.tvCurrentAspectRatio)?.text = label

        // Save "Auto Sizer" preference forever
        getSharedPreferences("AppPrefs", MODE_PRIVATE).edit {
            putInt("video_resize_mode", mode)
        }

        rescheduleHideControls()
    }

    private fun loadSavedAspectRatio() {
        val savedMode = getSharedPreferences("AppPrefs", MODE_PRIVATE)
            .getInt("video_resize_mode", AspectRatioFrameLayout.RESIZE_MODE_FIT)
        currentResizeMode = savedMode
        applyAspectRatio(savedMode)
    }

    private fun returnToLive() = playChannel(currentChannelIndex)
    private fun rewindSeconds(s: Int) {
        val channel = channels.getOrNull(currentChannelIndex) ?: return

        // Simply calculate the target and try to play it.
        // The server (or the subsequent boundary check) will handle limits.
        val targetTs = getCurrentAbsoluteTime() - (s * 1000L)

        playArchiveAt(channel.id, targetTs)
    }

    private fun forwardSeconds(s: Int) {
        val targetTs = getCurrentAbsoluteTime() + (s * 1000L)

        // If the jump goes into the future, go back to Live URL
        if (targetTs >= System.currentTimeMillis()) {
            returnToLive()
        } else {
            playArchiveAt(channels[currentChannelIndex].id, targetTs)
        }
    }
    private fun togglePlayPause() {
        val isPlaying = player?.isPlaying == true
        userIntentionallyPaused = isPlaying
        if (isPlaying) player?.pause() else player?.play()
    }

    private fun updateLiveIndicatorState() {
        val trulyLive = isLiveMode && isPlayerPlaying && livePausedAt == null
        controlOverlayManager.updateLiveIndicator(trulyLive, isPlayerPlaying)
        binding.root.findViewById<ImageButton>(R.id.btnPlayPause)?.setImageResource(if (isPlayerPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }



    private fun showTopBarTemporarily() {
        binding.root.findViewById<View>(R.id.controlOverlay)?.visibility = View.VISIBLE
        binding.root.findViewById<View>(R.id.topBar)?.visibility = View.VISIBLE
        binding.root.findViewById<View>(R.id.bottomSection)?.visibility = View.GONE
        updateOverlayInfo()
        rescheduleHideControls()
    }

    private fun showControls() {
        if (trackSelectionManager.isVisible) return
        isControlsVisible = true
        binding.root.findViewById<View>(R.id.controlOverlay)?.visibility = View.VISIBLE
        binding.root.findViewById<View>(R.id.topBar)?.visibility = View.VISIBLE
        binding.root.findViewById<View>(R.id.bottomSection)?.visibility = View.VISIBLE
        updateOverlayInfo()
        binding.root.findViewById<ImageButton>(R.id.btnPlayPause)?.requestFocus()
        rescheduleHideControls()
    }

    private fun hideControls() {
        isControlsVisible = false
        binding.root.findViewById<View>(R.id.controlOverlay)?.visibility = View.GONE
        binding.root.findViewById<View>(R.id.bottomSection)?.visibility = View.GONE
    }

    private fun rescheduleHideControls() {
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        hideControlsHandler.postDelayed(hideControlsRunnable, 5000)
    }

    private fun showEpg() {
        isEpgVisible = true
        binding.root.findViewById<View>(R.id.epgOverlay)?.visibility = View.VISIBLE
        epgOverlayManager.refreshData(channels)
        epgOverlayManager.requestFocus(channels.getOrNull(currentChannelIndex)?.id ?: -1)
    }

    private fun hideEpg() { isEpgVisible = false; binding.root.findViewById<View>(R.id.epgOverlay)?.visibility = View.GONE }
    // Inside PlayerActivity.kt
    private fun showTimeRewind() {
        val channel = channels.getOrNull(currentChannelIndex) ?: return
        hideControls()

        lifecycleScope.launch {
            // 1. Call dummy archive to get real length from server
            val hoursBack = repository.refreshArchiveWindow(channel.id, authToken)

            // 2. Open the manager with the retrieved length
            isTimeRewindVisible = true
            timeRewindManager.show(hoursBack)
        }
    }
    private fun toggleFavorite() {
        val token = authToken ?: return
        val channel = channels.getOrNull(currentChannelIndex)?.takeIf { !it.isLocked } ?: return
        val willBeFavorite = !channel.isFavorite
        repository.setFavoriteLocal(channel.id, willBeFavorite)
        controlOverlayManager.updateFavoriteButton(willBeFavorite)
        lifecycleScope.launch(Dispatchers.IO) {
            val success = if (willBeFavorite) repository.addFavouriteRemote(token, channel.apiId) else repository.removeFavouriteRemote(token, channel.apiId)
            if (!success) withContext(Dispatchers.Main) {
                repository.setFavoriteLocal(channel.id, !willBeFavorite)
                controlOverlayManager.updateFavoriteButton(!willBeFavorite)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 1. Reset the 3-hour timer on ANY button press
        resetInactivityTimer()

        // 2. THE SHIELD: If the popup is on screen, close it and STOP
        if (isInactivityPopupShowing) {
            dismissInactivityPopup()
            return true // This PREVENTS the "Back" button from reaching the finish() block below
        }

        // 3. Normal logic starts ONLY if the popup is NOT showing
        if (!isInitialized) return true

        val handled = when {
            trackSelectionManager.isVisible -> trackSelectionManager.handleKeyEvent(keyCode)
            isTimeRewindVisible -> timeRewindManager.handleKeyEvent(keyCode)
            isEpgVisible -> if (keyCode == KeyEvent.KEYCODE_BACK) { hideEpg(); true } else epgOverlayManager.handleKeyEvent(keyCode)
            isControlsVisible -> { rescheduleHideControls(); if (keyCode == KeyEvent.KEYCODE_BACK) { hideControls(); true } else false }
            else -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { changeChannel(1); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { changeChannel(-1); true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { showEpg(); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { showControls(); true }
                KeyEvent.KEYCODE_BACK -> { finish(); true } // This is what was catching your Back button
                else -> false
            }
        }
        return handled || super.onKeyDown(keyCode, event)
    }

    @OptIn(UnstableApi::class) private fun updateQualityDisplay(height: Int) = controlOverlayManager.updateQualityInfo(height)
}