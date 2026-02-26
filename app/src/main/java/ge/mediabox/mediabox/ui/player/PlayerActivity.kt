package ge.mediabox.mediabox.ui.player

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import ge.mediabox.mediabox.R
import ge.mediabox.mediabox.data.repository.ChannelRepository
import ge.mediabox.mediabox.databinding.ActivityPlayerBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private val repository = ChannelRepository

    private var currentChannelIndex = 0
    private var channels = repository.getAllChannels()

    private var isInitialized = false  // guards against key presses during async init

    private var isControlsVisible   = false
    private var isEpgVisible        = false
    private var isTimeRewindVisible = false

    private var isLiveMode = true
    private var livePausedAt: Long? = null
    private var currentPlayingTimestamp: Long = 0L
    private var isPlayerPlaying = true

    // Tracks whether the user intentionally paused vs system lifecycle pause
    private var userIntentionallyPaused = false

    private val hideControlsHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControls() }

    private val pauseTickHandler = Handler(Looper.getMainLooper())
    private val pauseTickRunnable = object : Runnable {
        override fun run() {
            if (isControlsVisible) updateRewindButtonAvailability()
            pauseTickHandler.postDelayed(this, 1000)
        }
    }

    private lateinit var controlOverlayManager: ControlOverlayManager
    private lateinit var epgOverlayManager: EpgOverlayManager
    private lateinit var timeRewindManager: TimeRewindOverlayManager

    private var channelWatchStartTime = 0L
    private val WATCH_THRESHOLD_MS    = 5 * 60 * 1000L
    private val HEARTBEAT_INTERVAL_MS = 2 * 60 * 1000L

    private val authToken: String?
        get() = getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE)
            .getString("auth_token", null)

    private var isSyncingFavorites = false

    // =========================================================================
    // Lifecycle
    // =========================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            binding.loadingIndicator.visibility = View.VISIBLE

            repository.initialize()

            authToken?.let { token ->
                launch {
                    try { syncFavoritesAndRecentlyWatched(token) }
                    catch (e: Exception) { e.printStackTrace() }
                }
            }

            channels = repository.getAllChannels()
            initializePlayer()
            setupOverlays()
            setupControlButtons()

            if (channels.isNotEmpty()) {
                playChannel(0)
            } else {
                Toast.makeText(this@PlayerActivity, "No channels found", Toast.LENGTH_LONG).show()
            }

            binding.loadingIndicator.visibility = View.GONE
            isInitialized = true  // only allow key input after everything is ready
        }

        startHeartbeat()
    }

    override fun onPause() {
        super.onPause()
        // Pause when app genuinely goes to background.
        // EPG/controls are Views, not Activities, so they don't trigger onPause.
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        // Only auto-resume if the user hadn't intentionally paused before going
        // to background — prevents the audio-focus race condition where
        // onPause() -> onIsPlayingChanged(false) -> sets livePausedAt, then
        // onResume() fights with that state.
        if (!userIntentionallyPaused) {
            player?.play()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pauseTickHandler.removeCallbacksAndMessages(null)
        checkAndRecordWatchTime()
        player?.release()
        player = null
        hideControlsHandler.removeCallbacksAndMessages(null)
    }

    // =========================================================================
    // Favorites & Recently Watched Sync
    // =========================================================================

    private suspend fun syncFavoritesAndRecentlyWatched(token: String) {
        if (isSyncingFavorites) return
        isSyncingFavorites = true
        try {
            repository.fetchAndSyncFavourites(token)
            repository.fetchAndSyncRecentlyWatched(token)
            channels = repository.getAllChannels()

            runOnUiThread {
                if (isControlsVisible ||
                    binding.root.findViewById<View>(R.id.controlOverlay)?.visibility == View.VISIBLE) {
                    updateOverlayInfo()
                }
                if (isEpgVisible) epgOverlayManager.refreshData(channels)
            }
        } finally {
            isSyncingFavorites = false
        }
    }

    // =========================================================================
    // Player init
    // =========================================================================

    private fun initializePlayer() {
        // DefaultRenderersFactory with decoder fallback enabled handles the
        // AudioSink$UnexpectedDiscontinuityException that occurs at HLS segment
        // boundaries — instead of logging an error and potentially dropping audio,
        // ExoPlayer will fall back to a different decoder gracefully.
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableAudioFloatOutput(false)
            .setEnableDecoderFallback(true)

        player = ExoPlayer.Builder(this, renderersFactory).build().also { exoPlayer ->
            binding.playerView.player = exoPlayer

            exoPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> binding.loadingIndicator.visibility = View.VISIBLE
                        Player.STATE_READY     -> binding.loadingIndicator.visibility = View.GONE
                        Player.STATE_ENDED     -> binding.loadingIndicator.visibility = View.GONE
                        else -> {}
                    }
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlayerPlaying = playing

                    if (isLiveMode && !playing && livePausedAt == null) {
                        // Only record a live-pause when the user intentionally paused,
                        // not when the system temporarily pauses us via lifecycle.
                        if (userIntentionallyPaused) {
                            livePausedAt = System.currentTimeMillis()
                            pauseTickHandler.post(pauseTickRunnable)
                        }
                    } else if (playing && livePausedAt != null) {
                        livePausedAt = null
                        pauseTickHandler.removeCallbacks(pauseTickRunnable)
                    } else if (!isLiveMode && playing) {
                        livePausedAt = null
                        pauseTickHandler.removeCallbacks(pauseTickRunnable)
                    }

                    updateLiveIndicatorState()
                    updateRewindButtonAvailability()
                }

                override fun onPlayerError(error: PlaybackException) {
                    binding.loadingIndicator.visibility = View.GONE

                    val isBehindLiveWindow = run {
                        var e: Throwable? = error.cause
                        var found = false
                        while (e != null) {
                            if (e.javaClass.simpleName == "BehindLiveWindowException") {
                                found = true; break
                            }
                            e = e.cause
                        }
                        found
                    }

                    if (isBehindLiveWindow) {
                        Toast.makeText(
                            this@PlayerActivity,
                            "Archive not available at this point, returning to live",
                            Toast.LENGTH_SHORT
                        ).show()
                        returnToLive()
                    } else {
                        Toast.makeText(
                            this@PlayerActivity,
                            "Playback error, retrying...",
                            Toast.LENGTH_SHORT
                        ).show()
                        hideControlsHandler.postDelayed({
                            if (!isLiveMode) returnToLive()
                            else playChannel(currentChannelIndex)
                        }, 2000)
                    }
                }
            })

            exoPlayer.playWhenReady = true
        }
    }

    // =========================================================================
    // Overlay setup
    // =========================================================================

    private fun setupOverlays() {
        controlOverlayManager = ControlOverlayManager(
            binding = binding,
            onFavoriteToggle = { toggleFavorite() }
        )

        epgOverlayManager = EpgOverlayManager(
            activity  = this,
            binding   = binding,
            channels  = channels,
            onChannelSelected = { channelIndex ->
                currentChannelIndex = channelIndex
                playChannel(channelIndex)
                hideEpg()
            },
            onArchiveSelected = { instruction ->
                if (instruction.startsWith("ARCHIVE_ID")) {
                    val parts = instruction.split(":")
                    if (parts.size >= 4) {
                        val channelId = parts[1].toIntOrNull()
                        val timeMs    = parts[3].toLongOrNull()
                        if (channelId != null && timeMs != null) {
                            playArchiveAt(channelId, timeMs)
                            hideEpg()
                        }
                    }
                }
            }
        )

        val rewindOverlayView = layoutInflater
            .inflate(R.layout.overlay_time_rewind, binding.root, false)
            .also { it.visibility = View.GONE; binding.root.addView(it) }

        timeRewindManager = TimeRewindOverlayManager(
            activity          = this,
            overlayView       = rewindOverlayView,
            channelIdProvider = { channels.getOrNull(currentChannelIndex)?.id ?: -1 },
            onTimeSelected    = { timestampMs ->
                val channel = channels.getOrNull(currentChannelIndex)
                    ?: return@TimeRewindOverlayManager
                isTimeRewindVisible = false
                playArchiveAt(channel.id, timestampMs)
            },
            onDismiss = { isTimeRewindVisible = false }
        )
    }

    private fun setupControlButtons() {
        binding.root.findViewById<ImageButton>(R.id.btnRewind15s)?.setOnClickListener  { rewindSeconds(15) }
        binding.root.findViewById<ImageButton>(R.id.btnRewind1m)?.setOnClickListener   { rewindSeconds(60) }
        binding.root.findViewById<ImageButton>(R.id.btnRewind5m)?.setOnClickListener   { rewindSeconds(300) }
        binding.root.findViewById<ImageButton>(R.id.btnForward15s)?.setOnClickListener { forwardSeconds(15) }
        binding.root.findViewById<ImageButton>(R.id.btnForward1m)?.setOnClickListener  { forwardSeconds(60) }
        binding.root.findViewById<ImageButton>(R.id.btnForward5m)?.setOnClickListener  { forwardSeconds(300) }

        binding.root.findViewById<ImageButton>(R.id.btnTimeRewind)?.setOnClickListener { showTimeRewind() }

        binding.root.findViewById<View>(R.id.btnLive)?.let { liveButton ->
            liveButton.setOnClickListener { returnToLive() }
            liveButton.setOnFocusChangeListener { v, hasFocus ->
                v.scaleX = if (hasFocus) 1.08f else 1.0f
                v.scaleY = if (hasFocus) 1.08f else 1.0f
            }
        }

        binding.root.findViewById<ImageButton>(R.id.btnPlayPause)?.setOnClickListener { togglePlayPause() }
        binding.root.findViewById<ImageButton>(R.id.btnAudioLanguage)?.setOnClickListener { /* dummy */ }
    }

    // =========================================================================
    // Playback
    // =========================================================================

    private fun playChannel(index: Int) {
        if (index < 0 || index >= channels.size) return
        checkAndRecordWatchTime()

        currentChannelIndex     = index
        isLiveMode              = true
        livePausedAt            = null
        userIntentionallyPaused = false
        pauseTickHandler.removeCallbacks(pauseTickRunnable)
        currentPlayingTimestamp = System.currentTimeMillis()
        channelWatchStartTime   = System.currentTimeMillis()

        showTopBarTemporarily()
        binding.loadingIndicator.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val streamUrl = repository.getStreamUrl(channels[index].id)
                if (streamUrl != null) {
                    playUrl(streamUrl)

                    authToken?.let { token ->
                        launch {
                            try { repository.postWatchHistory(token, channels[index].apiId) }
                            catch (e: Exception) { e.printStackTrace() }
                        }
                    }
                } else {
                    Toast.makeText(this@PlayerActivity, "Stream unavailable", Toast.LENGTH_SHORT).show()
                    binding.loadingIndicator.visibility = View.GONE
                }
                updateOverlayInfo()
                updateLiveIndicatorState()
                updateRewindButtonAvailability()
            } catch (e: Exception) {
                e.printStackTrace()
                binding.loadingIndicator.visibility = View.GONE
                Toast.makeText(this@PlayerActivity, "Error loading channel", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * FIX: Do NOT call stop() before setting a new media item.
     *
     * Calling stop() tears down the audio decoder and releases audio focus,
     * which causes:
     *   1. Audio silence / audio not returning after stream switch
     *   2. SurfaceView getting into a dirty state -> cropped/partially
     *      rendered video that only recovers after a surface re-attach
     *
     * ExoPlayer handles the transition cleanly when you call setMediaItem()
     * directly on a playing or ready player — no explicit stop() needed.
     */
    private fun playUrl(url: String) {
        player?.apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            play()
        }
    }

    private fun rewindSeconds(seconds: Int) {
        val channel = channels.getOrNull(currentChannelIndex) ?: return

        val baseMs = when {
            livePausedAt != null -> livePausedAt!!
            !isLiveMode          -> currentPlayingTimestamp
            else                 -> System.currentTimeMillis()
        }
        val targetTimestamp = baseMs - (seconds * 1000L)

        val archiveStartMs = repository.getArchiveStartMs(channel.id)
        if (archiveStartMs != null && targetTimestamp < archiveStartMs) {
            val hoursBack = repository.getHoursBack(channel.id)
            Toast.makeText(
                this,
                "This channel only supports ${hoursBack}h rewind",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        binding.loadingIndicator.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val streamUrl = repository.getArchiveUrl(channel.id, targetTimestamp)
                if (streamUrl != null) {
                    isLiveMode              = false
                    livePausedAt            = null
                    userIntentionallyPaused = false
                    pauseTickHandler.removeCallbacks(pauseTickRunnable)
                    currentPlayingTimestamp = targetTimestamp
                    playUrl(streamUrl)
                    updateOverlayInfo()
                    updateLiveIndicatorState()
                    updateRewindButtonAvailability()
                } else {
                    Toast.makeText(
                        this@PlayerActivity,
                        "Archive unavailable for this time",
                        Toast.LENGTH_SHORT
                    ).show()
                    binding.loadingIndicator.visibility = View.GONE
                }
            } catch (e: Exception) {
                e.printStackTrace()
                binding.loadingIndicator.visibility = View.GONE
                Toast.makeText(this@PlayerActivity, "Error loading archive", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun forwardSeconds(seconds: Int) {
        if (isLiveMode && livePausedAt == null) {
            Toast.makeText(this, "Already at live", Toast.LENGTH_SHORT).show()
            return
        }
        val channel = channels.getOrNull(currentChannelIndex) ?: return
        val baseMs  = livePausedAt ?: currentPlayingTimestamp
        val targetTimestamp = baseMs + (seconds * 1000L)

        if (targetTimestamp >= System.currentTimeMillis()) { returnToLive(); return }

        binding.loadingIndicator.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val streamUrl = repository.getArchiveUrl(channel.id, targetTimestamp)
                if (streamUrl != null) {
                    isLiveMode              = false
                    livePausedAt            = null
                    userIntentionallyPaused = false
                    pauseTickHandler.removeCallbacks(pauseTickRunnable)
                    currentPlayingTimestamp = targetTimestamp
                    playUrl(streamUrl)
                    updateOverlayInfo()
                    updateLiveIndicatorState()
                    updateRewindButtonAvailability()
                } else {
                    Toast.makeText(this@PlayerActivity, "Archive unavailable", Toast.LENGTH_SHORT).show()
                    binding.loadingIndicator.visibility = View.GONE
                }
            } catch (e: Exception) {
                e.printStackTrace()
                binding.loadingIndicator.visibility = View.GONE
            }
        }
    }

    private fun playArchiveAt(channelId: Int, timestampMs: Long) {
        isLiveMode              = false
        livePausedAt            = null
        userIntentionallyPaused = false
        pauseTickHandler.removeCallbacks(pauseTickRunnable)
        currentPlayingTimestamp = timestampMs
        binding.loadingIndicator.visibility = View.VISIBLE

        val index = channels.indexOfFirst { it.id == channelId }
        if (index != -1) currentChannelIndex = index

        lifecycleScope.launch {
            try {
                val streamUrl = repository.getArchiveUrl(channelId, timestampMs)
                if (streamUrl != null) {
                    playUrl(streamUrl)
                    updateOverlayInfo()
                    updateLiveIndicatorState()
                    updateRewindButtonAvailability()
                } else {
                    Toast.makeText(this@PlayerActivity, "Archive unavailable", Toast.LENGTH_SHORT).show()
                    binding.loadingIndicator.visibility = View.GONE
                }
            } catch (e: Exception) {
                e.printStackTrace()
                binding.loadingIndicator.visibility = View.GONE
            }
        }
    }

    private fun returnToLive() {
        livePausedAt            = null
        userIntentionallyPaused = false
        pauseTickHandler.removeCallbacks(pauseTickRunnable)
        if (isLiveMode) {
            // Was paused on live — just resume, ExoPlayer seeks to live edge
            player?.play()
            updateLiveIndicatorState()
            updateRewindButtonAvailability()
            return
        }
        playChannel(currentChannelIndex)
    }

    private fun togglePlayPause() {
        player?.let { exo ->
            if (exo.isPlaying) {
                userIntentionallyPaused = true
                exo.pause()
                binding.root.findViewById<ImageButton>(R.id.btnPlayPause)
                    ?.setImageResource(R.drawable.ic_play)
            } else {
                userIntentionallyPaused = false
                exo.play()
                binding.root.findViewById<ImageButton>(R.id.btnPlayPause)
                    ?.setImageResource(R.drawable.ic_pause)
            }
        }
    }

    // =========================================================================
    // UI state helpers
    // =========================================================================

    private fun updateLiveIndicatorState() {
        val trulyLiveAndPlaying = isLiveMode && isPlayerPlaying && livePausedAt == null
        controlOverlayManager.updateLiveIndicator(
            isLive    = trulyLiveAndPlaying,
            isPlaying = isPlayerPlaying
        )
        binding.root.findViewById<ImageButton>(R.id.btnPlayPause)
            ?.setImageResource(if (isPlayerPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun updateRewindButtonAvailability() {
        val btn15s = binding.root.findViewById<ImageButton>(R.id.btnRewind15s) ?: return
        val btn1m  = binding.root.findViewById<ImageButton>(R.id.btnRewind1m)  ?: return
        val btn5m  = binding.root.findViewById<ImageButton>(R.id.btnRewind5m)  ?: return
        val fwd15s = binding.root.findViewById<View>(R.id.layoutForward15s)    ?: return
        val fwd1m  = binding.root.findViewById<View>(R.id.layoutForward1m)     ?: return
        val fwd5m  = binding.root.findViewById<View>(R.id.layoutForward5m)     ?: return

        // Rewind always enabled
        setButtonEnabled(btn15s, true)
        setButtonEnabled(btn1m,  true)
        setButtonEnabled(btn5m,  true)

        // Forward only enabled when not on live edge
        val pausedSeconds = livePausedAt?.let { (System.currentTimeMillis() - it) / 1000L } ?: 0L
        val can15s = !isLiveMode || pausedSeconds >= 15
        val can1m  = !isLiveMode || pausedSeconds >= 60
        val can5m  = !isLiveMode || pausedSeconds >= 300
        fwd15s.alpha = if (can15s) 1f else 0.3f; fwd15s.isEnabled = can15s
        fwd1m.alpha  = if (can1m)  1f else 0.3f; fwd1m.isEnabled  = can1m
        fwd5m.alpha  = if (can5m)  1f else 0.3f; fwd5m.isEnabled  = can5m
    }

    private fun setButtonEnabled(btn: ImageButton, enabled: Boolean) {
        btn.isEnabled = enabled
        btn.alpha = if (enabled) 1.0f else 0.3f
    }

    private fun updateOverlayInfo() {
        val channel = channels.getOrNull(currentChannelIndex) ?: return
        val playingTime = when {
            livePausedAt != null -> livePausedAt!!
            !isLiveMode          -> currentPlayingTimestamp
            else                 -> System.currentTimeMillis()
        }
        val currentProgram = channel.programs.find {
            playingTime >= it.startTime && playingTime < it.endTime
        }
        controlOverlayManager.updateChannelInfo(
            channel         = channel,
            currentProgram  = currentProgram,
            streamTimestamp = if (isLiveMode && livePausedAt == null) null else playingTime,
            isPlaying       = isPlayerPlaying
        )
    }

    private fun changeChannel(direction: Int) {
        if (channels.isEmpty()) return
        val newIndex = (currentChannelIndex + direction).let {
            when { it < 0 -> channels.size - 1; it >= channels.size -> 0; else -> it }
        }
        playChannel(newIndex)
    }

    private fun showTopBarTemporarily() {
        binding.root.findViewById<View>(R.id.controlOverlay)?.visibility = View.VISIBLE
        binding.root.findViewById<View>(R.id.bottomSection)?.visibility = View.GONE
        isControlsVisible = false
        updateOverlayInfo()
        updateLiveIndicatorState()
        updateRewindButtonAvailability()
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        hideControlsHandler.postDelayed(hideControlsRunnable, 5000)
    }

    private fun showControls() {
        if (channels.isEmpty()) return
        isControlsVisible = true
        binding.root.findViewById<View>(R.id.controlOverlay)?.visibility = View.VISIBLE
        binding.root.findViewById<View>(R.id.bottomSection)?.visibility = View.VISIBLE
        updateOverlayInfo()
        updateLiveIndicatorState()
        updateRewindButtonAvailability()
        binding.root.findViewById<ImageButton>(R.id.btnPlayPause)?.requestFocus()
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        hideControlsHandler.postDelayed(hideControlsRunnable, 5000)
    }

    private fun hideControls() {
        isControlsVisible = false
        binding.root.findViewById<View>(R.id.controlOverlay)?.visibility = View.GONE
        binding.root.findViewById<View>(R.id.bottomSection)?.visibility = View.GONE
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
    }

    private fun showEpg() {
        authToken?.let { token ->
            lifecycleScope.launch {
                try { syncFavoritesAndRecentlyWatched(token) }
                catch (e: Exception) { e.printStackTrace() }
            }
        }
        isEpgVisible = true
        binding.root.findViewById<View>(R.id.epgOverlay)?.visibility = View.VISIBLE
        epgOverlayManager.refreshData(channels)
        epgOverlayManager.requestFocus()
    }

    private fun hideEpg() {
        isEpgVisible = false
        binding.root.findViewById<View>(R.id.epgOverlay)?.visibility = View.GONE
    }

    private fun showTimeRewind() {
        hideControls()
        isTimeRewindVisible = true
        timeRewindManager.show()
    }

    private fun hideTimeRewind() {
        isTimeRewindVisible = false
        timeRewindManager.dismiss()
    }

    // =========================================================================
    // Favorites
    // =========================================================================

    private fun toggleFavorite() {
        val token = authToken
        if (token == null) {
            Toast.makeText(this, "Please log in to use favorites", Toast.LENGTH_SHORT).show()
            return
        }
        if (channels.isEmpty()) return

        val channel        = channels[currentChannelIndex]
        val willBeFavorite = !channel.isFavorite

        repository.setFavorite(channel.id, willBeFavorite)
        channels = repository.getAllChannels()
        controlOverlayManager.updateFavoriteButton(willBeFavorite)

        lifecycleScope.launch {
            try {
                val success = if (willBeFavorite)
                    repository.addFavouriteRemote(token, channel.apiId)
                else
                    repository.removeFavouriteRemote(token, channel.apiId)

                if (!success) {
                    repository.setFavorite(channel.id, !willBeFavorite)
                    channels = repository.getAllChannels()
                    runOnUiThread {
                        controlOverlayManager.updateFavoriteButton(!willBeFavorite)
                        Toast.makeText(
                            this@PlayerActivity,
                            "Failed to update favorites",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                repository.setFavorite(channel.id, !willBeFavorite)
                channels = repository.getAllChannels()
                runOnUiThread {
                    controlOverlayManager.updateFavoriteButton(!willBeFavorite)
                    Toast.makeText(this@PlayerActivity, "Network error", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // =========================================================================
    // Recently watched
    // =========================================================================

    private fun checkAndRecordWatchTime() {
        if (channelWatchStartTime == 0L) return
        val elapsed = System.currentTimeMillis() - channelWatchStartTime
        if (elapsed >= WATCH_THRESHOLD_MS) {
            val channel = channels.getOrNull(currentChannelIndex) ?: return
            val token   = authToken
            lifecycleScope.launch {
                repository.recordWatched(channel.apiId)
                if (token != null) {
                    try { repository.postWatchHistory(token, channel.apiId) }
                    catch (e: Exception) { e.printStackTrace() }
                }
            }
        }
        channelWatchStartTime = 0L
    }

    // =========================================================================
    // Heartbeat
    // =========================================================================

    private fun startHeartbeat() {
        lifecycleScope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                val token = authToken ?: continue
                try { repository.sendHeartbeat(token) }
                catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    // =========================================================================
    // Key routing
    // =========================================================================

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Swallow all key events until async init is fully complete.
        // Without this guard, pressing any key during the loading coroutine
        // causes a crash on the lateinit overlay managers.
        if (!isInitialized) return true

        return when {
            isTimeRewindVisible -> timeRewindManager.handleKeyEvent(keyCode)
            isEpgVisible        -> handleEpgKeyPress(keyCode)
            isControlsVisible   -> handleControlsKeyPress(keyCode)
            else                -> handlePlayerKeyPress(keyCode)
        } || super.onKeyDown(keyCode, event)
    }

    private fun handlePlayerKeyPress(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP    -> { changeChannel(1);  true }
        KeyEvent.KEYCODE_DPAD_DOWN  -> { changeChannel(-1); true }
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { showEpg(); true }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER    -> { showControls(); true }
        KeyEvent.KEYCODE_BACK -> {
            val overlay = binding.root.findViewById<View>(R.id.controlOverlay)
            if (overlay?.visibility == View.VISIBLE) { hideControls(); true }
            else { finish(); true }
        }
        else -> false
    }

    private fun handleControlsKeyPress(keyCode: Int): Boolean {
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        hideControlsHandler.postDelayed(hideControlsRunnable, 5000)
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { hideControls(); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                currentFocus?.performClick()
                    ?: binding.root.findViewById<ImageButton>(R.id.btnPlayPause)?.performClick()
                true
            }
            else -> false
        }
    }

    private fun handleEpgKeyPress(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_BACK -> { hideEpg(); true }
        else -> epgOverlayManager.handleKeyEvent(keyCode)
    }
}