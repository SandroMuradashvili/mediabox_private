package ge.mediabox.mediabox.ui.player

import android.app.Activity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import ge.mediabox.mediabox.R
import ge.mediabox.mediabox.data.api.ApiService
import ge.mediabox.mediabox.data.model.Channel
import ge.mediabox.mediabox.data.model.Program
import ge.mediabox.mediabox.data.repository.ChannelRepository
import ge.mediabox.mediabox.databinding.ActivityPlayerBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * COMPLETELY REWORKED EPG OVERLAY MANAGER
 *
 * Three-section navigation system:
 * 1. CATEGORIES (top horizontal bar) - LEFT/RIGHT to navigate, DOWN to go to channels
 * 2. CHANNELS (left vertical list) - UP/DOWN to navigate, RIGHT to go to programs, LEFT to go back to categories
 * 3. PROGRAMS (right vertical list) - UP/DOWN to scroll, LEFT to go back to channels
 *
 * Focus management is clean and predictable
 * Auto-scrolls to current program on channel selection
 * Proper edge case handling for all boundaries
 */
class EpgOverlayManager(
    private val activity: Activity,
    private val binding: ActivityPlayerBinding,
    private var channels: List<Channel>,
    private val onChannelSelected: (Int) -> Unit,
    private val onArchiveSelected: (String) -> Unit
) {
    private val repository = ChannelRepository
    private var currentCategory = "All"
    private var filteredChannels: List<Channel> = channels
    private var selectedChannelIndex = 0

    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var programAdapter: ProgramAdapter

    private val categoryButtons = mutableMapOf<String, TextView>()
    private val dateFormat = SimpleDateFormat("EEEE, d MMM yyyy", Locale.getDefault())

    private val scope = CoroutineScope(Dispatchers.Main)

    // Focus sections: CATEGORIES, CHANNELS, PROGRAMS
    private enum class FocusSection { CATEGORIES, CHANNELS, PROGRAMS }
    private var currentFocusSection = FocusSection.CATEGORIES

    // Track last focused items in each section for better UX
    private var lastFocusedCategoryIndex = 0
    private var lastFocusedProgramIndex = 0

    init { setupEpg() }

    // =========================================================================
    // Setup
    // =========================================================================

    private fun setupEpg() {
        // Setup channel list
        val channelList = binding.root.findViewById<RecyclerView>(R.id.channelList)
        channelAdapter = ChannelAdapter(filteredChannels) { position ->
            selectedChannelIndex = position
            loadProgramsForChannel(position)
        }
        channelList?.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = channelAdapter
            // Disable default focus handling - we manage it manually
            isFocusable = false
            isFocusableInTouchMode = false
        }

        // Setup program list
        val programList = binding.root.findViewById<RecyclerView>(R.id.programList)
        programAdapter = ProgramAdapter(emptyList()) { program ->
            handleProgramClick(program)
        }
        programList?.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = programAdapter
            // Disable default focus handling
            isFocusable = false
            isFocusableInTouchMode = false
        }

        setupCategories()
        if (filteredChannels.isNotEmpty()) {
            loadProgramsForChannel(0)
        }
    }

    private fun setupCategories() {
        val container = binding.root.findViewById<LinearLayout>(R.id.categoryButtons)
        container?.removeAllViews()
        categoryButtons.clear()

        val categories = repository.getCategories()
        categories.forEachIndexed { index, category ->
            val btn = TextView(activity).apply {
                text = category
                setTextAppearance(R.style.CategoryButton)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(8, 8, 8, 8) }
                setPadding(40, 20, 40, 20)
                setBackgroundResource(R.drawable.category_button_background)
                isFocusable = false
                isFocusableInTouchMode = false
                setOnClickListener { selectCategory(category) }
            }
            container?.addView(btn)
            categoryButtons[category] = btn

            // Remember first category position
            if (index == 0) lastFocusedCategoryIndex = 0
        }

        selectCategory("All")
        highlightCategory(0)
    }

    private fun selectCategory(category: String) {
        currentCategory = category
        filteredChannels = repository.getChannelsByCategory(category)
        channelAdapter.updateChannels(filteredChannels)

        if (filteredChannels.isNotEmpty()) {
            selectedChannelIndex = 0
            loadProgramsForChannel(0)
        } else {
            // No channels in this category
            programAdapter.updatePrograms(emptyList())
            binding.root.findViewById<TextView>(R.id.tvSelectedChannelName)?.text = "No channels in this category"
        }
    }

    private fun highlightCategory(index: Int) {
        categoryButtons.values.forEachIndexed { i, btn ->
            btn.isSelected = (i == index)
        }
        lastFocusedCategoryIndex = index
    }

    // =========================================================================
    // Programs - Auto-scroll to current program
    // =========================================================================

    private fun loadProgramsForChannel(channelIndex: Int) {
        if (channelIndex < 0 || channelIndex >= filteredChannels.size) return
        val channel = filteredChannels[channelIndex]
        binding.root.findViewById<TextView>(R.id.tvSelectedChannelName)?.text =
            "${channel.name} – TV Programs"

        scope.launch {
            val allPrograms = withContext(Dispatchers.IO) {
                try {
                    ApiService.fetchAllPrograms(channel.apiId).sortedBy { it.startTime }
                } catch (e: Exception) {
                    emptyList()
                }
            }

            val itemsWithDividers = createProgramListWithDividers(allPrograms)
            programAdapter.updatePrograms(itemsWithDividers)

            // Auto-scroll to current program
            scrollToCurrentProgram(allPrograms, itemsWithDividers)
        }
    }

    private fun createProgramListWithDividers(programs: List<Program>): List<ProgramItem> {
        val items = mutableListOf<ProgramItem>()
        var lastDate: String? = null

        programs.forEach { program ->
            val date = dateFormat.format(Date(program.startTime))
            if (date != lastDate) {
                items.add(ProgramItem.DateDivider(date))
                lastDate = date
            }
            items.add(ProgramItem.ProgramData(program))
        }

        return items
    }

    private fun scrollToCurrentProgram(programs: List<Program>, itemsWithDividers: List<ProgramItem>) {
        val programList = binding.root.findViewById<RecyclerView>(R.id.programList) ?: return
        val lm = programList.layoutManager as? LinearLayoutManager ?: return

        val currentTime = System.currentTimeMillis()
        val currentProgramIndex = programs.indexOfFirst { it.isCurrentlyPlaying(currentTime) }

        if (currentProgramIndex < 0) {
            // No current program, scroll to top
            lastFocusedProgramIndex = 0
            return
        }

        // Count dividers before current program
        var dividers = 0
        for (i in 0 until currentProgramIndex) {
            val d = dateFormat.format(Date(programs[i].startTime))
            val p = if (i > 0) dateFormat.format(Date(programs[i - 1].startTime)) else null
            if (d != p) dividers++
        }

        val targetPosition = currentProgramIndex + dividers
        lastFocusedProgramIndex = targetPosition

        // Scroll to position
        programList.post {
            lm.scrollToPositionWithOffset(targetPosition, 0)
        }
    }

    private fun handleProgramClick(program: Program) {
        val currentTime = System.currentTimeMillis()
        val channel = filteredChannels.getOrNull(selectedChannelIndex) ?: return
        val globalIndex = channels.indexOfFirst { it.id == channel.id }

        when {
            program.isCurrentlyPlaying(currentTime) -> {
                // Playing now - switch to this channel
                if (globalIndex >= 0) onChannelSelected(globalIndex)
            }
            program.endTime < currentTime -> {
                // Past program - play from archive
                onArchiveSelected("ARCHIVE_ID:${channel.id}:TIME:${program.startTime}")
            }
            else -> {
                // Future program
                Toast.makeText(activity, "This program hasn't started yet", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // =========================================================================
    // Public Interface
    // =========================================================================

    fun refreshData(newChannels: List<Channel>) {
        channels = newChannels
        setupCategories()
        filteredChannels = repository.getChannelsByCategory(currentCategory)
        channelAdapter.updateChannels(filteredChannels)

        if (selectedChannelIndex < filteredChannels.size) {
            loadProgramsForChannel(selectedChannelIndex)
        } else if (filteredChannels.isNotEmpty()) {
            selectedChannelIndex = 0
            loadProgramsForChannel(0)
        }
    }

    fun requestFocus() {
        currentFocusSection = FocusSection.CATEGORIES
        highlightCategory(lastFocusedCategoryIndex)
    }

    // =========================================================================
    // Key Handling - Clean 3-Section Navigation
    // =========================================================================

    fun handleKeyEvent(keyCode: Int): Boolean {
        return when (currentFocusSection) {
            FocusSection.CATEGORIES -> handleCategoryKeys(keyCode)
            FocusSection.CHANNELS -> handleChannelKeys(keyCode)
            FocusSection.PROGRAMS -> handleProgramKeys(keyCode)
        }
    }

    // -------------------------------------------------------------------------
    // SECTION 1: Categories (Top Bar)
    // -------------------------------------------------------------------------
    private fun handleCategoryKeys(keyCode: Int): Boolean {
        val categories = categoryButtons.keys.toList()
        if (categories.isEmpty()) return false

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val newIndex = (lastFocusedCategoryIndex - 1).coerceAtLeast(0)
                if (newIndex != lastFocusedCategoryIndex) {
                    highlightCategory(newIndex)
                    selectCategory(categories[newIndex])
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val newIndex = (lastFocusedCategoryIndex + 1).coerceAtMost(categories.size - 1)
                if (newIndex != lastFocusedCategoryIndex) {
                    highlightCategory(newIndex)
                    selectCategory(categories[newIndex])
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (filteredChannels.isNotEmpty()) {
                    currentFocusSection = FocusSection.CHANNELS
                    channelAdapter.setHighlight(selectedChannelIndex)
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // Already selected via highlight
                true
            }

            else -> false
        }
    }

    // -------------------------------------------------------------------------
    // SECTION 2: Channels (Left Panel)
    // -------------------------------------------------------------------------
    private fun handleChannelKeys(keyCode: Int): Boolean {
        val channelList = binding.root.findViewById<RecyclerView>(R.id.channelList) ?: return false
        val lm = channelList.layoutManager as? LinearLayoutManager ?: return false

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (selectedChannelIndex > 0) {
                    selectedChannelIndex--
                    channelAdapter.setHighlight(selectedChannelIndex)
                    lm.scrollToPositionWithOffset(selectedChannelIndex, 0)
                    loadProgramsForChannel(selectedChannelIndex)
                } else {
                    // At top - go back to categories
                    currentFocusSection = FocusSection.CATEGORIES
                    channelAdapter.setHighlight(-1)
                    highlightCategory(lastFocusedCategoryIndex)
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (selectedChannelIndex < filteredChannels.size - 1) {
                    selectedChannelIndex++
                    channelAdapter.setHighlight(selectedChannelIndex)
                    lm.scrollToPositionWithOffset(selectedChannelIndex, 0)
                    loadProgramsForChannel(selectedChannelIndex)
                }
                // Stay at bottom if already there
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // Move to programs
                currentFocusSection = FocusSection.PROGRAMS
                channelAdapter.setHighlight(selectedChannelIndex)  // Keep channel highlighted
                programAdapter.setHighlight(lastFocusedProgramIndex)

                // Ensure program is visible
                val programList = binding.root.findViewById<RecyclerView>(R.id.programList)
                val programLm = programList?.layoutManager as? LinearLayoutManager
                programLm?.scrollToPositionWithOffset(lastFocusedProgramIndex, 0)
                true
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // Play this channel live
                val channel = filteredChannels.getOrNull(selectedChannelIndex) ?: return false
                val globalIndex = channels.indexOfFirst { it.id == channel.id }
                if (globalIndex >= 0) onChannelSelected(globalIndex)
                true
            }

            else -> false
        }
    }

    // -------------------------------------------------------------------------
    // SECTION 3: Programs (Right Panel)
    // -------------------------------------------------------------------------
    private fun handleProgramKeys(keyCode: Int): Boolean {
        val programList = binding.root.findViewById<RecyclerView>(R.id.programList) ?: return false
        val lm = programList.layoutManager as? LinearLayoutManager ?: return false
        val itemCount = programAdapter.itemCount

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (lastFocusedProgramIndex > 0) {
                    lastFocusedProgramIndex--
                    programAdapter.setHighlight(lastFocusedProgramIndex)
                    lm.scrollToPositionWithOffset(lastFocusedProgramIndex, 0)
                }
                // Stay at top if already there
                true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (lastFocusedProgramIndex < itemCount - 1) {
                    lastFocusedProgramIndex++
                    programAdapter.setHighlight(lastFocusedProgramIndex)
                    lm.scrollToPositionWithOffset(lastFocusedProgramIndex, 0)
                }
                // Stay at bottom if already there
                true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                // Go back to channels
                currentFocusSection = FocusSection.CHANNELS
                programAdapter.setHighlight(-1)
                channelAdapter.setHighlight(selectedChannelIndex)
                true
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // Play this program
                val item = programAdapter.getItem(lastFocusedProgramIndex)
                if (item is ProgramItem.ProgramData) {
                    handleProgramClick(item.program)
                }
                true
            }

            else -> false
        }
    }

    // =========================================================================
    // Sealed Items
    // =========================================================================

    sealed class ProgramItem {
        data class ProgramData(val program: Program) : ProgramItem()
        data class DateDivider(val date: String) : ProgramItem()
    }

    // =========================================================================
    // Channel Adapter
    // =========================================================================

    inner class ChannelAdapter(
        private var channels: List<Channel>,
        private val onChannelClick: (Int) -> Unit
    ) : RecyclerView.Adapter<ChannelAdapter.VH>() {

        private var highlightedPos = -1

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val logo: ImageView = itemView.findViewById(R.id.channelLogo)
            val number: TextView = itemView.findViewById(R.id.tvChannelNumber)
            val name: TextView = itemView.findViewById(R.id.tvChannelName)

            init {
                itemView.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        highlightedPos = pos
                        notifyDataSetChanged()
                        onChannelClick(pos)
                    }
                }
            }

            fun bind(channel: Channel, isHighlighted: Boolean) {
                number.text = channel.number.toString()
                name.text = channel.name

                if (!channel.logoUrl.isNullOrEmpty()) {
                    Glide.with(activity).load(channel.logoUrl)
                        .placeholder(R.color.surface_light)
                        .error(R.color.surface_light)
                        .into(logo)
                }

                // Visual feedback for highlighted item
                itemView.alpha = if (isHighlighted) 1.0f else 0.7f
                itemView.scaleX = if (isHighlighted) 1.05f else 1.0f
                itemView.scaleY = if (isHighlighted) 1.05f else 1.0f
            }
        }

        fun setHighlight(pos: Int) {
            val old = highlightedPos
            highlightedPos = pos
            if (old >= 0) notifyItemChanged(old)
            if (pos >= 0) notifyItemChanged(pos)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_epg_channel, parent, false)
        )

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(channels[position], position == highlightedPos)
        }

        override fun getItemCount() = channels.size

        fun updateChannels(newChannels: List<Channel>) {
            channels = newChannels
            highlightedPos = if (newChannels.isNotEmpty()) 0 else -1
            notifyDataSetChanged()
        }
    }

    // =========================================================================
    // Program Adapter
    // =========================================================================

    class ProgramAdapter(
        private var items: List<ProgramItem>,
        private val onProgramClick: (Program) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            const val TYPE_PROGRAM = 0
            const val TYPE_DIVIDER = 1
        }

        private var highlightedPos = -1

        inner class ProgramVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val time: TextView = itemView.findViewById(R.id.tvProgramTime)
            val title: TextView = itemView.findViewById(R.id.tvProgramTitle)
            val description: TextView = itemView.findViewById(R.id.tvProgramDescription)
            val container: View = itemView.findViewById(R.id.programContainer)

            init {
                itemView.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        (items[pos] as? ProgramItem.ProgramData)?.let {
                            onProgramClick(it.program)
                        }
                    }
                }
            }

            fun bind(program: Program, isHighlighted: Boolean) {
                val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                time.text = "${fmt.format(Date(program.startTime))} – ${fmt.format(Date(program.endTime))}"
                title.text = program.title
                description.text = program.description

                val isPlaying = program.isCurrentlyPlaying()

                // Visual feedback
                container.isSelected = isPlaying
                itemView.alpha = if (isHighlighted) 1.0f else if (isPlaying) 0.9f else 0.7f
                itemView.scaleX = if (isHighlighted) 1.03f else 1.0f
                itemView.scaleY = if (isHighlighted) 1.03f else 1.0f
            }
        }

        inner class DividerVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val dateLabel: TextView = itemView.findViewById(R.id.tvDateDivider)
        }

        fun setHighlight(pos: Int) {
            val old = highlightedPos
            highlightedPos = pos
            if (old >= 0 && old < itemCount) notifyItemChanged(old)
            if (pos >= 0 && pos < itemCount) notifyItemChanged(pos)
        }

        fun getItem(pos: Int): ProgramItem? = items.getOrNull(pos)

        override fun getItemViewType(pos: Int) = when (items[pos]) {
            is ProgramItem.ProgramData -> TYPE_PROGRAM
            is ProgramItem.DateDivider -> TYPE_DIVIDER
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_PROGRAM)
                ProgramVH(inflater.inflate(R.layout.item_epg_program, parent, false))
            else
                DividerVH(inflater.inflate(R.layout.item_epg_date_divider, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            when {
                holder is ProgramVH && item is ProgramItem.ProgramData -> {
                    holder.bind(item.program, position == highlightedPos)
                }
                holder is DividerVH && item is ProgramItem.DateDivider -> {
                    holder.dateLabel.text = item.date
                }
            }
        }

        override fun getItemCount() = items.size

        fun updatePrograms(newItems: List<ProgramItem>) {
            items = newItems
            highlightedPos = -1  // Reset highlight on new data
            notifyDataSetChanged()
        }
    }
}