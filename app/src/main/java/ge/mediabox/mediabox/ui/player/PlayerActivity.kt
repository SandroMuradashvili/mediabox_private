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

    // Overlay visibility flags
    private var isControlsVisible  = false
    private var isEpgVisible       = false
    private var isTimeRewindVisible = false

    // Playback state
    private var isLiveMode = true
    private var currentPlayingTimestamp: Long = 0L
    private var isPlayerPlaying = true  // track play/pause for live indicator

    // Handlers
    private val hideControlsHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControls() }

    // Overlay managers
    private lateinit var controlOverlayManager: ControlOverlayManager
    private lateinit var epgOverlayManager: EpgOverlayManager
    private lateinit var timeRewindManager: TimeRewindOverlayManager

    // Recently watched: track how long user has been on current channel
    private var channelWatchStartTime = 0L
    private val WATCH_THRESHOLD_MS = 5 * 60 * 1000L // 5 minutes

    // Heartbeat
    private val HEARTBEAT_INTERVAL_MS = 2 * 60 * 1000L

    // Auth token (may be null if not logged in)
    private val authToken: String?
        get() = getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE)
            .getString("auth_token", null)

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
            channels = repository.getAllChannels()

            // Fetch favourites and recently watched if logged in
            authToken?.let { token ->
                launch {
                    try {
                        repository.fetchAndSyncFavourites(token)
                        repository.fetchAndSyncRecentlyWatched(token)
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }

            initializePlayer()
            setupOverlays()
            setupControlButtons()

            if (channels.isNotEmpty()) {
                playChannel(0)
            } else {
                Toast.makeText(this@PlayerActivity, "No channels found", Toast.LENGTH_LONG).show()
            }
            binding.loadingIndicator.visibility = View.GONE
        }

        startHeartbeat()
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
        isPlayerPlaying = false
        updateLiveIndicatorState()
    }

    override fun onResume() {
        super.onResume()
        player?.play()
        isPlayerPlaying = true
        updateLiveIndicatorState()
    }

    override fun onDestroy() {
        super.onDestroy()
        checkAndRecordWatchTime() // record if they watched long enough
        player?.release()
        player = null
        hideControlsHandler.removeCallbacksAndMessages(null)
    }

    // =========================================================================
    // Player init
    // =========================================================================

    private fun initializePlayer() {
        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            binding.playerView.player = exoPlayer

            exoPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        // Show spinner overlaid (video frame stays underneath)
                        Player.STATE_BUFFERING -> binding.loadingIndicator.visibility = View.VISIBLE
                        Player.STATE_READY     -> binding.loadingIndicator.visibility = View.GONE
                        Player.STATE_ENDED     -> binding.loadingIndicator.visibility = View.GONE
                        else -> {}
                    }
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlayerPlaying = playing
                    updateLiveIndicatorState()
                }

                override fun onPlayerError(error: PlaybackException) {
                    binding.loadingIndicator.visibility = View.GONE
                    val isBehindLiveWindow = run {
                        var e: Throwable? = error.cause
                        var found = false
                        while (e != null) {
                            if (e.javaClass.simpleName == "BehindLiveWindowException") { found = true; break }
                            e = e.cause
                        }
                        found
                    }
                    if (isBehindLiveWindow) {
                        Toast.makeText(this@PlayerActivity,
                            "Archive not available at this point, returning to live", Toast.LENGTH_SHORT).show()
                        returnToLive()
                    } else {
                        Toast.makeText(this@PlayerActivity, "Playback error, retrying...", Toast.LENGTH_SHORT).show()
                        hideControlsHandler.postDelayed({
                            if (!isLiveMode) returnToLive() else playChannel(currentChannelIndex)
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
            activity = this,
            binding  = binding,
            channels = channels,
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

        val rewindOverlayView = layoutInflater.inflate(R.layout.overlay_time_rewind, binding.root, false)
            .also { it.visibility = View.GONE; binding.root.addView(it) }

        timeRewindManager = TimeRewindOverlayManager(
            activity          = this,
            overlayView       = rewindOverlayView,
            channelIdProvider = { channels.getOrNull(currentChannelIndex)?.id ?: -1 },
            onTimeSelected    = { timestampMs ->
                val channel = channels.getOrNull(currentChannelIndex) ?: return@TimeRewindOverlayManager
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

        // Audio/language — silent dummy
        binding.root.findViewById<ImageButton>(R.id.btnAudioLanguage)?.setOnClickListener { /* dummy */ }
    }

    // =========================================================================
    // Playback
    // =========================================================================

    private fun playChannel(index: Int) {
        if (index < 0 || index >= channels.size) return
        checkAndRecordWatchTime()

        currentChannelIndex = index
        isLiveMode = true
        currentPlayingTimestamp = System.currentTimeMillis()
        channelWatchStartTime = System.currentTimeMillis()

        // Show top bar immediately so user knows which channel is loading
        showTopBarTemporarily()
        // Keep spinner visible overlaid — don't clear video underneath
        binding.loadingIndicator.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val streamUrl = repository.getStreamUrl(channels[index].id)
                if (streamUrl != null) {
                    playUrl(streamUrl)
                } else {
                    Toast.makeText(this@PlayerActivity, "Stream unavailable", Toast.LENGTH_SHORT).show()
                    binding.loadingIndicator.visibility = View.GONE
                }
                updateOverlayInfo()
                updateLiveIndicatorState()
            } catch (e: Exception) {
                e.printStackTrace()
                binding.loadingIndicator.visibility = View.GONE
                Toast.makeText(this@PlayerActivity, "Error loading channel", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Single entry point for ExoPlayer URL loads.
     * Uses keepContentOnPlayerReset so the current frame stays while new stream loads.
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
        val baseMs = if (isLiveMode) System.currentTimeMillis() else currentPlayingTimestamp
        val targetTimestamp = baseMs - (seconds * 1000L)

        val archiveStartMs = repository.getArchiveStartMs(channel.id)
        if (archiveStartMs != null && targetTimestamp < archiveStartMs) {
            val hoursBack = repository.getHoursBack(channel.id)
            Toast.makeText(this, "This channel only supports ${hoursBack}h rewind", Toast.LENGTH_SHORT).show()
            return
        }

        // Show spinner overlaid on current video — don't stop/clear player yet
        binding.loadingIndicator.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val streamUrl = repository.getArchiveUrl(channel.id, targetTimestamp)
                if (streamUrl != null) {
                    isLiveMode = false
                    currentPlayingTimestamp = targetTimestamp
                    playUrl(streamUrl)  // video stays on screen until new stream is ready
                    updateOverlayInfo()
                    updateLiveIndicatorState()
                } else {
                    Toast.makeText(this@PlayerActivity, "Archive unavailable for this time", Toast.LENGTH_SHORT).show()
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
        if (isLiveMode) { Toast.makeText(this, "Already at live", Toast.LENGTH_SHORT).show(); return }
        val channel = channels.getOrNull(currentChannelIndex) ?: return
        val targetTimestamp = currentPlayingTimestamp + (seconds * 1000L)
        if (targetTimestamp >= System.currentTimeMillis()) { returnToLive(); return }

        binding.loadingIndicator.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val streamUrl = repository.getArchiveUrl(channel.id, targetTimestamp)
                if (streamUrl != null) {
                    currentPlayingTimestamp = targetTimestamp
                    playUrl(streamUrl)
                    updateOverlayInfo()
                    updateLiveIndicatorState()
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
        isLiveMode = false
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
        if (isLiveMode) return
        playChannel(currentChannelIndex)
    }

    private fun togglePlayPause() {
        player?.let { exo ->
            if (exo.isPlaying) {
                exo.pause()
                binding.root.findViewById<ImageButton>(R.id.btnPlayPause)?.setImageResource(R.drawable.ic_play)
            } else {
                exo.play()
                binding.root.findViewById<ImageButton>(R.id.btnPlayPause)?.setImageResource(R.drawable.ic_pause)
            }
        }
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

        val channel = channels[currentChannelIndex]
        val willBeFavorite = !channel.isFavorite

        // Optimistic local update
        repository.setFavorite(channel.id, willBeFavorite)
        controlOverlayManager.updateFavoriteButton(willBeFavorite)

        lifecycleScope.launch {
            val success = if (willBeFavorite) {
                repository.addFavouriteRemote(token, channel.apiId)
            } else {
                repository.removeFavouriteRemote(token, channel.apiId)
            }
            if (!success) {
                // Revert on failure
                repository.setFavorite(channel.id, !willBeFavorite)
                controlOverlayManager.updateFavoriteButton(!willBeFavorite)
                Toast.makeText(this@PlayerActivity, "Failed to update favorites", Toast.LENGTH_SHORT).show()
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
            val token = authToken
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
    // UI helpers
    // =========================================================================

    private fun updateLiveIndicatorState() {
        if (isControlsVisible) {
            controlOverlayManager.updateLiveIndicator(
                isLive = isLiveMode,
                isPlaying = isPlayerPlaying
            )
        }
    }

    private fun updateOverlayInfo() {
        val channel = channels.getOrNull(currentChannelIndex) ?: return
        val playingTime = if (isLiveMode) System.currentTimeMillis() else currentPlayingTimestamp
        val currentProgram = channel.programs.find { playingTime >= it.startTime && playingTime < it.endTime }
        controlOverlayManager.updateChannelInfo(
            channel         = channel,
            currentProgram  = currentProgram,
            streamTimestamp = if (isLiveMode) null else currentPlayingTimestamp,
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

    /**
     * Shows the top bar immediately (e.g. on channel switch) without opening full controls.
     * Auto-hides after 5 seconds unless controls are opened.
     */
    private fun showTopBarTemporarily() {
        binding.root.findViewById<View>(R.id.controlOverlay)?.visibility = View.VISIBLE
        isControlsVisible = true
        updateOverlayInfo()
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        hideControlsHandler.postDelayed(hideControlsRunnable, 5000)
    }

    private fun showControls() {
        if (channels.isEmpty()) return
        isControlsVisible = true
        binding.root.findViewById<View>(R.id.controlOverlay)?.visibility = View.VISIBLE
        updateOverlayInfo()
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        hideControlsHandler.postDelayed(hideControlsRunnable, 5000)
    }

    private fun hideControls() {
        isControlsVisible = false
        binding.root.findViewById<View>(R.id.controlOverlay)?.visibility = View.GONE
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
    }

    private fun showEpg() {
        // Sync favorites and recently watched before showing EPG
        authToken?.let { token ->
            lifecycleScope.launch {
                try {
                    repository.fetchAndSyncFavourites(token)
                    repository.fetchAndSyncRecentlyWatched(token)
                } catch (e: Exception) { e.printStackTrace() }
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
    // Key routing
    // =========================================================================

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when {
            isTimeRewindVisible -> timeRewindManager.handleKeyEvent(keyCode)
            isEpgVisible        -> handleEpgKeyPress(keyCode)
            isControlsVisible   -> handleControlsKeyPress(keyCode)
            else                -> handlePlayerKeyPress(keyCode)
        } || super.onKeyDown(keyCode, event)
    }

    private fun handlePlayerKeyPress(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP   -> { changeChannel(1); true }
        KeyEvent.KEYCODE_DPAD_DOWN -> { changeChannel(-1); true }
        // LEFT/RIGHT → open EPG
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { showEpg(); true }
        // OK/Center → open controls
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { showControls(); true }
        KeyEvent.KEYCODE_BACK -> { finish(); true }
        else -> false
    }

    private fun handleControlsKeyPress(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_BACK -> { hideControls(); true }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
            // Default focus should be on play/pause
            val focused = currentFocus
            if (focused != null) focused.performClick()
            else binding.root.findViewById<ImageButton>(R.id.btnPlayPause)?.performClick()
            true
        }
        KeyEvent.KEYCODE_DPAD_UP -> {
            // If user navigates up from bottom bar to rewind row — let the system handle focus
            hideControlsHandler.removeCallbacks(hideControlsRunnable)
            hideControlsHandler.postDelayed(hideControlsRunnable, 5000)
            false // let focus system handle it
        }
        KeyEvent.KEYCODE_DPAD_DOWN -> {
            // Navigating down snaps back to bottom bar / pause button
            binding.root.findViewById<ImageButton>(R.id.btnPlayPause)?.requestFocus()
            hideControlsHandler.removeCallbacks(hideControlsRunnable)
            hideControlsHandler.postDelayed(hideControlsRunnable, 5000)
            true
        }
        else -> {
            hideControlsHandler.removeCallbacks(hideControlsRunnable)
            hideControlsHandler.postDelayed(hideControlsRunnable, 5000)
            false
        }
    }

    private fun handleEpgKeyPress(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_BACK -> { hideEpg(); true }
        else -> epgOverlayManager.handleKeyEvent(keyCode)
    }
}