package ge.mediabox.mediabox.ui.player

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

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

    private var streamExpiryJob: Job? = null

    private var currentStreamUrl: String? = null

    private var isPlayerPlaying = true
    private var userIntentionallyPaused = false

    private var playingArchiveChannelId: Int = -1

    private var archiveBaseTimestamp: Long = 0L // The start timestamp of the archive request
    private var lastArchiveUrlTemplate: String? = null // For optimized seeking

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

    private val authToken: String?
        get() = getSharedPreferences("AuthPrefs", MODE_PRIVATE).getString("auth_token", null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            binding.loadingIndicator.visibility = View.VISIBLE
            val isKa = LangPrefs.isKa(this@PlayerActivity)

            // initialize now handles category fetching, channel fetching, and favorite syncing
            repository.initialize(authToken, isKa)

            channels = repository.getAllChannels()
            initializePlayer()
            setupOverlays()
            setupControlButtons()

            val firstUnlocked = channels.indexOfFirst { !it.isLocked }
            if (firstUnlocked >= 0) playChannel(firstUnlocked)

            binding.loadingIndicator.visibility = View.GONE
            isInitialized = true
        }
    }

    private fun getCurrentAbsoluteTime(): Long {
        return if (isLiveMode) {
            livePausedAt ?: System.currentTimeMillis()
        } else {
            // archiveBaseTimestamp (the epoch you started at) + player progress
            archiveBaseTimestamp + (player?.currentPosition ?: 0L)
        }
    }

    override fun onPause() { super.onPause(); player?.pause() }
    override fun onResume() { super.onResume(); if (!userIntentionallyPaused) player?.play() }
    override fun onDestroy() {
        super.onDestroy()
        pendingStreamJob?.cancel(); pendingProgramJob?.cancel()
        pauseTickHandler.removeCallbacksAndMessages(null)
        hideControlsHandler.removeCallbacksAndMessages(null)
        player?.release(); player = null
    }

    private fun initializePlayer() {
        val rf = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this, rf).build().also { exo ->
            binding.playerView.player = exo
            exo.addListener(playerListener)
            exo.playWhenReady = true
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            binding.loadingIndicator.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
        }
        override fun onTracksChanged(tracks: Tracks) {
            if (::trackSelectionManager.isInitialized) trackSelectionManager.onTracksChanged(tracks)
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            updateQualityDisplay(videoSize.height)
        }
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
        override fun onPlayerError(error: PlaybackException) {
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) returnToLive()
        }
    }

    private fun setupOverlays() {
        controlOverlayManager = ControlOverlayManager(binding = binding, onFavoriteToggle = { toggleFavorite() })
        epgOverlayManager = EpgOverlayManager(activity = this, binding = binding, channels = channels,
            onChannelSelected = { index -> currentChannelIndex = index; playChannel(index); hideEpg() },
            onArchiveSelected = { instruction ->
                val parts = instruction.split(":")
                if (parts.size >= 4) {
                    val chId = parts[1].toIntOrNull() ?: -1
                    val ts = parts[3].toLongOrNull() ?: 0L
                    playArchiveAt(chId, ts)
                }
                hideEpg()
            }
        )
        val rewindOverlayView = layoutInflater.inflate(R.layout.overlay_time_rewind, binding.root, false).also {
            it.visibility = View.GONE; binding.root.addView(it)
        }
        timeRewindManager = TimeRewindOverlayManager(activity = this, overlayView = rewindOverlayView,
            channelIdProvider = { channels.getOrNull(currentChannelIndex)?.id ?: -1 },
            onTimeSelected = { ts -> playArchiveAt(channels[currentChannelIndex].id, ts) },
            onDismiss = { isTimeRewindVisible = false })

        val trackOverlayView = layoutInflater.inflate(R.layout.overlay_track_selection, binding.root, false).also {
            it.visibility = View.GONE; binding.root.addView(it)
        }
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

            // Target the Layout Pill instead of ImageButton
            findViewById<View>(R.id.btnQualityLayout)?.setOnClickListener {
                hideControls()
                trackSelectionManager.show(TrackSelectionOverlayManager.Mode.VIDEO)
            }

            findViewById<ImageButton>(R.id.btnAudioLanguage)?.setOnClickListener {
                hideControls()
                trackSelectionManager.show(TrackSelectionOverlayManager.Mode.AUDIO)
            }
        }
    }

    private fun playChannel(index: Int) {
        if (index !in channels.indices || channels[index].isLocked) return

        // FIX: Update index immediately so Top Bar reflects the NEW channel
        currentChannelIndex = index
        val channel = channels[index]

        isLiveMode = true
        livePausedAt = null
        archiveBaseTimestamp = 0
        playingArchiveChannelId = -1 // Reset archive state
        lastArchiveUrlTemplate = null

        updateOverlayInfo() // Sync UI (Top Bar) immediately
        fetchProgramsForCurrentChannel()
        showTopBarTemporarily()

        loadStream { repository.getStreamUrl(channel.id) }
    }

    private fun fetchProgramsForCurrentChannel() {
        val channel = channels.getOrNull(currentChannelIndex) ?: return
        pendingProgramJob?.cancel()
        pendingProgramJob = lifecycleScope.launch {
            val programs = withContext(Dispatchers.IO) {
                runCatching { ApiService.fetchPrograms(channel.apiId) }.getOrDefault(emptyList())
            }
            channel.programs = programs
            updateOverlayInfo()
        }
    }



    private fun loadStream(urlProvider: suspend () -> String?) {
        binding.loadingIndicator.visibility = View.VISIBLE
        pendingStreamJob?.cancel()
        pendingStreamJob = lifecycleScope.launch {
            val url = urlProvider()
            if (url != null) {
                currentStreamUrl = url

                // 1. Play the stream
                val mediaItem = MediaItem.fromUri(url)
                player?.setMediaItem(mediaItem)
                player?.prepare()
                player?.play()

                // 2. Schedule the seamless refresh
                scheduleStreamRefresh()

                updateOverlayInfo()
                updateLiveIndicatorState()
            }
            binding.loadingIndicator.visibility = View.GONE
        }
    }

    private fun scheduleStreamRefresh() {
        // Cancel any existing refresh timer
        streamExpiryJob?.cancel()

        val url = currentStreamUrl ?: return

        // Extract expiry from the URL parameter (the last epoch timestamp)
        val expiryMs = repository.extractExpiryFromUrl(url)
        if (expiryMs <= 0) return

        val currentTime = System.currentTimeMillis()
        // Calculate delay: Refresh 60 seconds BEFORE it expires
        val refreshDelay = (expiryMs - currentTime) - 60000L

        if (refreshDelay > 0) {
            streamExpiryJob = lifecycleScope.launch(Dispatchers.Main) {
                delay(refreshDelay)
                refreshStreamSeamlessly()
            }
        }
    }

    private suspend fun refreshStreamSeamlessly() {
        val channel = channels.getOrNull(currentChannelIndex) ?: return

        // Fetch new URL from API
        val newUrl = if (isLiveMode) {
            repository.getStreamUrl(channel.id)
        } else {
            // If in archive, use the current absolute playback time
            repository.getArchiveUrl(channel.id, getCurrentAbsoluteTime())
        }

        if (newUrl != null && newUrl != currentStreamUrl) {
            currentStreamUrl = newUrl

            // This is the "Magic" part for Seamless update in Media3/ExoPlayer:
            // By using setMediaItem with 'resetPosition = false',
            // the player swaps the manifest/token but continues playing from current buffer.
            player?.setMediaItem(MediaItem.fromUri(newUrl), false)

            // Reschedule for the NEXT expiry
            scheduleStreamRefresh()
        }
    }

    private fun updateOverlayInfo() {
        val channel = channels.getOrNull(currentChannelIndex) ?: return
        val currentTs = getCurrentAbsoluteTime()

        // Find program based on the dynamic absolute time
        val currentProgram = channel.programs.find { currentTs in it.startTime until it.endTime }

        // Update the HUD
        controlOverlayManager.updateChannelInfo(
            channel,
            currentProgram,
            if (isLiveMode && livePausedAt == null) null else currentTs,
            isPlayerPlaying
        )
    }

    private fun playArchiveAt(channelId: Int, timestampMs: Long) {
        // 1. Find and update the current channel index first
        val targetIndex = channels.indexOfFirst { it.id == channelId }
        if (targetIndex != -1) {
            currentChannelIndex = targetIndex
        }

        val channel = channels[currentChannelIndex]
        isLiveMode = false
        livePausedAt = null

        // 2. Optimization Logic:
        // Only swap the URL if we are already watching an archive of the SAME channel.
        // If it's a different channel, we MUST fetch a new URL from the API.
        if (playingArchiveChannelId == channelId && lastArchiveUrlTemplate != null) {
            archiveBaseTimestamp = timestampMs
            val optimizedUrl = repository.getOptimizedArchiveUrl(lastArchiveUrlTemplate!!, timestampMs)

            // Skip the API call and play directly
            player?.setMediaItem(MediaItem.fromUri(optimizedUrl))
            player?.prepare()
            player?.play()
            updateOverlayInfo()
        } else {
            // Different channel or first time: Fetch from API
            archiveBaseTimestamp = timestampMs
            playingArchiveChannelId = channelId

            loadStream {
                val url = repository.getArchiveUrl(channelId, timestampMs)
                lastArchiveUrlTemplate = url // Store template for future rewinds on THIS channel
                url
            }
        }

        fetchProgramsForCurrentChannel()
        updateOverlayInfo()
    }

    private fun playDirectUrl(url: String) {
        player?.setMediaItem(MediaItem.fromUri(url))
        player?.prepare()
        player?.play()
        updateOverlayInfo()
    }

    private fun returnToLive() = playChannel(currentChannelIndex)
    private fun rewindSeconds(s: Int) {
        val targetTs = getCurrentAbsoluteTime() - (s * 1000L)
        playArchiveAt(channels[currentChannelIndex].id, targetTs)
    }
    private fun forwardSeconds(s: Int) {
        val targetTs = getCurrentAbsoluteTime() + (s * 1000L)
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

    private fun changeChannel(direction: Int) {
        var index = currentChannelIndex
        repeat(channels.size) {
            index = (index + direction + channels.size) % channels.size
            if (!channels[index].isLocked) { playChannel(index); return }
        }
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
        // Show the main overlay container
        binding.root.findViewById<View>(R.id.controlOverlay)?.visibility = View.VISIBLE

        // Specifically show the Top Bar but HIDE the Bottom Section
        binding.root.findViewById<View>(R.id.topBar)?.visibility = View.VISIBLE
        binding.root.findViewById<View>(R.id.bottomSection)?.visibility = View.GONE

        updateOverlayInfo()
        rescheduleHideControls()
    }

    private fun showControls() {
        if (trackSelectionManager.isVisible) return
        isControlsVisible = true

        // Show everything
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

        // FIX: Pass the ID of the channel currently playing
        val currentId = channels.getOrNull(currentChannelIndex)?.id ?: -1
        epgOverlayManager.requestFocus(currentId)
    }

    private fun hideEpg() { isEpgVisible = false; binding.root.findViewById<View>(R.id.epgOverlay)?.visibility = View.GONE }
    private fun showTimeRewind() { hideControls(); isTimeRewindVisible = true; timeRewindManager.show() }

    private fun toggleFavorite() {
        val token = authToken ?: return
        val channel = channels.getOrNull(currentChannelIndex)?.takeIf { !it.isLocked } ?: return
        val willBeFavorite = !channel.isFavorite
        val externalId = channel.apiId
        repository.setFavoriteLocal(channel.id, willBeFavorite)
        controlOverlayManager.updateFavoriteButton(willBeFavorite)
        lifecycleScope.launch(Dispatchers.IO) {
            val success = if (willBeFavorite) repository.addFavouriteRemote(token, externalId) else repository.removeFavouriteRemote(token, externalId)
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

    @OptIn(UnstableApi::class) private fun updateQualityDisplay(height: Int) {
        val exo = player ?: return

        // Check if the manifest actually offers more than 1 choice
        val videoGroup = exo.currentTracks.groups.find { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
        val hasMultipleOptions = (videoGroup?.length ?: 0) > 1

        // Check if we are currently in "Auto" mode (no manual overrides)
        val isAutoMode = exo.trackSelectionParameters.overrides.entries.none {
            it.key.type == androidx.media3.common.C.TRACK_TYPE_VIDEO
        }

        // Only show the word "Auto" if there's actually something to auto-switch between
        val shouldShowAutoLabel = isAutoMode && hasMultipleOptions

        controlOverlayManager.updateQualityInfo(height)
    }
}