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
 * REWRITTEN EPG OVERLAY MANAGER - CLEAN & MODERN
 *
 * FEATURES:
 * - Clean category selection (purple only when focused)
 * - Clear channel selection with outline border
 * - Smooth scrolling without animation glitches
 * - Programs panel shows/hides with "Press → to load" message
 * - LEFT button jumps from channels to categories (no need to scroll up)
 * - Favorites category support
 * - On-demand program loading
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

    private val categoryButtons = mutableListOf<TextView>()
    private val dateFormat = SimpleDateFormat("EEEE, d MMM yyyy", Locale.getDefault())

    private val scope = CoroutineScope(Dispatchers.Main)

    // Focus: CATEGORIES or CHANNELS or PROGRAMS
    private enum class FocusSection { CATEGORIES, CHANNELS, PROGRAMS }
    private var currentFocusSection = FocusSection.CATEGORIES

    private var selectedCategoryIndex = 0
    private var selectedProgramIndex = 0

    // Program panel visibility
    private val programPanel: View? by lazy { binding.root.findViewById(R.id.programPanel) }
    private val programPlaceholder: View? by lazy { binding.root.findViewById(R.id.programPlaceholder) }

    init { setupEpg() }

    // =========================================================================
    // Setup
    // =========================================================================

    private fun setupEpg() {
        // Setup channel list
        val channelList = binding.root.findViewById<RecyclerView>(R.id.channelList)
        channelAdapter = ChannelAdapter(filteredChannels) { position ->
            selectedChannelIndex = position
            hideProgramPanel()
        }
        channelList?.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = channelAdapter
            isFocusable = false
            isFocusableInTouchMode = false

            // Optimized smooth scroll animator
            itemAnimator = object : androidx.recyclerview.widget.DefaultItemAnimator() {
                override fun animateChange(
                    oldHolder: RecyclerView.ViewHolder?,
                    newHolder: RecyclerView.ViewHolder?,
                    fromLeft: Int, fromTop: Int, toLeft: Int, toTop: Int
                ): Boolean {
                    // Very fast animation for buttery smooth feel
                    changeDuration = 80
                    return super.animateChange(oldHolder, newHolder, fromLeft, fromTop, toLeft, toTop)
                }
            }.apply {
                // Faster overall animations
                addDuration = 80
                moveDuration = 80
                removeDuration = 80
            }

            // Smooth scroll configuration
            setHasFixedSize(true)
            setItemViewCacheSize(20) // Cache more items for smooth scrolling

            // Enable hardware acceleration for smoother animations
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        }

        // Setup program list
        val programList = binding.root.findViewById<RecyclerView>(R.id.programList)
        programAdapter = ProgramAdapter(emptyList()) { program ->
            handleProgramClick(program)
        }
        programList?.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = programAdapter
            isFocusable = false
            isFocusableInTouchMode = false
            setHasFixedSize(true)
            setItemViewCacheSize(20)
        }

        setupCategories()
        hideProgramPanel() // Start with placeholder visible
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
                setOnClickListener {
                    selectedCategoryIndex = index
                    selectCategory(category)
                    highlightCategory()
                }
            }
            container?.addView(btn)
            categoryButtons.add(btn)
        }

        selectedCategoryIndex = 0
        selectCategory(categories.getOrNull(0) ?: "All")
        highlightCategory()
    }

    private fun selectCategory(category: String) {
        currentCategory = category
        filteredChannels = repository.getChannelsByCategory(category)
        channelAdapter.updateChannels(filteredChannels)
        selectedChannelIndex = 0
        hideProgramPanel()
    }

    private fun highlightCategory() {
        categoryButtons.forEachIndexed { index, btn ->
            // Only highlight when categories have focus AND this is selected
            if (currentFocusSection == FocusSection.CATEGORIES && index == selectedCategoryIndex) {
                btn.isSelected = true
                btn.alpha = 1.0f
            } else if (index == selectedCategoryIndex) {
                // Selected but not focused - show it's selected but dimmed
                btn.isSelected = false
                btn.alpha = 0.8f
            } else {
                // Not selected
                btn.isSelected = false
                btn.alpha = 0.6f
            }
        }
    }

    // =========================================================================
    // Program Panel Show/Hide
    // =========================================================================

    private fun hideProgramPanel() {
        programPanel?.visibility = View.GONE
        programPlaceholder?.visibility = View.VISIBLE
    }

    private fun showProgramPanel() {
        programPlaceholder?.visibility = View.GONE
        programPanel?.visibility = View.VISIBLE
    }

    private fun loadProgramsForChannel(channelIndex: Int) {
        if (channelIndex < 0 || channelIndex >= filteredChannels.size) return
        val channel = filteredChannels[channelIndex]

        showProgramPanel()
        binding.root.findViewById<TextView>(R.id.tvSelectedChannelName)?.text =
            "${channel.name} – Loading programs..."

        scope.launch {
            val allPrograms = withContext(Dispatchers.IO) {
                try {
                    ApiService.fetchAllPrograms(channel.apiId).sortedBy { it.startTime }
                } catch (e: Exception) {
                    emptyList()
                }
            }

            binding.root.findViewById<TextView>(R.id.tvSelectedChannelName)?.text =
                "${channel.name} – TV Programs"

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
            selectedProgramIndex = 0
            return
        }

        var dividers = 0
        for (i in 0 until currentProgramIndex) {
            val d = dateFormat.format(Date(programs[i].startTime))
            val p = if (i > 0) dateFormat.format(Date(programs[i - 1].startTime)) else null
            if (d != p) dividers++
        }

        val targetPosition = currentProgramIndex + dividers
        selectedProgramIndex = targetPosition

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
                if (globalIndex >= 0) onChannelSelected(globalIndex)
            }
            program.endTime < currentTime -> {
                onArchiveSelected("ARCHIVE_ID:${channel.id}:TIME:${program.startTime}")
            }
            else -> {
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
        selectedChannelIndex = 0
        hideProgramPanel()
    }

    fun requestFocus() {
        currentFocusSection = FocusSection.CATEGORIES
        highlightCategory()
        channelAdapter.setHighlight(-1)
    }

    // =========================================================================
    // Key Handling
    // =========================================================================

    fun handleKeyEvent(keyCode: Int): Boolean {
        return when (currentFocusSection) {
            FocusSection.CATEGORIES -> handleCategoryKeys(keyCode)
            FocusSection.CHANNELS -> handleChannelKeys(keyCode)
            FocusSection.PROGRAMS -> handleProgramKeys(keyCode)
        }
    }

    private fun handleCategoryKeys(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (selectedCategoryIndex > 0) {
                    selectedCategoryIndex--
                    selectCategory(categoryButtons[selectedCategoryIndex].text.toString())
                    highlightCategory()
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (selectedCategoryIndex < categoryButtons.size - 1) {
                    selectedCategoryIndex++
                    selectCategory(categoryButtons[selectedCategoryIndex].text.toString())
                    highlightCategory()
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (filteredChannels.isNotEmpty()) {
                    currentFocusSection = FocusSection.CHANNELS
                    selectedChannelIndex = 0
                    channelAdapter.setHighlight(selectedChannelIndex)
                    highlightCategory() // Update category visual
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // Already selected
                true
            }

            else -> false
        }
    }

    // Scroll throttling to prevent too-fast updates
    private var lastScrollTime = 0L
    private val scrollThrottleMs = 80L // Milliseconds between scroll actions

    private fun handleChannelKeys(keyCode: Int): Boolean {
        val channelList = binding.root.findViewById<RecyclerView>(R.id.channelList) ?: return false
        val lm = channelList.layoutManager as? LinearLayoutManager ?: return false

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastScrollTime < scrollThrottleMs) {
                    // Too fast - ignore this input but mark as handled
                    return true
                }
                lastScrollTime = currentTime

                if (selectedChannelIndex > 0 && filteredChannels.isNotEmpty()) {
                    selectedChannelIndex--
                    // Bounds check
                    selectedChannelIndex = selectedChannelIndex.coerceIn(0, filteredChannels.size - 1)

                    channelAdapter.setHighlight(selectedChannelIndex)

                    // Smoother scroll with animation
                    channelList.smoothScrollToPosition(selectedChannelIndex)

                    hideProgramPanel()
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastScrollTime < scrollThrottleMs) {
                    // Too fast - ignore this input but mark as handled
                    return true
                }
                lastScrollTime = currentTime

                if (selectedChannelIndex < filteredChannels.size - 1 && filteredChannels.isNotEmpty()) {
                    selectedChannelIndex++
                    // Bounds check
                    selectedChannelIndex = selectedChannelIndex.coerceIn(0, filteredChannels.size - 1)

                    channelAdapter.setHighlight(selectedChannelIndex)

                    // Smoother scroll with animation
                    channelList.smoothScrollToPosition(selectedChannelIndex)

                    hideProgramPanel()
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                // Jump directly to categories
                currentFocusSection = FocusSection.CATEGORIES
                channelAdapter.setHighlight(-1)
                highlightCategory()
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // Bounds check before loading
                if (selectedChannelIndex >= 0 && selectedChannelIndex < filteredChannels.size) {
                    loadProgramsForChannel(selectedChannelIndex)
                    currentFocusSection = FocusSection.PROGRAMS
                    programAdapter.setHighlight(selectedProgramIndex)

                    val programList = binding.root.findViewById<RecyclerView>(R.id.programList)
                    val programLm = programList?.layoutManager as? LinearLayoutManager
                    programList?.post {
                        programLm?.scrollToPositionWithOffset(selectedProgramIndex, 0)
                    }
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val channel = filteredChannels.getOrNull(selectedChannelIndex) ?: return false
                val globalIndex = channels.indexOfFirst { it.id == channel.id }
                if (globalIndex >= 0) onChannelSelected(globalIndex)
                true
            }

            else -> false
        }
    }

    private fun handleProgramKeys(keyCode: Int): Boolean {
        val programList = binding.root.findViewById<RecyclerView>(R.id.programList) ?: return false
        val lm = programList.layoutManager as? LinearLayoutManager ?: return false
        val itemCount = programAdapter.itemCount

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (selectedProgramIndex > 0) {
                    selectedProgramIndex--
                    programAdapter.setHighlight(selectedProgramIndex)
                    lm.scrollToPositionWithOffset(selectedProgramIndex, 0)
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (selectedProgramIndex < itemCount - 1) {
                    selectedProgramIndex++
                    programAdapter.setHighlight(selectedProgramIndex)
                    lm.scrollToPositionWithOffset(selectedProgramIndex, 0)
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                currentFocusSection = FocusSection.CHANNELS
                programAdapter.setHighlight(-1)
                channelAdapter.setHighlight(selectedChannelIndex)
                true
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val item = programAdapter.getItem(selectedProgramIndex)
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
    // Channel Adapter - CLEAN SELECTION WITH OUTLINE
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
            val favoriteIcon: ImageView = itemView.findViewById(R.id.ivFavorite)
            val selectionOutline: View = itemView.findViewById(R.id.selectionOutline)

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

                // Show favorite star
                favoriteIcon.visibility = if (channel.isFavorite) View.VISIBLE else View.GONE

                // Very smooth, fast transition for selection
                if (isHighlighted) {
                    selectionOutline.visibility = View.VISIBLE
                    itemView.animate()
                        .alpha(1.0f)
                        .setDuration(80)  // Even faster for smoother feel
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                } else {
                    selectionOutline.visibility = View.GONE
                    itemView.animate()
                        .alpha(0.7f)
                        .setDuration(80)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                }
            }
        }

        fun setHighlight(pos: Int) {
            // Bounds check to prevent crashes
            if (pos < -1 || pos >= channels.size) return

            val old = highlightedPos
            highlightedPos = pos

            // Only notify if positions are valid and within bounds
            if (old >= 0 && old < channels.size && old < itemCount) {
                notifyItemChanged(old)
            }
            if (pos >= 0 && pos < channels.size && pos < itemCount) {
                notifyItemChanged(pos)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_epg_channel, parent, false)
        )

        override fun onBindViewHolder(holder: VH, position: Int) {
            // Bounds check for safety
            if (position < 0 || position >= channels.size) return

            holder.bind(channels[position], position == highlightedPos)
        }

        override fun getItemCount() = channels.size

        fun updateChannels(newChannels: List<Channel>) {
            // Safety check
            if (newChannels.isEmpty()) {
                channels = emptyList()
                highlightedPos = -1
                notifyDataSetChanged()
                return
            }

            channels = newChannels
            highlightedPos = -1

            // Use notifyDataSetChanged for safety during fast operations
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

                container.isSelected = isPlaying
                itemView.alpha = if (isHighlighted) 1.0f else if (isPlaying) 0.9f else 0.7f
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
            highlightedPos = -1
            notifyDataSetChanged()
        }
    }
}