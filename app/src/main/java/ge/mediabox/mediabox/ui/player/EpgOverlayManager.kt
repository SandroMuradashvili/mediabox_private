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
    private val dateFormat    = SimpleDateFormat("EEEE, d MMM yyyy", Locale.getDefault())
    private val dayFormat     = SimpleDateFormat("EEE d MMM", Locale.getDefault())
    // Format for the per-row date column: "23 Feb"
    private val rowDateFormat = SimpleDateFormat("d MMM", Locale.getDefault())

    private val scope = CoroutineScope(Dispatchers.Main)

    private enum class FocusSection { CATEGORIES, CHANNELS, PROGRAMS }
    private var currentFocusSection = FocusSection.CATEGORIES

    private var selectedCategoryIndex = 0
    private var selectedProgramIndex  = 0

    private var currentProgramItems: List<ProgramItem> = emptyList()

    private val programPanel: View?       by lazy { binding.root.findViewById(R.id.programPanel) }
    private val programPlaceholder: View? by lazy { binding.root.findViewById(R.id.programPlaceholder) }
    private val tvHoveredDate: TextView?  by lazy { binding.root.findViewById(R.id.tvHoveredDate) }

    init { setupEpg() }

    // =========================================================================
    // Setup
    // =========================================================================

    private fun setupEpg() {
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
            itemAnimator = object : androidx.recyclerview.widget.DefaultItemAnimator() {
                override fun animateChange(
                    oldHolder: RecyclerView.ViewHolder?,
                    newHolder: RecyclerView.ViewHolder?,
                    fromLeft: Int, fromTop: Int, toLeft: Int, toTop: Int
                ): Boolean {
                    changeDuration = 80
                    return super.animateChange(oldHolder, newHolder, fromLeft, fromTop, toLeft, toTop)
                }
            }.apply { addDuration = 80; moveDuration = 80; removeDuration = 80 }
            setHasFixedSize(true)
            setItemViewCacheSize(20)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        val programList = binding.root.findViewById<RecyclerView>(R.id.programList)
        programAdapter = ProgramAdapter(emptyList()) { program -> handleProgramClick(program) }
        programList?.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = programAdapter
            isFocusable = false
            isFocusableInTouchMode = false
            setHasFixedSize(false)
            setItemViewCacheSize(30)
        }

        setupCategories()
        hideProgramPanel()
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
                ).apply { setMargins(5, 0, 5, 0) }
                setPadding(28, 12, 28, 12)
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
        scrollChannelListTo(0)
        hideProgramPanel()
    }

    private fun highlightCategory() {
        categoryButtons.forEachIndexed { index, btn ->
            when {
                currentFocusSection == FocusSection.CATEGORIES && index == selectedCategoryIndex -> {
                    btn.isSelected = true; btn.alpha = 1.0f
                }
                index == selectedCategoryIndex -> {
                    btn.isSelected = false; btn.alpha = 0.85f
                }
                else -> {
                    btn.isSelected = false; btn.alpha = 0.55f
                }
            }
        }
    }

    // =========================================================================
    // Scroll helpers
    // =========================================================================

    private fun scrollChannelListTo(position: Int) {
        val rv = binding.root.findViewById<RecyclerView>(R.id.channelList) ?: return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        lm.scrollToPositionWithOffset(position, 0)
    }

    /**
     * Smart scroll: only moves the list when the cursor is near the edges.
     * If the target position is already fully visible, do nothing.
     * This prevents the annoying "always jumps to top" behaviour.
     */
    private fun smartScrollProgramList(position: Int) {
        val rv = binding.root.findViewById<RecyclerView>(R.id.programList) ?: return
        val lm = rv.layoutManager as? LinearLayoutManager ?: return

        val first = lm.findFirstCompletelyVisibleItemPosition()
        val last  = lm.findLastCompletelyVisibleItemPosition()

        when {
            // Cursor moved above the visible window → scroll up just enough
            position < first -> lm.scrollToPositionWithOffset(position, 0)
            // Cursor moved below the visible window → scroll down just enough
            position > last  -> {
                // Align so the target is at the bottom of the list
                val itemHeight = rv.getChildAt(0)?.height ?: 42
                val rvHeight   = rv.height
                val offset     = rvHeight - itemHeight - 4
                lm.scrollToPositionWithOffset(position, offset.coerceAtLeast(0))
            }
            // Already visible — don't touch scroll at all
        }
    }

    // =========================================================================
    // Program Panel
    // =========================================================================

    private fun hideProgramPanel() {
        programPanel?.visibility = View.GONE
        programPlaceholder?.visibility = View.VISIBLE
        tvHoveredDate?.visibility = View.GONE
    }

    private fun showProgramPanel() {
        programPlaceholder?.visibility = View.GONE
        programPanel?.visibility = View.VISIBLE
    }

    private fun loadProgramsForChannel(channelIndex: Int) {
        if (channelIndex < 0 || channelIndex >= filteredChannels.size) return
        val channel = filteredChannels[channelIndex]

        // Keep panel hidden until data is ready and scroll is committed —
        // showing it early causes the flash-to-position-0 bug
        programPlaceholder?.visibility = View.VISIBLE
        programPanel?.visibility = View.GONE
        tvHoveredDate?.visibility = View.GONE

        scope.launch {
            val allPrograms = withContext(Dispatchers.IO) {
                try { ApiService.fetchAllPrograms(channel.apiId).sortedBy { it.startTime } }
                catch (e: Exception) { emptyList() }
            }

            val itemsWithDividers = createProgramListWithDividers(allPrograms)
            currentProgramItems = itemsWithDividers

            var targetPos = findCurrentProgramPosition(allPrograms, itemsWithDividers)
            while (targetPos < itemsWithDividers.size &&
                itemsWithDividers.getOrNull(targetPos) is ProgramItem.DateDivider) {
                targetPos++
            }
            selectedProgramIndex = targetPos

            // Load data into adapter while panel is still hidden
            val rv = binding.root.findViewById<RecyclerView>(R.id.programList)
            rv?.visibility = View.INVISIBLE
            programAdapter.updatePrograms(itemsWithDividers)

            // First post: RecyclerView lays out new items
            rv?.post {
                val lm = rv.layoutManager as? LinearLayoutManager
                lm?.scrollToPositionWithOffset(targetPos, 0)

                // Second post: scroll is committed, now safe to reveal everything at once
                rv.post {
                    binding.root.findViewById<TextView>(R.id.tvSelectedChannelName)?.text = channel.name
                    rv.visibility = View.VISIBLE
                    programPanel?.visibility = View.VISIBLE
                    programPlaceholder?.visibility = View.GONE
                    programAdapter.setHighlight(targetPos)
                    updateHoveredDate(targetPos)
                }
            }
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

    private fun findCurrentProgramPosition(
        programs: List<Program>,
        itemsWithDividers: List<ProgramItem>
    ): Int {
        if (programs.isEmpty()) return 0
        val currentTime = System.currentTimeMillis()
        val currentProgramIndex = programs.indexOfFirst { it.isCurrentlyPlaying(currentTime) }
        if (currentProgramIndex < 0) return 0

        var dividers = 0
        for (i in 0 until currentProgramIndex) {
            val d = dateFormat.format(Date(programs[i].startTime))
            val p = if (i > 0) dateFormat.format(Date(programs[i - 1].startTime)) else null
            if (d != p) dividers++
        }
        return currentProgramIndex + dividers
    }

    private fun updateHoveredDate(position: Int) {
        val tv = tvHoveredDate ?: return
        val item = currentProgramItems.getOrNull(position)

        val dateStr = when (item) {
            is ProgramItem.ProgramData -> dayFormat.format(Date(item.program.startTime))
            is ProgramItem.DateDivider -> {
                // Show the date of the first program in this day block
                val nextProgram = currentProgramItems.getOrNull(position + 1)
                if (nextProgram is ProgramItem.ProgramData)
                    dayFormat.format(Date(nextProgram.program.startTime))
                else item.date
            }
            null -> null
        }

        if (dateStr != null) {
            tv.text = dateStr
            tv.visibility = View.VISIBLE
        } else {
            tv.visibility = View.GONE
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
            else -> Toast.makeText(activity, "This program hasn't started yet", Toast.LENGTH_SHORT).show()
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
        scrollChannelListTo(0)
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
            FocusSection.CHANNELS   -> handleChannelKeys(keyCode)
            FocusSection.PROGRAMS   -> handleProgramKeys(keyCode)
        }
    }

    private fun handleCategoryKeys(keyCode: Int): Boolean = when (keyCode) {
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
                channelAdapter.setHighlight(selectedChannelIndex)
                scrollChannelListTo(selectedChannelIndex)
                highlightCategory()
            }
            true
        }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> true
        else -> false
    }

    private var lastScrollTime = 0L
    private val scrollThrottleMs = 80L
    private var lastProgramScrollTime = 0L  // separate throttle for program list

    private fun handleChannelKeys(keyCode: Int): Boolean {
        val channelList = binding.root.findViewById<RecyclerView>(R.id.channelList) ?: return false

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                val now = System.currentTimeMillis()
                if (now - lastScrollTime < scrollThrottleMs) return true
                lastScrollTime = now
                if (selectedChannelIndex > 0 && filteredChannels.isNotEmpty()) {
                    selectedChannelIndex = (selectedChannelIndex - 1).coerceIn(0, filteredChannels.size - 1)
                    channelAdapter.setHighlight(selectedChannelIndex)
                    channelList.smoothScrollToPosition(selectedChannelIndex)
                    hideProgramPanel()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val now = System.currentTimeMillis()
                if (now - lastScrollTime < scrollThrottleMs) return true
                lastScrollTime = now
                if (selectedChannelIndex < filteredChannels.size - 1 && filteredChannels.isNotEmpty()) {
                    selectedChannelIndex = (selectedChannelIndex + 1).coerceIn(0, filteredChannels.size - 1)
                    channelAdapter.setHighlight(selectedChannelIndex)
                    channelList.smoothScrollToPosition(selectedChannelIndex)
                    hideProgramPanel()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                currentFocusSection = FocusSection.CATEGORIES
                // Keep highlight visible so user sees where they were
                channelAdapter.setHighlight(selectedChannelIndex)
                highlightCategory()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (selectedChannelIndex >= 0 && selectedChannelIndex < filteredChannels.size) {
                    loadProgramsForChannel(selectedChannelIndex)
                    currentFocusSection = FocusSection.PROGRAMS
                    programAdapter.setHighlight(selectedProgramIndex)
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

    /**
     * Move to the next/prev PROGRAM item, skipping DateDivider rows entirely.
     * Uses the same throttle + smoothScrollToPosition approach as the channel list
     * so fast scrolling feels identical — smooth, with a small natural delay.
     */
    private fun handleProgramKeys(keyCode: Int): Boolean {
        val programList = binding.root.findViewById<RecyclerView>(R.id.programList) ?: return false
        val itemCount   = programAdapter.itemCount

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                val now = System.currentTimeMillis()
                if (now - lastProgramScrollTime < scrollThrottleMs) return true
                lastProgramScrollTime = now

                // Find next selectable (non-divider) index above current
                var target = selectedProgramIndex - 1
                while (target >= 0 && currentProgramItems.getOrNull(target) is ProgramItem.DateDivider) {
                    target--
                }
                if (target >= 0) {
                    selectedProgramIndex = target
                    programAdapter.setHighlight(selectedProgramIndex)
                    programList.smoothScrollToPosition(selectedProgramIndex)
                    updateHoveredDate(selectedProgramIndex)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val now = System.currentTimeMillis()
                if (now - lastProgramScrollTime < scrollThrottleMs) return true
                lastProgramScrollTime = now

                // Find next selectable (non-divider) index below current
                var target = selectedProgramIndex + 1
                while (target < itemCount && currentProgramItems.getOrNull(target) is ProgramItem.DateDivider) {
                    target++
                }
                if (target < itemCount) {
                    selectedProgramIndex = target
                    programAdapter.setHighlight(selectedProgramIndex)
                    programList.smoothScrollToPosition(selectedProgramIndex)
                    updateHoveredDate(selectedProgramIndex)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                currentFocusSection = FocusSection.CHANNELS
                programAdapter.setHighlight(-1)
                channelAdapter.setHighlight(selectedChannelIndex)
                scrollChannelListTo(selectedChannelIndex)
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val item = currentProgramItems.getOrNull(selectedProgramIndex)
                if (item is ProgramItem.ProgramData) handleProgramClick(item.program)
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
            val logo: ImageView         = itemView.findViewById(R.id.channelLogo)
            val number: TextView        = itemView.findViewById(R.id.tvChannelNumber)
            val name: TextView          = itemView.findViewById(R.id.tvChannelName)
            val favoriteIcon: ImageView = itemView.findViewById(R.id.ivFavorite)
            val selectionOutline: View  = itemView.findViewById(R.id.selectionOutline)

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
                name.text   = channel.name

                if (!channel.logoUrl.isNullOrEmpty()) {
                    Glide.with(activity).load(channel.logoUrl)
                        .placeholder(R.color.surface_light)
                        .error(R.color.surface_light)
                        .into(logo)
                }

                favoriteIcon.visibility     = if (channel.isFavorite) View.VISIBLE else View.GONE
                selectionOutline.visibility = if (isHighlighted) View.VISIBLE else View.GONE

                // Name: bright white when selected, slightly dimmer when not
                name.setTextColor(if (isHighlighted) 0xFFF1F5F9.toInt() else 0xAAD1D9E6.toInt())
                // Number: lavender tint when selected, dim otherwise
                number.setTextColor(if (isHighlighted) 0xFFB0B3F5.toInt() else 0x99B0B3F5.toInt())

                // Row alpha
                itemView.alpha = if (isHighlighted) 1.0f else 0.85f
            }
        }

        fun setHighlight(pos: Int) {
            if (pos < -1 || pos >= channels.size) return
            val old = highlightedPos
            highlightedPos = pos
            if (old >= 0 && old < itemCount) notifyItemChanged(old)
            if (pos >= 0 && pos < itemCount) notifyItemChanged(pos)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_epg_channel, parent, false)
        )

        override fun onBindViewHolder(holder: VH, position: Int) {
            if (position < 0 || position >= channels.size) return
            holder.bind(channels[position], position == highlightedPos)
        }

        override fun getItemCount() = channels.size

        fun updateChannels(newChannels: List<Channel>) {
            channels = newChannels
            highlightedPos = -1
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
        private val rowDateFmt = SimpleDateFormat("d MMM", Locale.getDefault())

        inner class ProgramVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val time: TextView      = itemView.findViewById(R.id.tvProgramTime)
            val title: TextView     = itemView.findViewById(R.id.tvProgramTitle)
            val accentBar: View?    = itemView.findViewById(R.id.programAccentBar)
            val nowBadge: TextView? = itemView.findViewById(R.id.tvNowBadge)
            val dateCol: TextView?  = itemView.findViewById(R.id.tvProgramDate)

            init {
                itemView.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        (items[pos] as? ProgramItem.ProgramData)?.let { onProgramClick(it.program) }
                    }
                }
            }

            fun bind(program: Program, isHighlighted: Boolean) {
                val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                time.text  = "${fmt.format(Date(program.startTime))}–${fmt.format(Date(program.endTime))}"
                title.text = program.title

                // Date column: "23 Feb"
                dateCol?.text = rowDateFmt.format(Date(program.startTime))

                val isPlaying = program.isCurrentlyPlaying()
                val isPast    = program.endTime < System.currentTimeMillis()

                itemView.isActivated = isHighlighted
                itemView.isSelected  = isPlaying && !isHighlighted

                accentBar?.visibility = if (isPlaying) View.VISIBLE else View.INVISIBLE
                nowBadge?.visibility  = if (isPlaying) View.VISIBLE else View.GONE

                // Time — bright for PAST (aired), dim for FUTURE (not yet aired)
                time.setTextColor(when {
                    isHighlighted -> 0xFFB0B3F5.toInt()   // bright lavender when cursor on it
                    isPlaying     -> 0xFFB0B3F5.toInt()   // bright lavender for NOW
                    isPast        -> 0xC46366F1.toInt()   // bright indigo — already aired
                    else          -> 0x556366F1.toInt()   // dim indigo — hasn't aired yet
                })

                // Title — bright for past, dim for future
                title.setTextColor(when {
                    isHighlighted -> 0xFFF1F5F9.toInt()   // pure white when selected
                    isPast        -> 0xEEF1F5F9.toInt()   // near-full white — already aired
                    else          -> 0x55F1F5F9.toInt()   // dim — hasn't aired yet
                })

                // Date column — bright for past, dim for future
                dateCol?.setTextColor(when {
                    isHighlighted -> 0xAAB0B3F5.toInt()   // brighter when selected
                    isPast        -> 0x8094A3B8.toInt()   // normal for aired
                    else          -> 0x3594A3B8.toInt()   // dim for future
                })

                // Alpha — future rows slightly faded, past rows full
                itemView.alpha = when {
                    isHighlighted -> 1.0f
                    isPast        -> 1.0f    // aired: full brightness
                    isPlaying     -> 1.0f    // now: full brightness
                    else          -> 0.6f    // future: subtly faded
                }
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
                holder is ProgramVH && item is ProgramItem.ProgramData ->
                    holder.bind(item.program, position == highlightedPos)
                holder is DividerVH && item is ProgramItem.DateDivider ->
                    holder.dateLabel.text = item.date
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