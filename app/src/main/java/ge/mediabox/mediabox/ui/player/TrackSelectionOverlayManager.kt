package ge.mediabox.mediabox.ui.player

import android.app.Activity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
import ge.mediabox.mediabox.ui.LangPrefs

/**
 * Manages a unified track-selection overlay for:
 *   • Video quality  (HD / SD / Auto)
 *   • Audio language
 *   • Unified Settings (Quality, Audio, Aspect Ratio)
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TrackSelectionOverlayManager(
    private val activity: Activity,
    private val overlayView: View,
    private val playerProvider: () -> ExoPlayer?,
    private val onAspectRatioToggle: () -> Unit,
    private val getAspectRatioLabel: () -> String
) {

    enum class Mode { VIDEO, AUDIO, SETTINGS }

    private val tvTitle: TextView = overlayView.findViewById(R.id.tvTrackTitle)
    private val rvTracks: RecyclerView = overlayView.findViewById(R.id.rvTracks)

    private var currentMode = Mode.SETTINGS
    private var selectedIndex = 0
    private lateinit var adapter: TrackAdapter

    val isVisible: Boolean get() = overlayView.visibility == View.VISIBLE

    data class TrackEntry(
        val label: String,
        val isAuto: Boolean = false,
        val trackGroup: TrackGroup? = null,
        val trackIndex: Int = 0,
        val type: EntryType = EntryType.TRACK,
        val iconRes: Int? = null
    )

    enum class EntryType { TRACK, SETTING_QUALITY, SETTING_AUDIO, SETTING_ASPECT }

    private var videoEntries: List<TrackEntry> = emptyList()
    private var audioEntries: List<TrackEntry> = emptyList()
    private var settingsEntries: List<TrackEntry> = emptyList()

    fun init() {
        adapter = TrackAdapter(emptyList()) { pos -> applySelection(pos) }
        rvTracks.layoutManager = LinearLayoutManager(activity)
        rvTracks.adapter = adapter
        // DISABLE FADE ANIMATION: Set item animator to null for instant selection changes
        rvTracks.itemAnimator = null
        overlayView.visibility = View.GONE
    }

    fun onTracksChanged(tracks: Tracks) {
        videoEntries = buildVideoEntries(tracks)
        audioEntries = buildAudioEntries(tracks)
        if (currentMode == Mode.SETTINGS) refreshSettings()
    }

    fun show(mode: Mode) {
        currentMode = mode
        val player = playerProvider()
        if (player != null) {
            onTracksChanged(player.currentTracks)
        }

        val isKa = LangPrefs.isKa(activity)
        when (mode) {
            Mode.VIDEO -> {
                tvTitle.text = if (isKa) "ვიდეოს ხარისხი" else "Video Quality"
                selectedIndex = findCurrentSelection(videoEntries, Mode.VIDEO)
            }
            Mode.AUDIO -> {
                tvTitle.text = if (isKa) "აუდიო ენა" else "Audio Language"
                selectedIndex = findCurrentSelection(audioEntries, Mode.AUDIO)
            }
            Mode.SETTINGS -> {
                tvTitle.text = if (isKa) "პარამეტრები" else "Settings"
                refreshSettings()
                selectedIndex = 0
            }
        }

        adapter.update(currentEntries(), selectedIndex)
        overlayView.visibility = View.VISIBLE
        scrollToSelected()
    }

    private fun refreshSettings() {
        val player = playerProvider()
        val isKa = LangPrefs.isKa(activity)
        
        val currentQualityLabel = if (player != null) {
            val entries = buildVideoEntries(player.currentTracks)
            val sel = findCurrentSelection(entries, Mode.VIDEO)
            entries.getOrNull(sel)?.label ?: "Auto"
        } else "Auto"
        
        val currentAudioLabel = if (player != null) {
            val entries = buildAudioEntries(player.currentTracks)
            val sel = findCurrentSelection(entries, Mode.AUDIO)
            entries.getOrNull(sel)?.label ?: "Default"
        } else "Default"

        settingsEntries = listOf(
            TrackEntry("${if (isKa) "ხარისხი" else "Quality"}: $currentQualityLabel", type = EntryType.SETTING_QUALITY, iconRes = R.drawable.ic_quality),
            TrackEntry("${if (isKa) "აუდიო" else "Audio"}: $currentAudioLabel", type = EntryType.SETTING_AUDIO, iconRes = R.drawable.ic_audio_language),
            TrackEntry("${if (isKa) "ზომა" else "Aspect"}: ${getAspectRatioLabel()}", type = EntryType.SETTING_ASPECT, iconRes = R.drawable.ic_cast)
        )
        
        if (currentMode == Mode.SETTINGS) {
            adapter.updateLabelsOnly(settingsEntries)
        }
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
                    rvTracks.scrollToPosition(selectedIndex)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (selectedIndex < entries.size - 1) {
                    selectedIndex++
                    adapter.setHighlight(selectedIndex)
                    rvTracks.scrollToPosition(selectedIndex)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                applySelection(selectedIndex)
                true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_LEFT -> {
                dismiss()
                true
            }
            else -> false
        }
    }

    private fun buildVideoEntries(tracks: Tracks): List<TrackEntry> {
        val entries = mutableListOf<TrackEntry>()
        val player = playerProvider()
        val fmt = player?.videoFormat
        val currentAutoDetails = if (fmt != null && fmt.height > 0) {
            val tag = if (fmt.height >= 700) "HD" else "SD"
            " ($tag)"
        } else ""
        entries.add(TrackEntry(label = "Auto$currentAutoDetails", isAuto = true))
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                entries.add(TrackEntry(label = formatVideoLabel(format), trackGroup = group.mediaTrackGroup, trackIndex = i))
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
                entries.add(TrackEntry(label = languageDisplayName(format.language ?: "und"), trackGroup = group.mediaTrackGroup, trackIndex = i))
            }
        }
        return entries
    }

    private fun formatVideoLabel(fmt: Format): String {
        val height = fmt.height
        return when {
            height >= 700 -> "HD · ${height}p"
            height > 0    -> "SD · ${height}p"
            else          -> "Standard"
        }
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
        "hy" -> "Հაერენ"
        else -> code.uppercase()
    }

    private fun applySelection(pos: Int) {
        val entries = currentEntries()
        val entry   = entries.getOrNull(pos) ?: return
        val player  = playerProvider() ?: return

        when (entry.type) {
            EntryType.TRACK -> {
                applyTrackSelection(entry)
                adapter.setSelected(pos)
                dismiss()
            }
            EntryType.SETTING_QUALITY -> cycleQuality()
            EntryType.SETTING_AUDIO -> cycleAudio()
            EntryType.SETTING_ASPECT -> onAspectRatioToggle()
        }
        if (entry.type != EntryType.TRACK) refreshSettings()
    }

    private fun applyTrackSelection(entry: TrackEntry) {
        val player = playerProvider() ?: return
        val params = player.trackSelectionParameters.buildUpon()
        if (currentMode == Mode.VIDEO) {
            if (entry.isAuto) params.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            else entry.trackGroup?.let { tg -> params.addOverride(TrackSelectionOverride(tg, entry.trackIndex)) }
        } else if (currentMode == Mode.AUDIO) {
            entry.trackGroup?.let { tg ->
                params.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                params.addOverride(TrackSelectionOverride(tg, entry.trackIndex))
            }
        }
        player.trackSelectionParameters = params.build()
    }

    private fun cycleQuality() {
        val player = playerProvider() ?: return
        val entries = buildVideoEntries(player.currentTracks)
        if (entries.size <= 1) return
        val current = findCurrentSelection(entries, Mode.VIDEO)
        val next = (current + 1) % entries.size
        val params = player.trackSelectionParameters.buildUpon()
        val entry = entries[next]
        if (entry.isAuto) params.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        else entry.trackGroup?.let { tg -> params.addOverride(TrackSelectionOverride(tg, entry.trackIndex)) }
        player.trackSelectionParameters = params.build()
    }

    private fun cycleAudio() {
        val player = playerProvider() ?: return
        val entries = buildAudioEntries(player.currentTracks)
        if (entries.size <= 1) return
        val current = findCurrentSelection(entries, Mode.AUDIO)
        val next = (current + 1) % entries.size
        val params = player.trackSelectionParameters.buildUpon()
        val entry = entries[next]
        entry.trackGroup?.let { tg ->
            params.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            params.addOverride(TrackSelectionOverride(tg, entry.trackIndex))
        }
        player.trackSelectionParameters = params.build()
    }

    private fun findCurrentSelection(entries: List<TrackEntry>, mode: Mode): Int {
        val player = playerProvider() ?: return 0
        val overrides = player.trackSelectionParameters.overrides
        if (mode == Mode.VIDEO) {
            val hasVideoOverride = overrides.keys.any { tg -> entries.any { e -> e.trackGroup == tg } }
            if (!hasVideoOverride) return 0
            entries.forEachIndexed { i, e -> if (e.trackGroup != null && overrides[e.trackGroup]?.trackIndices?.contains(e.trackIndex) == true) return i }
            return 0
        } else {
            entries.forEachIndexed { i, e -> if (e.trackGroup != null && overrides[e.trackGroup]?.trackIndices?.contains(e.trackIndex) == true) return i }
            val currentTracks = player.currentTracks
            entries.forEachIndexed { i, e ->
                if (e.trackGroup != null) {
                    for (group in currentTracks.groups) {
                        if (group.type == C.TRACK_TYPE_AUDIO && group.mediaTrackGroup == e.trackGroup && group.isTrackSelected(e.trackIndex)) return i
                    }
                }
            }
            return 0
        }
    }

    private fun currentEntries() = when (currentMode) {
        Mode.VIDEO -> videoEntries
        Mode.AUDIO -> audioEntries
        Mode.SETTINGS -> settingsEntries
    }

    private fun scrollToSelected() {
        rvTracks.post { (rvTracks.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(selectedIndex, 0) }
    }

    inner class TrackAdapter(
        private var entries: List<TrackEntry>,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<TrackAdapter.VH>() {
        private var highlightedPos = 0
        private var selectedPos = -1

        inner class VH(root: View) : RecyclerView.ViewHolder(root) {
            val ivIcon: ImageView = root.findViewById(R.id.ivTrackIcon)
            val tvLabel: TextView = root.findViewById(R.id.tvTrackLabel)
            val tvCheck: TextView = root.findViewById(R.id.tvTrackCheck)
            init { root.setOnClickListener { val pos = adapterPosition; if (pos != RecyclerView.NO_POSITION) onClick(pos) } }

            fun bind(entry: TrackEntry, isHighlighted: Boolean, isSelected: Boolean) {
                tvLabel.text = entry.label
                if (entry.iconRes != null) {
                    ivIcon.visibility = View.VISIBLE
                    ivIcon.setImageResource(entry.iconRes)
                } else {
                    ivIcon.visibility = View.GONE
                }
                tvCheck.visibility = if (isSelected && currentMode != Mode.SETTINGS) View.VISIBLE else View.INVISIBLE
                itemView.isActivated = isHighlighted
                val color = if (isHighlighted) 0xFFF1F5F9.toInt() else 0xBBF1F5F9.toInt()
                tvLabel.setTextColor(color)
                ivIcon.imageTintList = android.content.res.ColorStateList.valueOf(color)
                itemView.alpha = if (isHighlighted) 1f else 0.7f
            }
        }

        fun update(newEntries: List<TrackEntry>, preSelected: Int) {
            entries = newEntries
            highlightedPos = preSelected
            selectedPos = preSelected
            notifyDataSetChanged()
        }

        fun updateLabelsOnly(newEntries: List<TrackEntry>) {
            entries = newEntries
            notifyDataSetChanged()
        }

        fun setHighlight(pos: Int) {
            val old = highlightedPos
            highlightedPos = pos
            notifyItemChanged(old)
            notifyItemChanged(pos)
        }

        fun setSelected(pos: Int) {
            val old = selectedPos
            selectedPos = pos
            notifyItemChanged(old)
            notifyItemChanged(pos)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_track_entry, parent, false))
        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(entries[position], position == highlightedPos, position == selectedPos)
        override fun getItemCount() = entries.size
    }
}