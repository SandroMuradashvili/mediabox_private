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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.TrackSelectionDialogBuilder
import ge.mediabox.mediabox.R
import ge.mediabox.mediabox.data.api.ApiService
import ge.mediabox.mediabox.data.repository.ChannelRepository
import ge.mediabox.mediabox.databinding.ActivityPlayerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private var pendingStreamJob: Job? = null
    private var pendingProgramJob: Job? = null

    private val authToken: String?
        get() = getSharedPreferences("AuthPrefs", MODE_PRIVATE).getString("auth_token", null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            binding.loadingIndicator.visibility = View.VISIBLE
            repository.initialize(authToken)
            authToken?.let { token -> launch { runCatching { repository.fetchAndSyncFavourites(token) } } }

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

    override fun onPause() { super.onPause(); player?.pause() }
    override fun onResume() { super.onResume(); if (!userIntentionallyPaused) player?.play() }
    override fun onDestroy() {
        super.onDestroy()
        pendingStreamJob?.cancel()
        pendingProgramJob?.cancel()
        pauseTickHandler.removeCallbacksAndMessages(null)
        hideControlsHandler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
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
            it.visibility = View.GONE
            binding.root.addView(it)
        }
        timeRewindManager = TimeRewindOverlayManager(activity = this, overlayView = rewindOverlayView,
            channelIdProvider = { channels.getOrNull(currentChannelIndex)?.id ?: -1 },
            onTimeSelected = { ts -> playArchiveAt(channels[currentChannelIndex].id, ts) },
            onDismiss = { isTimeRewindVisible = false })
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

            // RESTORED QUALITY/AUDIO SWITCHER
            findViewById<ImageButton>(R.id.btnAudioLanguage)?.setOnClickListener {
                showTrackSelectionDialog()
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun showTrackSelectionDialog() {
        val exo = player ?: return

        // This builder creates a dialog to switch between Audio tracks and Video qualities
        val dialogBuilder = TrackSelectionDialogBuilder(
            this,
            "Select Quality / Audio",
            exo,
            C.TRACK_TYPE_VIDEO // This allows selecting video quality
        )

        // Allow the user to also see audio tracks in the same dialog logic
        dialogBuilder.setShowDisableOption(false)
        dialogBuilder.setAllowAdaptiveSelections(true)

        val dialog = dialogBuilder.build()
        dialog.show()
    }

    private fun playChannel(index: Int) {
        if (index !in channels.indices || channels[index].isLocked) return
        currentChannelIndex = index
        isLiveMode = true
        livePausedAt = null
        userIntentionallyPaused = false
        currentPlayingTimestamp = System.currentTimeMillis()

        fetchProgramsForCurrentChannel()
        showTopBarTemporarily()
        loadStream { repository.getStreamUrl(channels[index].id) }
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

    private fun loadArchive(channelId: Int, timestampMs: Long) {
        isLiveMode = false
        livePausedAt = null
        userIntentionallyPaused = false
        currentPlayingTimestamp = timestampMs
        fetchProgramsForCurrentChannel()
        loadStream { repository.getArchiveUrl(channelId, timestampMs) }
    }

    private fun loadStream(urlProvider: suspend () -> String?) {
        binding.loadingIndicator.visibility = View.VISIBLE
        pendingStreamJob?.cancel()
        pendingStreamJob = lifecycleScope.launch {
            val url = urlProvider()
            if (url != null) {
                player?.setMediaItem(MediaItem.fromUri(url))
                player?.prepare()
                player?.play()
                updateOverlayInfo()
                updateLiveIndicatorState()
            }
            binding.loadingIndicator.visibility = View.GONE
        }
    }

    private fun updateOverlayInfo() {
        val channel = channels.getOrNull(currentChannelIndex) ?: return
        val playingTime = livePausedAt ?: if (!isLiveMode) currentPlayingTimestamp else System.currentTimeMillis()
        val currentProgram = channel.programs.find { playingTime in it.startTime until it.endTime }
        controlOverlayManager.updateChannelInfo(channel, currentProgram, if (isLiveMode && livePausedAt == null) null else playingTime, isPlayerPlaying)
    }

    private fun playArchiveAt(id: Int, ts: Long) = loadArchive(id, ts)
    private fun returnToLive() = playChannel(currentChannelIndex)
    private fun rewindSeconds(s: Int) = loadArchive(channels[currentChannelIndex].id, (livePausedAt ?: currentPlayingTimestamp) - s.toLong() * 1000L)
    private fun forwardSeconds(s: Int) = loadArchive(channels[currentChannelIndex].id, (livePausedAt ?: currentPlayingTimestamp) + s.toLong() * 1000L)

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

        val buttons = listOf(
            Pair(R.id.layoutForward15s, 15L),
            Pair(R.id.layoutForward1m, 60L),
            Pair(R.id.layoutForward5m, 300L)
        )

        for (button in buttons) {
            val id = button.first
            val required = button.second
            val enabled = !isLiveMode || pausedSeconds >= required
            binding.root.findViewById<View>(id)?.apply {
                alpha = if (enabled) 1f else 0.3f
                isEnabled = enabled
            }
        }
    }

    private fun showTopBarTemporarily() {
        binding.root.findViewById<View>(R.id.controlOverlay)?.visibility = View.VISIBLE
        updateOverlayInfo()
        rescheduleHideControls()
    }

    private fun showControls() {
        isControlsVisible = true
        binding.root.findViewById<View>(R.id.controlOverlay)?.visibility = View.VISIBLE
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
        epgOverlayManager.requestFocus()
    }

    private fun hideEpg() {
        isEpgVisible = false
        binding.root.findViewById<View>(R.id.epgOverlay)?.visibility = View.GONE
    }

    private fun showTimeRewind() { hideControls(); isTimeRewindVisible = true; timeRewindManager.show() }

    private fun toggleFavorite() {
        val token = authToken ?: return
        val channel = channels.getOrNull(currentChannelIndex)?.takeIf { !it.isLocked } ?: return
        val willBeFavorite = !channel.isFavorite
        repository.setFavorite(channel.id, willBeFavorite)
        controlOverlayManager.updateFavoriteButton(willBeFavorite)
        lifecycleScope.launch {
            if (willBeFavorite) repository.addFavouriteRemote(token, channel.apiId)
            else repository.removeFavouriteRemote(token, channel.apiId)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!isInitialized) return true

        val handled = when {
            isTimeRewindVisible -> timeRewindManager.handleKeyEvent(keyCode)

            isEpgVisible -> {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    hideEpg()
                    true
                } else {
                    epgOverlayManager.handleKeyEvent(keyCode)
                }
            }

            isControlsVisible -> {
                rescheduleHideControls()
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    hideControls()
                    true
                } else false
            }

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
}