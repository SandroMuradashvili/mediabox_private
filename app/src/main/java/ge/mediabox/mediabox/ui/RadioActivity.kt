package ge.mediabox.mediabox.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import ge.mediabox.mediabox.R
import ge.mediabox.mediabox.data.model.RadioStation
import ge.mediabox.mediabox.data.repository.RadioRepository
import ge.mediabox.mediabox.databinding.ActivityRadioBinding
import kotlinx.coroutines.launch
import android.graphics.drawable.Animatable

class RadioActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRadioBinding
    private var player: ExoPlayer? = null
    private var stations: List<RadioStation> = emptyList()
    private var currentIndex = -1
    private var highlightedStationIndex = 0
    private lateinit var stationAdapter: StationAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRadioBinding.inflate(layoutInflater)
        setContentView(binding.root)

        LogoManager.loadLogo(binding.ivRadioBrandingLogo)

        initPlayer()
        setupStationList()
        loadStations()
    }

    private fun toggleBeatsAnimation(show: Boolean) {
        binding.ivRadioBeats.visibility = if (show) View.VISIBLE else View.GONE
        val drawable = binding.ivRadioBeats.drawable
        if (drawable is Animatable) {
            if (show) {
                drawable.start()
            } else {
                drawable.stop()
            }
        }
    }

    private fun initPlayer() {
        player = ExoPlayer.Builder(this).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    binding.radioLoadingBar.visibility =
                        if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE

                    if (state == Player.STATE_READY && playWhenReady) {
                        // Start the animation when music actually starts playing
                        toggleBeatsAnimation(true)
                    } else if (state == Player.STATE_ENDED || state == Player.STATE_IDLE) {
                        toggleBeatsAnimation(false)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    // This ensures the animation stops if the user pauses
                    toggleBeatsAnimation(isPlaying)
                }

                override fun onPlayerError(error: PlaybackException) {
                    binding.radioLoadingBar.visibility = View.GONE
                    toggleBeatsAnimation(false) // Stop animation on error
                    Toast.makeText(this@RadioActivity, "Stream error", Toast.LENGTH_SHORT).show()
                }
            })
        }
    }

    private fun playStation(index: Int) {
        val station = stations.getOrNull(index) ?: return

        // Check Access
        if (!station.hasAccess) {
            val isKa = LangPrefs.isKa(this)
            Toast.makeText(this, if(isKa) "წვდომა შეზღუდულია" else "Access Denied", Toast.LENGTH_SHORT).show()
            return
        }

        currentIndex = index
        val token = getSharedPreferences("AuthPrefs", MODE_PRIVATE).getString("auth_token", null)

        binding.layoutNothingPlaying.visibility = View.GONE
        binding.layoutNowPlaying.visibility = View.VISIBLE
        binding.tvStationName.text = station.name
        binding.tvStationGenre.text = station.genre
        toggleBeatsAnimation(false)
        binding.ivRadioBeats.visibility = View.GONE
        binding.radioLoadingBar.visibility = View.VISIBLE

        if (!station.logoUrl.isNullOrEmpty()) {
            Glide.with(this).load(station.logoUrl).placeholder(R.drawable.ic_radio).into(binding.ivStationLogo)
        }

        lifecycleScope.launch {
            val url = RadioRepository.getStreamUrl(station.id, token)
            if (url != null) {
                player?.stop()
                player?.setMediaItem(MediaItem.fromUri(url))
                player?.prepare()
                player?.play()
                stationAdapter.setNowPlaying(index)
            } else {
                binding.radioLoadingBar.visibility = View.GONE
                Toast.makeText(this@RadioActivity, "Stream unavailable", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupStationList() {
        stationAdapter = StationAdapter(emptyList()) { index ->
            playStation(index)
        }
        binding.rvRadioStations.apply {
            layoutManager = LinearLayoutManager(this@RadioActivity)
            adapter = stationAdapter
            setHasFixedSize(true)
        }
    }

    private fun loadStations() {
        val token = getSharedPreferences("AuthPrefs", MODE_PRIVATE).getString("auth_token", null)
        lifecycleScope.launch {
            stations = RadioRepository.getStations(token)
            stationAdapter.updateStations(stations)
            if (stations.isNotEmpty()) {
                highlightedStationIndex = 0
                stationAdapter.setHighlight(0)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (highlightedStationIndex > 0) {
                    highlightedStationIndex--
                    stationAdapter.setHighlight(highlightedStationIndex)
                    binding.rvRadioStations.smoothScrollToPosition(highlightedStationIndex)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (highlightedStationIndex < stations.size - 1) {
                    highlightedStationIndex++
                    stationAdapter.setHighlight(highlightedStationIndex)
                    binding.rvRadioStations.smoothScrollToPosition(highlightedStationIndex)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                playStation(highlightedStationIndex)
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                finish()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    // ── STATION ADAPTER INNER CLASS ──
    inner class StationAdapter(
        private var stations: List<RadioStation>,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<StationAdapter.VH>() {

        private var highlightedPos = -1
        private var nowPlayingPos  = -1

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val logo:    ImageView = itemView.findViewById(R.id.ivRadioLogo)
            val name:    TextView  = itemView.findViewById(R.id.tvRadioName)
            val genre:   TextView  = itemView.findViewById(R.id.tvRadioGenre)
            val outline: View      = itemView.findViewById(R.id.radioSelectionOutline)
            val nowDot:  TextView  = itemView.findViewById(R.id.tvNowPlayingDot)

            fun bind(station: RadioStation, isHighlighted: Boolean, isNowPlaying: Boolean) {
                name.text  = station.name
                genre.text = station.genre

                if (!station.logoUrl.isNullOrEmpty()) {
                    Glide.with(itemView.context).load(station.logoUrl).placeholder(R.drawable.ic_radio).into(logo)
                } else {
                    logo.setImageResource(R.drawable.ic_radio)
                }

                outline.visibility = if (isHighlighted) View.VISIBLE else View.GONE
                nowDot.visibility  = if (isNowPlaying) View.VISIBLE else View.GONE

                // Handle Access
                if (!station.hasAccess) {
                    itemView.alpha = 0.35f
                    name.setTextColor(0xFF94A3B8.toInt())
                } else {
                    itemView.alpha = if (isHighlighted || isNowPlaying) 1f else 0.85f
                    name.setTextColor(if (isHighlighted) 0xFFF1F5F9.toInt() else 0xDDF1F5F9.toInt())
                }
            }
        }

        fun setHighlight(pos: Int) {
            val old = highlightedPos
            highlightedPos = pos
            if (old in 0 until itemCount) notifyItemChanged(old)
            if (pos in 0 until itemCount) notifyItemChanged(pos)
        }

        fun setNowPlaying(pos: Int) {
            val old = nowPlayingPos
            nowPlayingPos  = pos
            highlightedPos = pos // Automatically highlight what we just played
            if (old in 0 until itemCount) notifyItemChanged(old)
            if (pos in 0 until itemCount) notifyItemChanged(pos)
        }

        fun updateStations(newStations: List<RadioStation>) {
            stations = newStations
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_radio_station, parent, false)
        )

        override fun getItemCount() = stations.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(stations[position], position == highlightedPos, position == nowPlayingPos)
        }
    }
}