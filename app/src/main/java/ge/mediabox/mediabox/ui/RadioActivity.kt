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

class RadioActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRadioBinding
    private var player: ExoPlayer? = null

    private var stations: List<RadioStation> = emptyList()
    private var currentIndex = -1
    private var isRadioPlaying = false  // renamed to avoid conflict with ExoPlayer.isPlaying

    private var focusZone = 0
    private var highlightedStationIndex = 0

    private lateinit var stationAdapter: StationAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRadioBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initPlayer()
        setupStationList()
        setupControlButtons()
        loadStations()
    }

    // ── Player ────────────────────────────────────────────────────────────────

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun initPlayer() {
        player = ExoPlayer.Builder(this).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    binding.radioLoadingBar.visibility =
                        if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                    if (state == Player.STATE_READY) {
                        isRadioPlaying = true
                        updatePlayPauseButton()
                        binding.tvLiveBadge.visibility = View.VISIBLE
                    }
                }
                override fun onIsPlayingChanged(playing: Boolean) {
                    isRadioPlaying = playing
                    updatePlayPauseButton()
                }
                override fun onPlayerError(error: PlaybackException) {
                    binding.radioLoadingBar.visibility = View.GONE
                    Toast.makeText(
                        this@RadioActivity,
                        "Stream unavailable for this station",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
        }
    }

    private fun playStation(index: Int) {
        val station = stations.getOrNull(index) ?: return
        currentIndex = index

        binding.layoutNothingPlaying.visibility = View.GONE
        binding.layoutNowPlaying.visibility = View.VISIBLE
        binding.tvStationName.text = station.name
        binding.tvStationGenre.text = station.genre
        binding.tvLiveBadge.visibility = View.GONE
        binding.radioLoadingBar.visibility = View.VISIBLE

        if (!station.logoUrl.isNullOrEmpty()) {
            Glide.with(this)
                .load(station.logoUrl)
                .placeholder(R.drawable.ic_radio)
                .error(R.drawable.ic_radio)
                .into(binding.ivStationLogo)
        } else {
            binding.ivStationLogo.setImageResource(R.drawable.ic_radio)
        }

        val exo = player ?: return
        exo.stop()
        exo.setMediaItem(MediaItem.fromUri(station.streamUrl))
        exo.prepare()
        exo.play()

        stationAdapter.setNowPlaying(index)
    }

    private fun updatePlayPauseButton() {
        binding.btnPlayPauseRadio.setImageResource(
            if (isRadioPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    // ── List ──────────────────────────────────────────────────────────────────

    private fun setupStationList() {
        stationAdapter = StationAdapter(emptyList()) { index ->
            highlightedStationIndex = index
            playStation(index)
            focusZone = 1
            updateControlFocus()
        }
        binding.rvRadioStations.apply {
            layoutManager = LinearLayoutManager(this@RadioActivity)
            adapter = stationAdapter
            itemAnimator = null
            isFocusable = false
            isFocusableInTouchMode = false
        }
    }

    private fun setupControlButtons() {
        binding.btnPlayPauseRadio.setOnClickListener { togglePlayPause() }
        binding.btnPrevStation.setOnClickListener { changeStation(-1) }
        binding.btnNextStation.setOnClickListener { changeStation(1) }
    }

    private fun loadStations() {
        binding.radioLoadingBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            stations = RadioRepository.getStations()
            stationAdapter.updateStations(stations)
            binding.radioLoadingBar.visibility = View.GONE
            if (stations.isNotEmpty()) {
                stationAdapter.setHighlight(0)
                highlightedStationIndex = 0
            }
        }
    }

    // ── Controls ──────────────────────────────────────────────────────────────

    private fun togglePlayPause() {
        val exo = player ?: return
        if (exo.isPlaying) exo.pause() else exo.play()
    }

    private fun changeStation(direction: Int) {
        if (stations.isEmpty()) return
        val next = (currentIndex + direction).coerceIn(0, stations.size - 1)
        if (next != currentIndex) {
            highlightedStationIndex = next
            stationAdapter.setHighlight(next)
            binding.rvRadioStations.smoothScrollToPosition(next)
            playStation(next)
        }
    }

    private fun updateControlFocus() {
        val alpha = if (focusZone == 1) 1f else 0.5f
        binding.btnPlayPauseRadio.alpha = alpha
        binding.btnPrevStation.alpha    = alpha
        binding.btnNextStation.alpha    = alpha
    }

    // ── Keys ──────────────────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (focusZone == 0 && highlightedStationIndex > 0) {
                    highlightedStationIndex--
                    stationAdapter.setHighlight(highlightedStationIndex)
                    binding.rvRadioStations.smoothScrollToPosition(highlightedStationIndex)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (focusZone == 0 && highlightedStationIndex < stations.size - 1) {
                    highlightedStationIndex++
                    stationAdapter.setHighlight(highlightedStationIndex)
                    binding.rvRadioStations.smoothScrollToPosition(highlightedStationIndex)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (focusZone == 0 && currentIndex >= 0) {
                    focusZone = 1
                    updateControlFocus()
                    binding.btnPlayPauseRadio.requestFocus()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (focusZone == 1) {
                    focusZone = 0
                    updateControlFocus()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                when (focusZone) {
                    0 -> playStation(highlightedStationIndex)
                    1 -> togglePlayPause()
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        if (currentIndex >= 0) player?.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

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

            init {
                itemView.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) onClick(pos)
                }
            }

            fun bind(station: RadioStation, isHighlighted: Boolean, isNowPlaying: Boolean) {
                name.text  = station.name
                genre.text = station.genre

                if (!station.logoUrl.isNullOrEmpty()) {
                    Glide.with(itemView.context)
                        .load(station.logoUrl)
                        .placeholder(R.drawable.ic_radio)
                        .into(logo)
                } else {
                    logo.setImageResource(R.drawable.ic_radio)
                }

                outline.visibility = if (isHighlighted) View.VISIBLE else View.GONE
                nowDot.visibility  = if (isNowPlaying) View.VISIBLE else View.GONE
                name.setTextColor(
                    if (isHighlighted) 0xFFF1F5F9.toInt() else 0xDDF1F5F9.toInt()
                )
                itemView.alpha = if (isHighlighted || isNowPlaying) 1f else 0.85f
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
            highlightedPos = pos
            if (old in 0 until itemCount) notifyItemChanged(old)
            if (pos in 0 until itemCount) notifyItemChanged(pos)
        }

        fun updateStations(newStations: List<RadioStation>) {
            stations = newStations
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_radio_station, parent, false)
        )

        override fun getItemCount() = stations.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(
                station       = stations[position],
                isHighlighted = position == highlightedPos,
                isNowPlaying  = position == nowPlayingPos
            )
        }
    }
}