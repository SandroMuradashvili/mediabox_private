package ge.mediabox.mediabox.ui.player

import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.ImageButton
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

            val firstUnlocked = channels.indexOfFirst { !it.isLocked }
            if (firstUnlocked >= 0) {
                currentChannelIndex = firstUnlocked
                // Use a short delay so the Top Bar isn't "blocked" by the activity loading
                zapHandler.postDelayed({
                    playChannel(firstUnlocked)
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
                pauseTickHandler.post(pauseTickRunnable)
            } else if (playing && livePausedAt != null) {
                livePausedAt = null
                pauseTickHandler.removeCallbacks(pauseTickRunnable)
            }
            updateLiveIndicatorState()
            updateRewindButtonAvailability()
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
        isLiveMode = true
        livePausedAt = null
        archiveBaseTimestamp = 0
        playingArchiveChannelId = -1
        lastArchiveUrlTemplate = null

        if (!isBrowsing) binding.videoPlaceholder.visibility = View.VISIBLE

        updateOverlayInfo()
        fetchProgramsForCurrentChannel()
        prefetchNeighbors(index)
        loadStream { repository.getStreamUrl(channel.id, useCache = true) }
    }

    private fun loadStream(urlProvider: suspend () -> String?) {
        pendingStreamJob?.cancel()
        pendingStreamJob = lifecycleScope.launch {
            val channel = channels.getOrNull(currentChannelIndex)

            // We fetch the full response so we can get the 'hoursBack' metadata
            val resp = withContext(Dispatchers.IO) {
                if (isLiveMode) {
                    channel?.let { ApiService.fetchStreamUrl(it.apiId, "tv-device", authToken) }
                } else null
            }

            val url = resp?.url ?: urlProvider()

            // Update the archive window limit in the channel object
            if (resp != null && channel != null) {
                if (resp.hoursBack > 0) channel.hoursBack = resp.hoursBack
            }

            if (url != null) {
                currentStreamUrl = url
                player?.setMediaItem(MediaItem.fromUri(url))
                player?.prepare()
                player?.play()
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
            if (!channels[next].isLocked) repository.getStreamUrl(channels[next].id)
            if (!channels[prev].isLocked) repository.getStreamUrl(channels[prev].id)
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
            if (isLiveMode) repository.getStreamUrl(channel.id)
            else repository.getArchiveUrl(channel.id, getCurrentAbsoluteTime())
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

    private fun playArchiveAt(channelId: Int, timestampMs: Long) {
        val targetIndex = channels.indexOfFirst { it.id == channelId }
        if (targetIndex != -1) currentChannelIndex = targetIndex

        // Ensure UI shows up during archive switch
        isBrowsing = false
        binding.videoPlaceholder.visibility = View.VISIBLE

        val channel = channels[currentChannelIndex]
        isLiveMode = false
        livePausedAt = null

        // 1. Fetch programs for this specific time
        fetchProgramsForCurrentChannel()

        if (playingArchiveChannelId == channelId && lastArchiveUrlTemplate != null) {
            archiveBaseTimestamp = timestampMs
            val optimizedUrl = repository.getOptimizedArchiveUrl(lastArchiveUrlTemplate!!, timestampMs)
            player?.setMediaItem(MediaItem.fromUri(optimizedUrl))
            player?.prepare(); player?.play()
            updateOverlayInfo()
        } else {
            archiveBaseTimestamp = timestampMs
            playingArchiveChannelId = channelId
            loadStream {
                // Fetch archive URL and its specific metadata
                val resp = withContext(Dispatchers.IO) {
                    ApiService.fetchArchiveUrl(channel.apiId, timestampMs / 1000, "tv-device", authToken)
                }
                if (resp != null && resp.hoursBack > 0) channel.hoursBack = resp.hoursBack
                lastArchiveUrlTemplate = resp?.url
                resp?.url
            }
        }
        showTopBarTemporarily()
    }

    private fun fetchProgramsForCurrentChannel() {
        val channel = channels.getOrNull(currentChannelIndex) ?: return
        pendingProgramJob?.cancel()
        pendingProgramJob = lifecycleScope.launch {
            val programs = withContext(Dispatchers.IO) {
                runCatching { ApiService.fetchPrograms(channel.apiId) }.getOrDefault(emptyList())
            }
            channel.programs = programs

            // KEY FIX: After programs are downloaded, update the HUD title immediately
            updateOverlayInfo()
        }
    }

    private fun getCurrentAbsoluteTime(): Long = if (isLiveMode) livePausedAt ?: System.currentTimeMillis() else archiveBaseTimestamp + (player?.currentPosition ?: 0L)
    override fun onPause() { super.onPause(); player?.pause() }
    override fun onResume() { super.onResume(); if (!userIntentionallyPaused) player?.play() }
    override fun onDestroy() {
        super.onDestroy()
        pendingStreamJob?.cancel(); pendingProgramJob?.cancel()
        zapHandler.removeCallbacksAndMessages(null)
        pauseTickHandler.removeCallbacksAndMessages(null)
        hideControlsHandler.removeCallbacksAndMessages(null)
        player?.release(); player = null
    }

    private fun setupOverlays() {
        controlOverlayManager = ControlOverlayManager(binding = binding, onFavoriteToggle = { toggleFavorite() })
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
    private fun rewindSeconds(s: Int) = playArchiveAt(channels[currentChannelIndex].id, getCurrentAbsoluteTime() - (s * 1000L))
    private fun forwardSeconds(s: Int) {
        val targetTs = getCurrentAbsoluteTime() + (s * 1000L)
        if (targetTs >= System.currentTimeMillis()) returnToLive() else playArchiveAt(channels[currentChannelIndex].id, targetTs)
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

    private fun updateRewindButtonAvailability() {
        val pausedSeconds = livePausedAt?.let { (System.currentTimeMillis() - it) / 1000L } ?: 0L
        val buttons = listOf(Pair(R.id.layoutForward15s, 15L), Pair(R.id.layoutForward1m, 60L), Pair(R.id.layoutForward5m, 300L))
        for (button in buttons) {
            val enabled = !isLiveMode || pausedSeconds >= button.second
            binding.root.findViewById<View>(button.first)?.apply { alpha = if (enabled) 1f else 0.3f; isEnabled = enabled }
        }
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
    private fun showTimeRewind() { hideControls(); isTimeRewindVisible = true; timeRewindManager.show() }

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