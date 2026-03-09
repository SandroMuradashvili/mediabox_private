package ge.mediabox.mediabox.ui.player

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
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import ge.mediabox.mediabox.R
import ge.mediabox.mediabox.data.repository.ChannelRepository
import ge.mediabox.mediabox.databinding.ActivityPlayerBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
    private var isTrackSelectionVisible = false

    // Playback state
    private var isLiveMode = true
    private var livePausedAt: Long? = null
    private var currentPlayingTimestamp = 0L
    private var isPlayerPlaying = true
    private var userIntentionallyPaused = false

    private val hideControlsHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControls() }

    private val pauseTickHandler = Handler(Looper.getMainLooper())
    private val pauseTickRunnable = object : Runnable {
        override fun run() {
            if (isControlsVisible) updateRewindButtonAvailability()
            pauseTickHandler.postDelayed(this, 1_000)
        }
    }

    private lateinit var controlOverlayManager: ControlOverlayManager
    private lateinit var epgOverlayManager: EpgOverlayManager
    private lateinit var timeRewindManager: TimeRewindOverlayManager
    private lateinit var trackSelectionManager: TrackSelectionOverlayManager

    private var isSyncingFavorites = false
    private var pendingStreamJob: Job? = null

    private val authToken: String?
        get() = getSharedPreferences("AuthPrefs", MODE_PRIVATE)
            .getString("auth_token", null)

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            binding.loadingIndicator.visibility = View.VISIBLE
            repository.initialize(authToken)
            authToken?.let { token ->
                launch { runCatching { repository.fetchAndSyncFavourites(token) } }
            }

            channels = repository.getAllChannels()
            initializePlayer()
            setupOverlays()
            setupControlButtons()

            val firstUnlocked = channels.indexOfFirst { !it.isLocked }
            if (firstUnlocked >= 0) playChannel(firstUnlocked)
            else Toast.makeText(this@PlayerActivity, "No accessible channels", Toast.LENGTH_LONG).show()

            binding.loadingIndicator.visibility = View.GONE
            isInitialized = true
        }
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        if (!userIntentionallyPaused) player?.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingStreamJob?.cancel()
        pauseTickHandler.removeCallbacksAndMessages(null)
        hideControlsHandler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }

    // ── Favorites sync ────────────────────────────────────────────────────────

    private suspend fun syncFavorites(token: String) {
        if (isSyncingFavorites) return
        isSyncingFavorites = true
        try {
            repository.fetchAndSyncFavourites(token)
            channels = repository.getAllChannels()
            runOnUiThread {
                if (isControlsVisible) updateOverlayInfo()
                if (isEpgVisible) epgOverlayManager.refreshData(channels)
            }
        } finally {
            isSyncingFavorites = false
        }
    }

    // ── Player init ───────────────────────────────────────────────────────────

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun initializePlayer() {
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableAudioFloatOutput(false)
            .setEnableDecoderFallback(true)

        player = ExoPlayer.Builder(this, renderersFactory).build().also { exo ->
            binding.playerView.player = exo
            exo.addListener(playerListener)
            exo.playWhenReady = true
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            binding.loadingIndicator.visibility =
                if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            isPlayerPlaying = playing
            if (isLiveMode && !playing && livePausedAt == null && userIntentionallyPaused) {
                livePausedAt = System.currentTimeMillis()
                pauseTickHandler.post(pauseTickRunnable)
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

        override fun onTracksChanged(tracks: Tracks) {
            // Feed new track info into the manager — it will rebuild lists
            trackSelectionManager.onTracksChanged(tracks)
            // Show/dim the audio button based on whether multiple audio tracks exist
            updateAudioLanguageButton()
        }

        override fun onPlayerError(error: PlaybackException) {
            binding.loadingIndicator.visibility = View.GONE
            if (error.isBehindLiveWindow()) {
                Toast.makeText(this@PlayerActivity, "Archive not available, returning to live", Toast.LENGTH_SHORT).show()
                returnToLive()
            } else {
                Toast.makeText(this@PlayerActivity, "Playback error, retrying…", Toast.LENGTH_SHORT).show()
                hideControlsHandler.postDelayed({
                    if (!isLiveMode) returnToLive() else playChannel(currentChannelIndex)
                }, 2_000)
            }
        }
    }

    private fun PlaybackException.isBehindLiveWindow(): Boolean {
        var e: Throwable? = cause
        while (e != null) {
            if (e.javaClass.simpleName == "BehindLiveWindowException") return true
            e = e.cause
        }
        return false
    }

    // ── Overlay setup ─────────────────────────────────────────────────────────

    private fun setupOverlays() {
        controlOverlayManager = ControlOverlayManager(
            binding          = binding,
            onFavoriteToggle = { toggleFavorite() }
        )

        epgOverlayManager = EpgOverlayManager(
            activity  = this,
            binding   = binding,
            channels  = channels,
            onChannelSelected = { index ->
                currentChannelIndex = index
                playChannel(index)
                hideEpg()
            },
            onArchiveSelected = { instruction ->
                val parts = instruction.split(":")
                if (instruction.startsWith("ARCHIVE_ID") && parts.size >= 4) {
                    val channelId = parts[1].toIntOrNull()
                    val timeMs    = parts[3].toLongOrNull()
                    if (channelId != null && timeMs != null) {
                        playArchiveAt(channelId, timeMs)
                        hideEpg()
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
                val channel = channels.getOrNull(currentChannelIndex) ?: return@TimeRewindOverlayManager
                isTimeRewindVisible = false
                playArchiveAt(channel.id, timestampMs)
            },
            onDismiss = { isTimeRewindVisible = false }
        )

        // ── Track selection overlay ───────────────────────────────────────────
        val trackOverlayView = layoutInflater
            .inflate(R.layout.overlay_track_selection, binding.root, false)
            .also { it.visibility = View.GONE; binding.root.addView(it) }

        trackSelectionManager = TrackSelectionOverlayManager(
            activity       = this,
            overlayView    = trackOverlayView,
            playerProvider = { player }
        )
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

            // ── Quality button (HD/SD) ────────────────────────────────────────
            // Reuse the existing btnAudioLanguage slot for quality; add btnQuality if you prefer
            // Here: long-press = quality, click = audio language (matches common TV UX)
            // OR wire two separate buttons if your layout has them.
            // Current layout has only btnAudioLanguage — we'll use it as audio,
            // and add quality switching via the YELLOW / GREEN remote key OR via
            // a new button we attach to R.id.btnQuality (added to overlay_controls).

            // Audio language button
            findViewById<ImageButton>(R.id.btnAudioLanguage)?.setOnClickListener {
                showTrackSelection(TrackSelectionOverlayManager.Mode.AUDIO)
            }

            // Quality button (HD/SD) — falls back gracefully if not in layout
            findViewById<ImageButton>(R.id.btnQuality)?.setOnClickListener {
                showTrackSelection(TrackSelectionOverlayManager.Mode.VIDEO)
            }

            findViewById<View>(R.id.btnLive)?.let { btn ->
                btn.setOnClickListener { returnToLive() }
                btn.setOnFocusChangeListener { v, hasFocus ->
                    v.scaleX = if (hasFocus) 1.08f else 1f
                    v.scaleY = if (hasFocus) 1.08f else 1f
                }
            }
        }
    }

    // ── Track selection ───────────────────────────────────────────────────────

    private fun showTrackSelection(mode: TrackSelectionOverlayManager.Mode) {
        if (mode == TrackSelectionOverlayManager.Mode.VIDEO &&
            !trackSelectionManager.hasVideoTracks()) {
            Toast.makeText(this, "No quality options for this channel", Toast.LENGTH_SHORT).show()
            return
        }
        if (mode == TrackSelectionOverlayManager.Mode.AUDIO &&
            !trackSelectionManager.hasAudioTracks()) {
            Toast.makeText(this, "No alternate audio for this channel", Toast.LENGTH_SHORT).show()
            return
        }
        hideControls()
        isTrackSelectionVisible = true
        trackSelectionManager.show(mode)
    }

    /**
     * Updates the audio language button icon/alpha based on whether
     * multiple audio tracks are actually available.
     */
    private fun updateAudioLanguageButton() {
        val hasAudio   = trackSelectionManager.hasAudioTracks()
        val hasQuality = trackSelectionManager.hasVideoTracks()

        binding.root.findViewById<ImageButton>(R.id.btnAudioLanguage)?.alpha =
            if (hasAudio) 1.0f else 0.35f

        binding.root.findViewById<ImageButton>(R.id.btnQuality)?.alpha =
            if (hasQuality) 1.0f else 0.35f
    }

    // ── Playback ──────────────────────────────────────────────────────────────

    private fun playChannel(index: Int) {
        if (index !in channels.indices || channels[index].isLocked) return
        currentChannelIndex     = index
        isLiveMode              = true
        livePausedAt            = null
        userIntentionallyPaused = false
        currentPlayingTimestamp = System.currentTimeMillis()
        pauseTickHandler.removeCallbacks(pauseTickRunnable)

        showTopBarTemporarily()
        loadStream { repository.getStreamUrl(channels[index].id) }
    }

    private fun rewindSeconds(seconds: Int) {
        val channel = channels.getOrNull(currentChannelIndex) ?: return
        val baseMs = livePausedAt ?: if (!isLiveMode) currentPlayingTimestamp else System.currentTimeMillis()
        val target = baseMs - seconds * 1_000L

        val archiveStart = repository.getArchiveStartMs(channel.id)
        if (archiveStart != null && target < archiveStart) {
            Toast.makeText(this, "This channel only supports ${repository.getHoursBack(channel.id)}h rewind", Toast.LENGTH_SHORT).show()
            return
        }
        loadArchive(channel.id, target)
    }

    private fun forwardSeconds(seconds: Int) {
        if (isLiveMode && livePausedAt == null) {
            Toast.makeText(this, "Already at live", Toast.LENGTH_SHORT).show()
            return
        }
        val channel = channels.getOrNull(currentChannelIndex) ?: return
        val target  = (livePausedAt ?: currentPlayingTimestamp) + seconds * 1_000L
        if (target >= System.currentTimeMillis()) { returnToLive(); return }
        loadArchive(channel.id, target)
    }

    private fun playArchiveAt(channelId: Int, timestampMs: Long) {
        val index = channels.indexOfFirst { it.id == channelId }
        if (index != -1) currentChannelIndex = index
        loadArchive(channelId, timestampMs)
    }

    private fun loadArchive(channelId: Int, timestampMs: Long) {
        isLiveMode              = false
        livePausedAt            = null
        userIntentionallyPaused = false
        currentPlayingTimestamp = timestampMs
        pauseTickHandler.removeCallbacks(pauseTickRunnable)
        loadStream { repository.getArchiveUrl(channelId, timestampMs) }
    }

    private fun loadStream(urlProvider: suspend () -> String?) {
        binding.loadingIndicator.visibility = View.VISIBLE
        pendingStreamJob?.cancel()
        pendingStreamJob = lifecycleScope.launch {
            try {
                val url = urlProvider()
                if (url != null) {
                    playUrl(url)
                    updateOverlayInfo()
                    updateLiveIndicatorState()
                    updateRewindButtonAvailability()
                } else {
                    Toast.makeText(this@PlayerActivity, "Stream unavailable", Toast.LENGTH_SHORT).show()
                    binding.loadingIndicator.visibility = View.GONE
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) return@launch
                e.printStackTrace()
                binding.loadingIndicator.visibility = View.GONE
                Toast.makeText(this@PlayerActivity, "Error loading stream", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playUrl(url: String) {
        val exo = player ?: return
        exo.pause()
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        exo.play()
    }

    private fun returnToLive() {
        livePausedAt            = null
        userIntentionallyPaused = false
        pauseTickHandler.removeCallbacks(pauseTickRunnable)
        if (isLiveMode) {
            player?.play()
            updateLiveIndicatorState()
            updateRewindButtonAvailability()
        } else {
            playChannel(currentChannelIndex)
        }
    }

    private fun togglePlayPause() {
        val exo = player ?: return
        val nowPlaying = exo.isPlaying
        userIntentionallyPaused = nowPlaying
        if (nowPlaying) exo.pause() else exo.play()
        binding.root.findViewById<ImageButton>(R.id.btnPlayPause)
            ?.setImageResource(if (nowPlaying) R.drawable.ic_play else R.drawable.ic_pause)
    }

    // ── Channel switching ─────────────────────────────────────────────────────

    private fun changeChannel(direction: Int) {
        if (channels.isEmpty()) return
        var index = currentChannelIndex
        repeat(channels.size) {
            index = (index + direction + channels.size) % channels.size
            if (!channels[index].isLocked) { playChannel(index); return }
        }
    }

    // ── UI state ──────────────────────────────────────────────────────────────

    private fun updateLiveIndicatorState() {
        val trulyLive = isLiveMode && isPlayerPlaying && livePausedAt == null
        controlOverlayManager.updateLiveIndicator(isLive = trulyLive, isPlaying = isPlayerPlaying)
        binding.root.findViewById<ImageButton>(R.id.btnPlayPause)
            ?.setImageResource(if (isPlayerPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun updateRewindButtonAvailability() {
        val pausedSeconds = livePausedAt?.let { (System.currentTimeMillis() - it) / 1_000L } ?: 0L
        listOf(
            Triple(R.id.layoutForward15s, isLiveMode, pausedSeconds >= 15),
            Triple(R.id.layoutForward1m,  isLiveMode, pausedSeconds >= 60),
            Triple(R.id.layoutForward5m,  isLiveMode, pausedSeconds >= 300)
        ).forEach { (id, live, hasEnough) ->
            val enabled = !live || hasEnough
            binding.root.findViewById<View>(id)?.apply {
                alpha = if (enabled) 1f else 0.3f
                isEnabled = enabled
            }
        }
    }

    private fun updateOverlayInfo() {
        val channel = channels.getOrNull(currentChannelIndex) ?: return
        val playingTime = livePausedAt ?: if (!isLiveMode) currentPlayingTimestamp else System.currentTimeMillis()
        val currentProgram = channel.programs.find { playingTime in it.startTime until it.endTime }
        controlOverlayManager.updateChannelInfo(
            channel         = channel,
            currentProgram  = currentProgram,
            streamTimestamp = if (isLiveMode && livePausedAt == null) null else playingTime,
            isPlaying       = isPlayerPlaying
        )
    }

    private fun showTopBarTemporarily() {
        binding.root.findViewById<View>(R.id.controlOverlay)?.visibility = View.VISIBLE
        binding.root.findViewById<View>(R.id.bottomSection)?.visibility = View.GONE
        isControlsVisible = false
        updateOverlayInfo()
        updateLiveIndicatorState()
        updateRewindButtonAvailability()
        rescheduleHideControls()
    }

    private fun showControls() {
        if (channels.isEmpty()) return
        isControlsVisible = true
        binding.root.findViewById<View>(R.id.controlOverlay)?.visibility = View.VISIBLE
        binding.root.findViewById<View>(R.id.bottomSection)?.visibility = View.VISIBLE
        updateOverlayInfo()
        updateLiveIndicatorState()
        updateRewindButtonAvailability()
        updateAudioLanguageButton()
        binding.root.findViewById<ImageButton>(R.id.btnPlayPause)?.requestFocus()
        rescheduleHideControls()
    }

    private fun hideControls() {
        isControlsVisible = false
        binding.root.findViewById<View>(R.id.controlOverlay)?.visibility = View.GONE
        binding.root.findViewById<View>(R.id.bottomSection)?.visibility = View.GONE
        hideControlsHandler.removeCallbacksAndMessages(null)
    }

    private fun rescheduleHideControls() {
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        hideControlsHandler.postDelayed(hideControlsRunnable, 5_000)
    }

    private fun showEpg() {
        authToken?.let { token ->
            lifecycleScope.launch { runCatching { syncFavorites(token) } }
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

    // ── Favorites ─────────────────────────────────────────────────────────────

    private fun toggleFavorite() {
        val token = authToken ?: run {
            Toast.makeText(this, "Please log in to use favorites", Toast.LENGTH_SHORT).show()
            return
        }
        val channel = channels.getOrNull(currentChannelIndex)?.takeIf { !it.isLocked } ?: return
        val willBeFavorite = !channel.isFavorite

        repository.setFavorite(channel.id, willBeFavorite)
        channels = repository.getAllChannels()
        controlOverlayManager.updateFavoriteButton(willBeFavorite)

        lifecycleScope.launch {
            val success = runCatching {
                if (willBeFavorite) repository.addFavouriteRemote(token, channel.apiId)
                else repository.removeFavouriteRemote(token, channel.apiId)
            }.getOrDefault(false)

            if (!success) {
                repository.setFavorite(channel.id, !willBeFavorite)
                channels = repository.getAllChannels()
                runOnUiThread {
                    controlOverlayManager.updateFavoriteButton(!willBeFavorite)
                    Toast.makeText(this@PlayerActivity, "Failed to update favorites", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ── Key routing ───────────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!isInitialized) return true
        val handled = when {
            isTrackSelectionVisible -> handleTrackSelectionKey(keyCode)
            isTimeRewindVisible     -> timeRewindManager.handleKeyEvent(keyCode)
            isEpgVisible            -> handleEpgKey(keyCode)
            isControlsVisible       -> handleControlsKey(keyCode)
            else                    -> handlePlayerKey(keyCode)
        }
        return handled || super.onKeyDown(keyCode, event)
    }

    private fun handleTrackSelectionKey(keyCode: Int): Boolean {
        val consumed = trackSelectionManager.handleKeyEvent(keyCode)
        if (!trackSelectionManager.isVisible) isTrackSelectionVisible = false
        return consumed
    }

    private fun handlePlayerKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP    -> { changeChannel(1);  true }
        KeyEvent.KEYCODE_DPAD_DOWN  -> { changeChannel(-1); true }
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT -> { showEpg(); true }
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER      -> { showControls(); true }
        // Remote colour buttons — standard TV convention
        KeyEvent.KEYCODE_PROG_RED   -> { showTrackSelection(TrackSelectionOverlayManager.Mode.VIDEO); true }
        KeyEvent.KEYCODE_PROG_GREEN -> { showTrackSelection(TrackSelectionOverlayManager.Mode.AUDIO); true }
        KeyEvent.KEYCODE_BACK       -> {
            if (binding.root.findViewById<View>(R.id.controlOverlay)?.visibility == View.VISIBLE) {
                hideControls()
            } else {
                finish()
            }
            true
        }
        else -> false
    }

    private fun handleControlsKey(keyCode: Int): Boolean {
        rescheduleHideControls()
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { hideControls(); true }
            KeyEvent.KEYCODE_PROG_RED   -> { showTrackSelection(TrackSelectionOverlayManager.Mode.VIDEO); true }
            KeyEvent.KEYCODE_PROG_GREEN -> { showTrackSelection(TrackSelectionOverlayManager.Mode.AUDIO); true }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                (currentFocus ?: binding.root.findViewById<ImageButton>(R.id.btnPlayPause))
                    ?.performClick()
                true
            }
            else -> false
        }
    }

    private fun handleEpgKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_BACK -> { hideEpg(); true }
        else -> epgOverlayManager.handleKeyEvent(keyCode)
    }
}