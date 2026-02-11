package ge.mediabox.mediabox.ui.player

import android.os.Bundle
import ge.mediabox.mediabox.R
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
            playChannel(0)
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
            }
        )
    }

    private fun playChannel(index: Int) {
        if (index < 0 || index >= channels.size) return

        currentChannelIndex = index
        val channel = channels[index]

        binding.loadingIndicator.visibility = View.VISIBLE

        // Fetch stream URL from API
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
                    // Handle error - stream URL couldn't be fetched
                    binding.loadingIndicator.visibility = View.GONE
                    // You could show an error message here
                }

                controlOverlayManager.updateChannelInfo(
                    channel = channel,
                    currentProgram = repository.getCurrentProgram(channel.id)
                )
            } catch (e: Exception) {
                e.printStackTrace()
                binding.loadingIndicator.visibility = View.GONE
            }
        }
    }

    private fun changeChannel(direction: Int) {
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
        val channel = channels[currentChannelIndex]
        repository.toggleFavorite(channel.id)
        channel.isFavorite = !channel.isFavorite
        controlOverlayManager.updateFavoriteButton(channel.isFavorite)
    }

    private fun showControls() {
        isControlsVisible = true
        binding.root.findViewById<View>(R.id.controlOverlay)?.visibility = View.VISIBLE
        controlOverlayManager.updateChannelInfo(
            channels[currentChannelIndex],
            repository.getCurrentProgram(channels[currentChannelIndex].id)
        )
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        hideControlsHandler.postDelayed(hideControlsRunnable, 15000)
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
                changeChannel(-1)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                changeChannel(1)
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
            else -> {
                hideControlsHandler.removeCallbacks(hideControlsRunnable)
                hideControlsHandler.postDelayed(hideControlsRunnable, 15000)
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
            else -> false
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