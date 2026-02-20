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
import androidx.media3.exoplayer.ExoPlayer
import ge.mediabox.mediabox.R
import ge.mediabox.mediabox.data.repository.ChannelRepository
import ge.mediabox.mediabox.databinding.ActivityPlayerBinding
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private val repository = ChannelRepository

    private var currentChannelIndex = 0
    private var channels = repository.getAllChannels()
    private var isControlsVisible = false
    private var isEpgVisible = false
    private var isTimeRewindVisible = false

    // Archive mode tracking
    private var isLiveMode = true
    private var currentPlayingTimestamp: Long = 0L

    private val hideControlsHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControls() }

    private lateinit var controlOverlayManager: ControlOverlayManager
    private lateinit var epgOverlayManager: EpgOverlayManager
    private lateinit var timeRewindManager: TimeRewindOverlayManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            binding.loadingIndicator.visibility = View.VISIBLE
            repository.initialize()
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
        }
    }

    // -------------------------------------------------------------------------
    // Player init
    // -------------------------------------------------------------------------

    private fun initializePlayer() {
        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
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

    // -------------------------------------------------------------------------
    // Overlay setup
    // -------------------------------------------------------------------------

    private fun setupOverlays() {
        controlOverlayManager = ControlOverlayManager(
            binding = binding,
            onFavoriteToggle = { toggleFavorite() }
        )

        epgOverlayManager = EpgOverlayManager(
            activity = this,
            binding = binding,
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

        // Inflate time-rewind overlay and attach to root
        val rewindOverlayView = layoutInflater.inflate(
            R.layout.overlay_time_rewind,
            binding.root,
            false
        ).also {
            it.visibility = View.GONE
            binding.root.addView(it)
        }

        timeRewindManager = TimeRewindOverlayManager(
            activity          = this,
            overlayView       = rewindOverlayView,
            channelIdProvider = { channels.getOrNull(currentChannelIndex)?.id ?: -1 },
            onTimeSelected    = { timestampMs ->
                val channel = channels.getOrNull(currentChannelIndex) ?: return@TimeRewindOverlayManager
                isTimeRewindVisible = false
                playArchiveAt(channel.id, timestampMs)
            },
            onDismiss = {
                isTimeRewindVisible = false
            }
        )
    }

    private fun setupControlButtons() {
        binding.root.findViewById<ImageButton>(R.id.btnRewind15s)?.setOnClickListener  { rewindSeconds(15) }
        binding.root.findViewById<ImageButton>(R.id.btnRewind1m)?.setOnClickListener   { rewindSeconds(60) }
        binding.root.findViewById<ImageButton>(R.id.btnRewind5m)?.setOnClickListener   { rewindSeconds(300) }
        binding.root.findViewById<ImageButton>(R.id.btnForward15s)?.setOnClickListener { forwardSeconds(15) }
        binding.root.findViewById<ImageButton>(R.id.btnForward1m)?.setOnClickListener  { forwardSeconds(60) }
        binding.root.findViewById<ImageButton>(R.id.btnForward5m)?.setOnClickListener  { forwardSeconds(300) }

        // Opens the date/time picker instead of a fixed 30-min jump
        binding.root.findViewById<ImageButton>(R.id.btnTimeRewind)?.setOnClickListener {
            showTimeRewind()
        }

        binding.root.findViewById<View>(R.id.btnLive)?.let { liveButton ->
            liveButton.setOnClickListener { returnToLive() }
            liveButton.setOnFocusChangeListener { v, hasFocus ->
                v.scaleX = if (hasFocus) 1.1f else 1.0f
                v.scaleY = if (hasFocus) 1.1f else 1.0f
            }
        }

        binding.root.findViewById<ImageButton>(R.id.btnPlayPause)?.setOnClickListener {
            togglePlayPause()
        }
    }

    // -------------------------------------------------------------------------
    // Playback
    // -------------------------------------------------------------------------

    private fun playChannel(index: Int) {
        if (index < 0 || index >= channels.size) return
        currentChannelIndex = index
        isLiveMode = true
        currentPlayingTimestamp = System.currentTimeMillis()
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
                updateLiveIndicator()
            } catch (e: Exception) {
                e.printStackTrace()
                binding.loadingIndicator.visibility = View.GONE
                Toast.makeText(this@PlayerActivity, "Error loading channel", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Single entry point for all ExoPlayer URL loads — stops previous stream first. */
    private fun playUrl(url: String) {
        player?.apply {
            stop()
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

        binding.loadingIndicator.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val streamUrl = repository.getArchiveUrl(channel.id, targetTimestamp)
                if (streamUrl != null) {
                    isLiveMode = false
                    currentPlayingTimestamp = targetTimestamp
                    playUrl(streamUrl)
                    updateOverlayInfo()
                    updateLiveIndicator()
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
        if (isLiveMode) {
            Toast.makeText(this, "Already at live", Toast.LENGTH_SHORT).show()
            return
        }
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
                    updateLiveIndicator()
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
                    updateLiveIndicator()
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

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private fun updateLiveIndicator() {
        binding.root.findViewById<View>(R.id.btnLive)?.alpha = if (isLiveMode) 1.0f else 0.4f
    }

    private fun updateOverlayInfo() {
        val channel = channels.getOrNull(currentChannelIndex) ?: return
        val playingTime = if (isLiveMode) System.currentTimeMillis() else currentPlayingTimestamp
        val currentProgram = channel.programs.find { playingTime >= it.startTime && playingTime < it.endTime }
        controlOverlayManager.updateChannelInfo(
            channel = channel,
            currentProgram = currentProgram,
            streamTimestamp = if (isLiveMode) null else currentPlayingTimestamp
        )
    }

    private fun changeChannel(direction: Int) {
        if (channels.isEmpty()) return
        val newIndex = (currentChannelIndex + direction).let {
            when { it < 0 -> channels.size - 1; it >= channels.size -> 0; else -> it }
        }
        playChannel(newIndex)
    }

    private fun toggleFavorite() {
        if (channels.isEmpty()) return
        val channel = channels[currentChannelIndex]
        repository.toggleFavorite(channel.id)
        channel.isFavorite = !channel.isFavorite
        controlOverlayManager.updateFavoriteButton(channel.isFavorite)
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

    // -------------------------------------------------------------------------
    // Key routing
    // -------------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when {
            isTimeRewindVisible -> timeRewindManager.handleKeyEvent(keyCode)
            isEpgVisible        -> handleEpgKeyPress(keyCode)
            isControlsVisible   -> handleControlsKeyPress(keyCode)
            else                -> handlePlayerKeyPress(keyCode)
        } || super.onKeyDown(keyCode, event)
    }

    private fun handlePlayerKeyPress(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP                               -> { changeChannel(1); true }
        KeyEvent.KEYCODE_DPAD_DOWN                             -> { changeChannel(-1); true }
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { showControls(); true }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER   -> { showEpg(); true }
        KeyEvent.KEYCODE_BACK                                  -> { finish(); true }
        else -> false
    }

    private fun handleControlsKeyPress(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_BACK -> { hideControls(); true }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { currentFocus?.performClick(); true }
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

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onPause() { super.onPause(); player?.pause() }
    override fun onResume() { super.onResume(); player?.play() }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
    }
}