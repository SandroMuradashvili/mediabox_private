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
import ge.mediabox.mediabox.data.api.ApiService
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


    private lateinit var controlOverlayManager: ControlOverlayManager
    private lateinit var epgOverlayManager: EpgOverlayManager
    private lateinit var timeRewindManager: TimeRewindOverlayManager
    private lateinit var trackSelectionManager: TrackSelectionOverlayManager
    private var pendingStreamJob: Job? = null
    private var pendingProgramJob: Job? = null

    private val authToken: String? get() = getSharedPreferences("AuthPrefs", MODE_PRIVATE).getString("auth_token", null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    override fun onPause() { super.onPause(); player?.pause() }
    override fun onResume() { super.onResume(); if (!userIntentionallyPaused) player?.play() }
    override fun onDestroy() {
        super.onDestroy()
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
        }
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
                KeyEvent.KEYCODE_BACK -> { finish(); true }
                else -> false
            }
        }
        return handled || super.onKeyDown(keyCode, event)
    }

    @OptIn(UnstableApi::class) private fun updateQualityDisplay(height: Int) = controlOverlayManager.updateQualityInfo(height)
}