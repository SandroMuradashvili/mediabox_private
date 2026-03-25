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

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TrackSelectionOverlayManager(
    private val activity: Activity,
    private val overlayView: View,
    private val playerProvider: () -> ExoPlayer?,
    private val onAspectRatioToggle: () -> Unit,
    private val getAspectRatioLabel: () -> String
) {
    enum class Mode { VIDEO, AUDIO, SETTINGS }

    data class TrackEntry(
        val label: String,
        val isAuto: Boolean = false,
        val trackGroup: TrackGroup? = null,
        val trackIndex: Int = 0,
        val type: EntryType = EntryType.TRACK,
        val iconRes: Int? = null
    )

    enum class EntryType { TRACK, SETTING_QUALITY, SETTING_AUDIO, SETTING_ASPECT }

    private val tvTitle: TextView = overlayView.findViewById(R.id.tvTrackTitle)
    private val rvTracks: RecyclerView = overlayView.findViewById(R.id.rvTracks)

    private var currentMode = Mode.SETTINGS
    private var selectedIndex = 0
    private lateinit var adapter: TrackAdapter

    val isVisible: Boolean get() = overlayView.visibility == View.VISIBLE

    fun init() {
        adapter = TrackAdapter()
        rvTracks.layoutManager = LinearLayoutManager(activity)
        rvTracks.adapter = adapter
        rvTracks.itemAnimator = null
        rvTracks.isFocusable = false
        rvTracks.isFocusableInTouchMode = false
        overlayView.visibility = View.GONE
    }

    // Called by PlayerActivity when tracks change — only refresh if already visible
    fun onTracksChanged(tracks: Tracks) {
        if (isVisible && currentMode == Mode.SETTINGS) refreshSettingsEntries()
    }

    fun show(mode: Mode) {
        currentMode = mode
        selectedIndex = 0
        val isKa = LangPrefs.isKa(activity)

        val entries = when (mode) {
            Mode.VIDEO -> {
                tvTitle.text = if (isKa) "ვიდეოს ხარისხი" else "Video Quality"
                buildVideoEntries().also { selectedIndex = findCurrentVideoSelection(it) }
            }
            Mode.AUDIO -> {
                tvTitle.text = if (isKa) "აუდიო ენა" else "Audio Language"
                buildAudioEntries().also { selectedIndex = findCurrentAudioSelection(it) }
            }
            Mode.SETTINGS -> {
                tvTitle.text = if (isKa) "პარამეტრები" else "Settings"
                buildSettingsEntries()
            }
        }

        // selectedPos: for TRACK modes show where current selection is (checkmark).
        // For SETTINGS: -1 always — nothing is "confirmed selected", items are actions.
        // highlightPos: where the cursor sits. For SETTINGS always start at 0 (no pre-selection).
        // CRITICAL: highlightedPos in adapter starts at -1 so no row is visually activated
        // until the user actually moves — this prevents the phantom "top row triggered" bug.
        val preSelected = if (mode == Mode.SETTINGS) -1 else selectedIndex
        adapter.update(entries, highlightPos = selectedIndex, selectedPos = preSelected)
        overlayView.visibility = View.VISIBLE
        rvTracks.post { (rvTracks.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(selectedIndex, 0) }
    }

    fun dismiss() {
        overlayView.visibility = View.GONE
        // Hard reset all state so nothing bleeds into the next open
        selectedIndex = 0
        adapter.resetState()
    }

    fun handleKeyEvent(keyCode: Int): Boolean {
        val entries = adapter.currentEntries
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (selectedIndex > 0) { selectedIndex--; adapter.setHighlight(selectedIndex); rvTracks.scrollToPosition(selectedIndex) }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (selectedIndex < entries.size - 1) { selectedIndex++; adapter.setHighlight(selectedIndex); rvTracks.scrollToPosition(selectedIndex) }
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { applySelection(selectedIndex); true }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_LEFT -> { dismiss(); true }
            else -> false
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun buildVideoEntries(): List<TrackEntry> {
        val entries = mutableListOf<TrackEntry>()
        val player = playerProvider()
        val fmt = player?.videoFormat
        val autoLabel = "Auto" + if (fmt != null && fmt.height > 0) " (${if (fmt.height >= 700) "HD" else "SD"})" else ""
        entries.add(TrackEntry(autoLabel, isAuto = true))
        player?.currentTracks?.groups?.forEach { group ->
            if (group.type == C.TRACK_TYPE_VIDEO) {
                for (i in 0 until group.length)
                    entries.add(TrackEntry(formatVideoLabel(group.getTrackFormat(i)), trackGroup = group.mediaTrackGroup, trackIndex = i))
            }
        }
        return entries
    }

    private fun buildAudioEntries(): List<TrackEntry> {
        val entries = mutableListOf<TrackEntry>()
        playerProvider()?.currentTracks?.groups?.forEach { group ->
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length)
                    entries.add(TrackEntry(languageDisplayName(group.getTrackFormat(i).language ?: "und"), trackGroup = group.mediaTrackGroup, trackIndex = i))
            }
        }
        return entries
    }

    private fun buildSettingsEntries(): List<TrackEntry> {
        val isKa = LangPrefs.isKa(activity)
        val player = playerProvider()

        val qualityLabel = player?.let {
            val e = buildVideoEntries(); e.getOrNull(findCurrentVideoSelection(e))?.label ?: "Auto"
        } ?: "Auto"

        val audioLabel = player?.let {
            val e = buildAudioEntries(); e.getOrNull(findCurrentAudioSelection(e))?.label ?: "Default"
        } ?: "Default"

        return listOf(
            TrackEntry("${if (isKa) "ხარისხი" else "Quality"}: $qualityLabel", type = EntryType.SETTING_QUALITY, iconRes = R.drawable.ic_quality),
            TrackEntry("${if (isKa) "აუდიო" else "Audio"}: $audioLabel", type = EntryType.SETTING_AUDIO, iconRes = R.drawable.ic_audio_language),
            TrackEntry("${if (isKa) "ზომა" else "Aspect"}: ${getAspectRatioLabel()}", type = EntryType.SETTING_ASPECT, iconRes = R.drawable.ic_cast)
        )
    }

    // FIX: Refresh settings without touching highlight/selected state
    private fun refreshSettingsEntries() {
        adapter.updateLabels(buildSettingsEntries())
    }

    private fun applySelection(pos: Int) {
        val entry = adapter.currentEntries.getOrNull(pos) ?: return
        when (entry.type) {
            EntryType.TRACK -> {
                applyTrackEntry(entry)
                adapter.setSelected(pos)  // only TRACK entries get a checkmark
                dismiss()
            }
            // SETTINGS entries are stateless actions — never mark as selected
            EntryType.SETTING_QUALITY -> { cycleVideoQuality(); refreshSettingsEntries() }
            EntryType.SETTING_AUDIO   -> { cycleAudioTrack();   refreshSettingsEntries() }
            EntryType.SETTING_ASPECT  -> { onAspectRatioToggle(); refreshSettingsEntries() }
        }
    }

    private fun applyTrackEntry(entry: TrackEntry) {
        val player = playerProvider() ?: return
        val params = player.trackSelectionParameters.buildUpon()
        when (currentMode) {
            Mode.VIDEO -> if (entry.isAuto) params.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            else entry.trackGroup?.let { params.addOverride(TrackSelectionOverride(it, entry.trackIndex)) }
            Mode.AUDIO -> entry.trackGroup?.let {
                params.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                params.addOverride(TrackSelectionOverride(it, entry.trackIndex))
            }
            else -> {}
        }
        player.trackSelectionParameters = params.build()
    }

    private fun cycleVideoQuality() {
        val player = playerProvider() ?: return
        val entries = buildVideoEntries().takeIf { it.size > 1 } ?: return
        val next = (findCurrentVideoSelection(entries) + 1) % entries.size
        val params = player.trackSelectionParameters.buildUpon()
        val e = entries[next]
        if (e.isAuto) params.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        else e.trackGroup?.let { params.addOverride(TrackSelectionOverride(it, e.trackIndex)) }
        player.trackSelectionParameters = params.build()
    }

    private fun cycleAudioTrack() {
        val player = playerProvider() ?: return
        val entries = buildAudioEntries().takeIf { it.size > 1 } ?: return
        val next = (findCurrentAudioSelection(entries) + 1) % entries.size
        val e = entries[next]
        val params = player.trackSelectionParameters.buildUpon()
        e.trackGroup?.let { params.clearOverridesOfType(C.TRACK_TYPE_AUDIO); params.addOverride(TrackSelectionOverride(it, e.trackIndex)) }
        player.trackSelectionParameters = params.build()
    }

    private fun findCurrentVideoSelection(entries: List<TrackEntry>): Int {
        val overrides = playerProvider()?.trackSelectionParameters?.overrides ?: return 0
        val hasOverride = overrides.keys.any { tg -> entries.any { e -> e.trackGroup == tg } }
        if (!hasOverride) return 0
        entries.forEachIndexed { i, e ->
            if (e.trackGroup != null && overrides[e.trackGroup]?.trackIndices?.contains(e.trackIndex) == true) return i
        }
        return 0
    }

    private fun findCurrentAudioSelection(entries: List<TrackEntry>): Int {
        val player = playerProvider() ?: return 0
        val overrides = player.trackSelectionParameters.overrides
        entries.forEachIndexed { i, e ->
            if (e.trackGroup != null && overrides[e.trackGroup]?.trackIndices?.contains(e.trackIndex) == true) return i
        }
        // Fall back to whichever track is actively selected
        entries.forEachIndexed { i, e ->
            if (e.trackGroup != null) {
                player.currentTracks.groups.forEach { group ->
                    if (group.type == C.TRACK_TYPE_AUDIO && group.mediaTrackGroup == e.trackGroup && group.isTrackSelected(e.trackIndex)) return i
                }
            }
        }
        return 0
    }

    private fun formatVideoLabel(fmt: Format) = when {
        fmt.height >= 700 -> "HD · ${fmt.height}p"
        fmt.height > 0    -> "SD · ${fmt.height}p"
        else              -> "Standard"
    }

    private fun languageDisplayName(code: String) = when (code.lowercase().take(2)) {
        "ka" -> "ქართული"; "en" -> "English"; "ru" -> "Русский"; "tr" -> "Türkçe"
        "de" -> "Deutsch";  "fr" -> "Français"; "es" -> "Español"; "ar" -> "العربية"
        "uk" -> "Українська"; "az" -> "Azərbaycanca"; "hy" -> "Հայերեն"
        else -> code.uppercase()
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    inner class TrackAdapter : RecyclerView.Adapter<TrackAdapter.VH>() {
        var currentEntries: List<TrackEntry> = emptyList()
            private set
        private var highlightedPos = 0
        // FIX: -1 means "nothing selected" — no checkmark drawn
        private var selectedPos = -1

        inner class VH(root: View) : RecyclerView.ViewHolder(root) {
            val ivIcon: ImageView = root.findViewById(R.id.ivTrackIcon)
            val tvLabel: TextView = root.findViewById(R.id.tvTrackLabel)
            val tvCheck: TextView = root.findViewById(R.id.tvTrackCheck)

            fun bind(entry: TrackEntry, isHighlighted: Boolean, isSelected: Boolean) {
                tvLabel.text = entry.label
                if (entry.iconRes != null) { ivIcon.visibility = View.VISIBLE; ivIcon.setImageResource(entry.iconRes) }
                else ivIcon.visibility = View.GONE
                itemView.isFocusable = false
                itemView.isFocusableInTouchMode = false
                tvCheck.visibility = if (isSelected && currentMode != Mode.SETTINGS) View.VISIBLE else View.INVISIBLE
                itemView.isActivated = isHighlighted
                val color = if (isHighlighted) 0xFFF1F5F9.toInt() else 0xBBF1F5F9.toInt()
                tvLabel.setTextColor(color)
                ivIcon.imageTintList = android.content.res.ColorStateList.valueOf(color)
                itemView.alpha = if (isHighlighted) 1f else 0.7f
            }
        }

        /** Full reset — call on dismiss to prevent state bleed across sessions */
        fun resetState() {
            highlightedPos = -1
            selectedPos = -1
            // No notifyDataSetChanged needed — panel is hidden
        }

        /** Full reset — call when opening the panel */
        fun update(entries: List<TrackEntry>, highlightPos: Int, selectedPos: Int) {
            currentEntries = entries
            highlightedPos = highlightPos
            this.selectedPos = selectedPos
            notifyDataSetChanged()
        }

        /** Label-only refresh — preserves highlight, does NOT touch selectedPos */
        fun updateLabels(entries: List<TrackEntry>) {
            currentEntries = entries
            notifyDataSetChanged()
        }

        fun setHighlight(pos: Int) {
            val old = highlightedPos; highlightedPos = pos
            notifyItemChanged(old); notifyItemChanged(pos)
        }

        fun setSelected(pos: Int) {
            val old = selectedPos; selectedPos = pos
            notifyItemChanged(old); notifyItemChanged(pos)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_track_entry, parent, false)
        )
        override fun onBindViewHolder(holder: VH, position: Int) =
            holder.bind(currentEntries[position], position == highlightedPos, position == selectedPos)
        override fun getItemCount() = currentEntries.size
    }
}