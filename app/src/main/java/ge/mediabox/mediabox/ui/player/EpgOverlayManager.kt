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
    private val dateFormat     = SimpleDateFormat("EEEE, d MMM yyyy", Locale.getDefault())
    // Day-of-month format for the header chip e.g. "Mon 12"
    private val dayFormat      = SimpleDateFormat("EEE d MMM", Locale.getDefault())

    private val scope = CoroutineScope(Dispatchers.Main)

    private enum class FocusSection { CATEGORIES, CHANNELS, PROGRAMS }
    private var currentFocusSection = FocusSection.CATEGORIES

    private var selectedCategoryIndex = 0
    private var selectedProgramIndex  = 0

    // Cached program list so we can look up date when navigating
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
        // FIX: always reset to top when category changes
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
                    btn.isSelected = false; btn.alpha = 0.8f
                }
                else -> {
                    btn.isSelected = false; btn.alpha = 0.5f
                }
            }
        }
    }

    // =========================================================================
    // Scroll helpers
    // =========================================================================

    private fun scrollChannelListTo(position: Int) {
        val channelList = binding.root.findViewById<RecyclerView>(R.id.channelList) ?: return
        val lm = channelList.layoutManager as? LinearLayoutManager ?: return
        // Instant scroll so it's in sync with the cursor
        lm.scrollToPositionWithOffset(position, 0)
    }

    private fun scrollProgramListTo(position: Int) {
        val programList = binding.root.findViewById<RecyclerView>(R.id.programList) ?: return
        val lm = programList.layoutManager as? LinearLayoutManager ?: return
        lm.scrollToPositionWithOffset(position, 0)
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

        showProgramPanel()
        binding.root.findViewById<TextView>(R.id.tvSelectedChannelName)?.text =
            "${channel.name} – Loading…"
        tvHoveredDate?.visibility = View.GONE

        scope.launch {
            val allPrograms = withContext(Dispatchers.IO) {
                try { ApiService.fetchAllPrograms(channel.apiId).sortedBy { it.startTime } }
                catch (e: Exception) { emptyList() }
            }

            binding.root.findViewById<TextView>(R.id.tvSelectedChannelName)?.text = channel.name

            val itemsWithDividers = createProgramListWithDividers(allPrograms)
            currentProgramItems = itemsWithDividers
            programAdapter.updatePrograms(itemsWithDividers)

            val targetPos = findCurrentProgramPosition(allPrograms, itemsWithDividers)
            selectedProgramIndex = targetPos
            scrollProgramListTo(targetPos)
            programAdapter.setHighlight(targetPos)
            updateHoveredDate(targetPos)
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

    // Update the date chip in the header to show which day the hovered program is on
    private fun updateHoveredDate(position: Int) {
        val item = currentProgramItems.getOrNull(position) ?: return
        val tv = tvHoveredDate ?: return

        // Walk backwards to find the most recent DateDivider for this position
        var dateStr: String? = null
        for (i in position downTo 0) {
            val candidate = currentProgramItems.getOrNull(i)
            if (candidate is ProgramItem.DateDivider) {
                dateStr = candidate.date
                break
            }
            if (candidate is ProgramItem.ProgramData) {
                // Use the program's own date
                dateStr = dayFormat.format(Date(candidate.program.startTime))
                break
            }
        }

        // If the item itself is a program use its date directly
        if (item is ProgramItem.ProgramData) {
            dateStr = dayFormat.format(Date(item.program.startTime))
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
                // FIX: always highlight and scroll to selectedChannelIndex (memory)
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
                // FIX: go back to categories but remember channel position
                currentFocusSection = FocusSection.CATEGORIES
                channelAdapter.setHighlight(selectedChannelIndex) // keep visual highlight
                highlightCategory()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (selectedChannelIndex >= 0 && selectedChannelIndex < filteredChannels.size) {
                    loadProgramsForChannel(selectedChannelIndex)
                    currentFocusSection = FocusSection.PROGRAMS
                    programAdapter.setHighlight(selectedProgramIndex)
                    scrollProgramListTo(selectedProgramIndex)
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
        val itemCount = programAdapter.itemCount

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (selectedProgramIndex > 0) {
                    selectedProgramIndex--
                    programAdapter.setHighlight(selectedProgramIndex)
                    scrollProgramListTo(selectedProgramIndex)
                    updateHoveredDate(selectedProgramIndex)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (selectedProgramIndex < itemCount - 1) {
                    selectedProgramIndex++
                    programAdapter.setHighlight(selectedProgramIndex)
                    scrollProgramListTo(selectedProgramIndex)
                    updateHoveredDate(selectedProgramIndex)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                currentFocusSection = FocusSection.CHANNELS
                programAdapter.setHighlight(-1)
                // FIX: restore channel highlight and scroll position
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
                favoriteIcon.visibility    = if (channel.isFavorite) View.VISIBLE else View.GONE
                selectionOutline.visibility = if (isHighlighted) View.VISIBLE else View.GONE
                itemView.animate()
                    .alpha(if (isHighlighted) 1.0f else 0.65f)
                    .setDuration(80)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
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

        inner class ProgramVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val time: TextView        = itemView.findViewById(R.id.tvProgramTime)
            val title: TextView       = itemView.findViewById(R.id.tvProgramTitle)
            val accentBar: View?      = itemView.findViewById(R.id.programAccentBar)
            val nowBadge: TextView?   = itemView.findViewById(R.id.tvNowBadge)

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

                val isPlaying = program.isCurrentlyPlaying()
                val isPast    = program.endTime < System.currentTimeMillis()

                itemView.isActivated = isHighlighted
                itemView.isSelected  = isPlaying && !isHighlighted

                accentBar?.visibility = if (isPlaying) View.VISIBLE else View.INVISIBLE
                nowBadge?.visibility  = if (isPlaying) View.VISIBLE else View.GONE

                time.setTextColor(when {
                    isPlaying -> 0xFFB0B3F5.toInt()
                    isPast    -> 0x2694A3B8.toInt()
                    else      -> 0x806366F1.toInt()
                })

                title.setTextColor(when {
                    isHighlighted -> 0xFFF1F5F9.toInt()
                    isPast        -> 0x4DF1F5F9.toInt()
                    else          -> 0xBFF1F5F9.toInt()
                })

                itemView.alpha = when {
                    isHighlighted -> 1.0f
                    isPast        -> 0.5f
                    else          -> 1.0f
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