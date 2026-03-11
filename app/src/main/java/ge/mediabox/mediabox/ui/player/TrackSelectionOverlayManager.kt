package ge.mediabox.mediabox.ui.player

import android.app.Activity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ge.mediabox.mediabox.R

/**
 * Manages a unified track-selection overlay for:
 *   • Video quality  (HD / SD / Auto — sourced from HLS video renditions)
 *   • Audio language (any EXO-detected audio track group)
 *
 * Usage
 * ─────
 * 1. Call [show] with [Mode.VIDEO] or [Mode.AUDIO].
 * 2. Wire [handleKeyEvent] into PlayerActivity.onKeyDown.
 * 3. Call [onTracksChanged] whenever ExoPlayer fires onTracksChanged.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TrackSelectionOverlayManager(
    private val activity: Activity,
    private val overlayView: View,
    private val playerProvider: () -> ExoPlayer?
) {

    enum class Mode { VIDEO, AUDIO }

    // ── Views ─────────────────────────────────────────────────────────────────

    private val tvTitle: TextView = overlayView.findViewById(R.id.tvTrackTitle)
    private val rvTracks: RecyclerView = overlayView.findViewById(R.id.rvTracks)

    // ── State ─────────────────────────────────────────────────────────────────

    private var currentMode = Mode.VIDEO
    private var selectedIndex = 0
    private lateinit var adapter: TrackAdapter

    val isVisible: Boolean get() = overlayView.visibility == View.VISIBLE

    // ── Track data ────────────────────────────────────────────────────────────

    /** A unified track entry shown in the list. */
    data class TrackEntry(
        val label: String,
        val isAuto: Boolean = false,
        /** null only for the Auto entry */
        val trackGroup: TrackGroup? = null,
        val trackIndex: Int = 0
    )

    private var videoEntries: List<TrackEntry> = emptyList()
    private var audioEntries: List<TrackEntry> = emptyList()

    // ── Public API ────────────────────────────────────────────────────────────

    fun init() {
        adapter = TrackAdapter(emptyList()) { pos -> applySelection(pos) }
        rvTracks.layoutManager = LinearLayoutManager(activity)
        rvTracks.adapter = adapter
        rvTracks.isFocusable = false
        rvTracks.isFocusableInTouchMode = false
        overlayView.visibility = View.GONE
    }

    /** Called from PlayerActivity.onTracksChanged — rebuilds track lists. */
    fun onTracksChanged(tracks: Tracks) {
        videoEntries = buildVideoEntries(tracks)
        audioEntries = buildAudioEntries(tracks)
    }

    fun show(mode: Mode) {
        currentMode = mode

        // Force a track refresh so the "Auto" label updates to current resolution
        val player = playerProvider()
        if (player != null) {
            onTracksChanged(player.currentTracks)
        }

        val entries = if (mode == Mode.VIDEO) videoEntries else audioEntries
        if (entries.isEmpty()) return

        tvTitle.text = if (mode == Mode.VIDEO) "Video Quality" else "Audio Language"
        selectedIndex = findCurrentSelection(entries, mode)
        adapter.update(entries, selectedIndex)
        overlayView.visibility = View.VISIBLE
        scrollToSelected()
    }

    fun dismiss() {
        overlayView.visibility = View.GONE
    }

    fun handleKeyEvent(keyCode: Int): Boolean {
        val entries = currentEntries()
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (selectedIndex > 0) {
                    selectedIndex--
                    adapter.setHighlight(selectedIndex)
                    rvTracks.smoothScrollToPosition(selectedIndex)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (selectedIndex < entries.size - 1) {
                    selectedIndex++
                    adapter.setHighlight(selectedIndex)
                    rvTracks.smoothScrollToPosition(selectedIndex)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                applySelection(selectedIndex)
                true
            }
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                dismiss()
                true
            }
            else -> false
        }
    }

    /** Returns true if there are audio tracks to switch between. */
    fun hasAudioTracks(): Boolean = audioEntries.size > 1

    /** Returns true if there are video quality levels to switch between. */
    fun hasVideoTracks(): Boolean = videoEntries.size > 1

    // ── Track building ────────────────────────────────────────────────────────

    private fun buildVideoEntries(tracks: Tracks): List<TrackEntry> {
        val entries = mutableListOf<TrackEntry>()

        // 1. Get current live data from the player for the "Auto" row
        val player = playerProvider()
        val fmt = player?.videoFormat

        val currentAutoDetails = if (fmt != null && fmt.height > 0) {
            val tag = if (fmt.height >= 700) "HD" else "SD"
            val mbps = if (fmt.bitrate > 0) " · ${String.format("%.1f", fmt.bitrate / 1_000_000f)} Mbps" else ""
            " ($tag$mbps)"
        } else ""

        // 2. Add the dynamic Auto label (e.g., "Auto (HD · 4.5 Mbps)")
        entries.add(TrackEntry(label = "Auto$currentAutoDetails", isAuto = true))

        // 3. Add manual entries using the original detailed formatting
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                entries.add(TrackEntry(
                    label = formatVideoLabel(format),
                    trackGroup = group.mediaTrackGroup,
                    trackIndex = i
                ))
            }
        }
        return entries
    }

    private fun buildAudioEntries(tracks: Tracks): List<TrackEntry> {
        val entries = mutableListOf<TrackEntry>()

        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                entries.add(
                    TrackEntry(
                        label      = formatAudioLabel(format, entries.size),
                        trackGroup = group.mediaTrackGroup,
                        trackIndex = i
                    )
                )
            }
        }
        return entries
    }

    private fun formatVideoLabel(fmt: Format): String {
        val height = fmt.height
        val bitrate = fmt.bitrate
        val qualityTag = when {
            height >= 700 -> "HD · ${height}p"
            height > 0    -> "SD · ${height}p"
            else          -> "Standard"
        }
        return if (bitrate > 0) {
            val mbps = bitrate / 1_000_000f
            if (mbps >= 1f) "$qualityTag (${String.format("%.1f", mbps)} Mbps)"
            else "$qualityTag (${bitrate / 1000} kbps)"
        } else qualityTag
    }

    private fun formatAudioLabel(fmt: Format, index: Int): String {
        val lang = when {
            !fmt.language.isNullOrBlank() && fmt.language != "und" -> {
                languageDisplayName(fmt.language!!)
            }
            !fmt.label.isNullOrBlank() -> fmt.label!!
            else -> "Track ${index + 1}"
        }
        val channels = when (fmt.channelCount) {
            1    -> "Mono"
            2    -> "Stereo"
            6    -> "5.1"
            8    -> "7.1"
            else -> if (fmt.channelCount > 0) "${fmt.channelCount}ch" else ""
        }
        return if (channels.isNotEmpty()) "$lang  ·  $channels" else lang
    }

    private fun languageDisplayName(code: String): String = when (code.lowercase().take(2)) {
        "ka" -> "ქართული"
        "en" -> "English"
        "ru" -> "Русский"
        "tr" -> "Türkçe"
        "de" -> "Deutsch"
        "fr" -> "Français"
        "es" -> "Español"
        "ar" -> "العربية"
        "uk" -> "Українська"
        "az" -> "Azərbaycanca"
        "hy" -> "Հայերեն"
        else -> code.uppercase()
    }

    // ── Selection logic ───────────────────────────────────────────────────────

    private fun applySelection(pos: Int) {
        val entries = currentEntries()
        val entry   = entries.getOrNull(pos) ?: return
        val player  = playerProvider() ?: return

        adapter.setSelected(pos)
        dismiss()

        val params = player.trackSelectionParameters.buildUpon()

        if (currentMode == Mode.VIDEO) {
            if (entry.isAuto) {
                // Clear any video overrides → let ExoPlayer choose
                params.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            } else {
                entry.trackGroup?.let { tg ->
                    params.addOverride(TrackSelectionOverride(tg, entry.trackIndex))
                }
            }
        } else {
            // Audio — always an explicit group selection
            entry.trackGroup?.let { tg ->
                params.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                params.addOverride(TrackSelectionOverride(tg, entry.trackIndex))
            }
        }

        player.trackSelectionParameters = params.build()
    }

    private fun findCurrentSelection(entries: List<TrackEntry>, mode: Mode): Int {
        val player = playerProvider() ?: return 0
        val overrides = player.trackSelectionParameters.overrides

        if (mode == Mode.VIDEO) {
            // If no video overrides → "Auto" is selected
            val hasVideoOverride = overrides.keys.any { tg ->
                entries.any { e -> e.trackGroup == tg }
            }
            if (!hasVideoOverride) return 0 // Auto

            // Find the matching entry
            entries.forEachIndexed { i, entry ->
                if (entry.trackGroup != null) {
                    val override = overrides[entry.trackGroup]
                    if (override != null && override.trackIndices.contains(entry.trackIndex)) return i
                }
            }
            return 0
        } else {
            // Audio — find active track
            entries.forEachIndexed { i, entry ->
                if (entry.trackGroup != null) {
                    val override = overrides[entry.trackGroup]
                    if (override != null && override.trackIndices.contains(entry.trackIndex)) return i
                }
            }
            // Fall back: find the currently playing audio track
            val currentTracks = player.currentTracks
            entries.forEachIndexed { i, entry ->
                if (entry.trackGroup != null) {
                    for (group in currentTracks.groups) {
                        if (group.type == C.TRACK_TYPE_AUDIO &&
                            group.mediaTrackGroup == entry.trackGroup &&
                            group.isTrackSelected(entry.trackIndex)) return i
                    }
                }
            }
            return 0
        }
    }

    private fun currentEntries() =
        if (currentMode == Mode.VIDEO) videoEntries else audioEntries

    private fun scrollToSelected() {
        rvTracks.post {
            (rvTracks.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(selectedIndex, 0)
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    inner class TrackAdapter(
        private var entries: List<TrackEntry>,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<TrackAdapter.VH>() {

        private var highlightedPos = 0
        private var selectedPos    = -1

        inner class VH(val root: View) : RecyclerView.ViewHolder(root) {
            val tvLabel:  TextView = root.findViewById(R.id.tvTrackLabel)
            val tvCheck:  TextView = root.findViewById(R.id.tvTrackCheck)

            init {
                root.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) onClick(pos)
                }
            }

            fun bind(entry: TrackEntry, isHighlighted: Boolean, isSelected: Boolean) {
                tvLabel.text = entry.label

                // Check mark
                tvCheck.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE

                // Highlight = D-pad cursor; selected = active track
                root.isActivated = isHighlighted
                tvLabel.setTextColor(when {
                    isHighlighted -> 0xFFF1F5F9.toInt()
                    isSelected    -> 0xFFB0B3F5.toInt()
                    else          -> 0xBBF1F5F9.toInt()
                })
                root.alpha = if (isHighlighted || isSelected) 1f else 0.75f
            }
        }

        fun update(newEntries: List<TrackEntry>, preSelected: Int) {
            entries       = newEntries
            highlightedPos = preSelected
            selectedPos    = preSelected
            notifyDataSetChanged()
        }

        fun setHighlight(pos: Int) {
            val old = highlightedPos
            highlightedPos = pos
            if (old in 0 until itemCount) notifyItemChanged(old)
            if (pos in 0 until itemCount) notifyItemChanged(pos)
        }

        fun setSelected(pos: Int) {
            val old = selectedPos
            selectedPos = pos
            if (old in 0 until itemCount) notifyItemChanged(old)
            if (pos in 0 until itemCount) notifyItemChanged(pos)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_track_entry, parent, false)
        )

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(
                entry        = entries[position],
                isHighlighted = position == highlightedPos,
                isSelected    = position == selectedPos
            )
        }

        override fun getItemCount() = entries.size
    }
}