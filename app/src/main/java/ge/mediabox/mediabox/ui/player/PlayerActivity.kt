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

    // Archive mode tracking
    private var isLiveMode = true

    private val hideControlsHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControls() }

    private lateinit var controlOverlayManager: ControlOverlayManager
    private lateinit var epgOverlayManager: EpgOverlayManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize repository and load channels
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

    private fun initializePlayer() {
        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            binding.playerView.player = exoPlayer

            exoPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> {
                            binding.loadingIndicator.visibility = View.VISIBLE
                        }
                        Player.STATE_READY -> {
                            binding.loadingIndicator.visibility = View.GONE
                        }
                    }
                }
            })

            exoPlayer.playWhenReady = true
        }
    }

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
                // Instruction format: "ARCHIVE_ID:123:TIME:17000000"
                if (instruction.startsWith("ARCHIVE_ID")) {
                    val parts = instruction.split(":")
                    if (parts.size >= 4) {
                        val channelId = parts[1].toIntOrNull()
                        val timeMs = parts[3].toLongOrNull()
                        if (channelId != null && timeMs != null) {
                            playArchiveAt(channelId, timeMs)
                            hideEpg()
                        }
                    }
                }
            }
        )
    }

    private fun setupControlButtons() {
        // Rewind buttons
        binding.root.findViewById<ImageButton>(R.id.btnRewind15s)?.setOnClickListener {
            rewindArchiveSeconds(15)
        }
        binding.root.findViewById<ImageButton>(R.id.btnRewind1m)?.setOnClickListener {
            rewindArchiveSeconds(60)
        }
        binding.root.findViewById<ImageButton>(R.id.btnRewind5m)?.setOnClickListener {
            rewindArchiveSeconds(300)
        }

        // Forward buttons
        binding.root.findViewById<ImageButton>(R.id.btnForward15s)?.setOnClickListener {
            seekBySeconds(15)
        }
        binding.root.findViewById<ImageButton>(R.id.btnForward1m)?.setOnClickListener {
            seekBySeconds(60)
        }
        binding.root.findViewById<ImageButton>(R.id.btnForward5m)?.setOnClickListener {
            seekBySeconds(300)
        }

        // Time control button – for now, jump back 30 minutes
        binding.root.findViewById<ImageButton>(R.id.btnTimeRewind)?.setOnClickListener {
            rewindArchiveSeconds(30 * 60)
        }

        // LIVE button
        binding.root.findViewById<View>(R.id.btnLive)?.let { liveButton ->
            liveButton.setOnClickListener {
                returnToLive()
            }
            liveButton.setOnFocusChangeListener { v, hasFocus ->
                v.scaleX = if (hasFocus) 1.1f else 1.0f
                v.scaleY = if (hasFocus) 1.1f else 1.0f
            }
        }

        // Play/Pause button
        binding.root.findViewById<ImageButton>(R.id.btnPlayPause)?.setOnClickListener {
            togglePlayPause()
        }
    }

    private fun playChannel(index: Int) {
        if (index < 0 || index >= channels.size) return

        currentChannelIndex = index
        val channel = channels[index]
        isLiveMode = true

        binding.loadingIndicator.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val streamUrl = repository.getStreamUrl(channel.id)

                if (streamUrl != null) {
                    player?.apply {
                        setMediaItem(MediaItem.fromUri(streamUrl))
                        prepare()
                        play()
                    }
                } else {
                    Toast.makeText(this@PlayerActivity, "Stream unavailable", Toast.LENGTH_SHORT).show()
                    binding.loadingIndicator.visibility = View.GONE
                }

                controlOverlayManager.updateChannelInfo(
                    channel = channel,
                    currentProgram = repository.getCurrentProgram(channel.id)
                )

                updateLiveIndicator()
            } catch (e: Exception) {
                e.printStackTrace()
                binding.loadingIndicator.visibility = View.GONE
            }
        }
    }

    /**
     * Rewind using backend archive API by N seconds from live/current server time.
     */
    private fun rewindArchiveSeconds(secondsBack: Int) {
        val channel = channels.getOrNull(currentChannelIndex) ?: return

        binding.loadingIndicator.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val streamUrl = repository.getArchiveUrlByOffsetSeconds(channel.id, secondsBack)
                if (streamUrl != null) {
                    isLiveMode = false
                    player?.apply {
                        setMediaItem(MediaItem.fromUri(streamUrl))
                        prepare()
                        play()
                    }
                    updateLiveIndicator()
                } else {
                    Toast.makeText(this@PlayerActivity, "Archive unavailable", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                binding.loadingIndicator.visibility = View.GONE
            }
        }
    }

    /**
     * Triggered by EPG click (Catch-up)
     */
    private fun playArchiveAt(channelId: Int, timestampMs: Long) {
        isLiveMode = false
        binding.loadingIndicator.visibility = View.VISIBLE

        // Find channel index just in case we jumped channels
        val index = channels.indexOfFirst { it.id == channelId }
        if (index != -1) currentChannelIndex = index
        val channel = channels[currentChannelIndex]

        lifecycleScope.launch {
            try {
                val streamUrl = repository.getArchiveUrl(channelId, timestampMs)

                if (streamUrl != null) {
                    player?.apply {
                        setMediaItem(MediaItem.fromUri(streamUrl))
                        prepare()
                        play()
                    }

                    // Update UI info
                    controlOverlayManager.updateChannelInfo(
                        channel = channel,
                        currentProgram = channel.programs.find {
                            timestampMs >= it.startTime && timestampMs < it.endTime
                        }
                    )

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
        if (isLiveMode) return // Already live
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

    /**
     * Seeks within the currently playing media item (works for both Live and Archive stream if manifest allows)
     */
    private fun seekBySeconds(deltaSeconds: Int) {
        val p = player ?: return
        val deltaMs = deltaSeconds * 1000L
        val current = p.currentPosition
        val duration = p.duration

        val target = current + deltaMs
        // Coerce target
        val clamped = if (duration != android.util.Log.ERROR.toLong() && duration > 0) {
            target.coerceIn(0L, duration)
        } else {
            target.coerceAtLeast(0L)
        }

        p.seekTo(clamped)
    }

    private fun updateLiveIndicator() {
        val liveButton = binding.root.findViewById<View>(R.id.btnLive)
        // Dim the live indicator if we are watching archive
        liveButton?.alpha = if (isLiveMode) 1.0f else 0.4f
        // Optional: Change text to "REC" or "CATCHUP" if you wanted
    }

    private fun changeChannel(direction: Int) {
        if (channels.isEmpty()) return
        val newIndex = (currentChannelIndex + direction).let { index ->
            when {
                index < 0 -> channels.size - 1
                index >= channels.size -> 0
                else -> index
            }
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
        controlOverlayManager.updateChannelInfo(
            channels[currentChannelIndex],
            repository.getCurrentProgram(channels[currentChannelIndex].id)
        )
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when {
            isEpgVisible -> handleEpgKeyPress(keyCode)
            isControlsVisible -> handleControlsKeyPress(keyCode)
            else -> handlePlayerKeyPress(keyCode)
        } || super.onKeyDown(keyCode, event)
    }

    private fun handlePlayerKeyPress(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                changeChannel(1)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                changeChannel(-1)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                showControls()
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                showEpg()
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                finish()
                true
            }
            else -> false
        }
    }

    private fun handleControlsKeyPress(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                hideControls()
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                currentFocus?.performClick()
                true
            }
            else -> {
                // Reset timer on interaction
                hideControlsHandler.removeCallbacks(hideControlsRunnable)
                hideControlsHandler.postDelayed(hideControlsRunnable, 5000)
                false
            }
        }
    }

    private fun handleEpgKeyPress(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                hideEpg()
                true
            }
            else -> {
                epgOverlayManager.handleKeyEvent(keyCode)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        player?.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
    }
}