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
import java.util.Date
import java.util.Locale

class EpgOverlayManager(
    private val activity: Activity,
    private val binding: ActivityPlayerBinding,
    private var channels: List<Channel>,
    private val onChannelSelected: (Int) -> Unit,
    private val onArchiveSelected: (String) -> Unit
) {
    // ── Sealed item type for the program list ─────────────────────────────────

    sealed class ProgramItem {
        data class ProgramData(val program: Program) : ProgramItem()
        data class DateDivider(val date: String) : ProgramItem()
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private val repository = ChannelRepository
    private var currentCategory = "All"
    private var filteredChannels: List<Channel> = channels
    private var selectedChannelIndex = 0
    private var selectedProgramIndex = 0
    private var selectedCategoryIndex = 0
    private var currentProgramItems: List<ProgramItem> = emptyList()

    private val categoryButtons = mutableListOf<TextView>()
    private val scope = CoroutineScope(Dispatchers.Main)

    private enum class FocusSection { CATEGORIES, CHANNELS, PROGRAMS }
    private var focusSection = FocusSection.CATEGORIES

    // Scroll throttle
    private var lastChannelScrollTime = 0L
    private var lastProgramScrollTime = 0L
    private val scrollThrottleMs = 80L

    // Date formatters
    private val fullDateFmt = SimpleDateFormat("EEEE, d MMM yyyy", Locale.getDefault())
    private val shortDateFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())

    // Lazy view references
    private val programPanel     by lazy { binding.root.findViewById<View>(R.id.programPanel) }
    private val programPlaceholder by lazy { binding.root.findViewById<View>(R.id.programPlaceholder) }
    private val tvHoveredDate    by lazy { binding.root.findViewById<TextView>(R.id.tvHoveredDate) }
    private val tvPlaceholderTitle    by lazy { binding.root.findViewById<TextView>(R.id.tvPlaceholderTitle) }
    private val tvPlaceholderSubtitle by lazy { binding.root.findViewById<TextView>(R.id.tvPlaceholderSubtitle) }

    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var programAdapter: ProgramAdapter

    init { setupEpg() }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun setupEpg() {
        channelAdapter = ChannelAdapter(filteredChannels) { pos ->
            selectedChannelIndex = pos
            hideProgramPanel()
        }
        binding.root.findViewById<RecyclerView>(R.id.channelList)?.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = channelAdapter
            itemAnimator = null
            isFocusable = false
            isFocusableInTouchMode = false
            setHasFixedSize(true)
            setItemViewCacheSize(20)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        programAdapter = ProgramAdapter(emptyList()) { handleProgramClick(it) }
        binding.root.findViewById<RecyclerView>(R.id.programList)?.apply {
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

        repository.getCategories().forEachIndexed { index, category ->
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
        selectCategory(categoryButtons.firstOrNull()?.text?.toString() ?: "All")
        highlightCategory()
    }

    private fun selectCategory(category: String) {
        currentCategory = category
        filteredChannels = repository.getChannelsByCategory(category)
        channelAdapter.updateChannels(filteredChannels)
        selectedChannelIndex = 0
        binding.root.findViewById<RecyclerView>(R.id.channelList)
            ?.let { (it.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0) }
        hideProgramPanel()
    }

    private fun highlightCategory() {
        categoryButtons.forEachIndexed { i, btn ->
            val isSelected = i == selectedCategoryIndex
            val isFocused  = focusSection == FocusSection.CATEGORIES && isSelected
            btn.isSelected = isSelected
            btn.alpha = when {
                isFocused  -> 1f
                isSelected -> 0.85f
                else       -> 0.55f
            }
        }
    }

    // ── Program panel helpers ─────────────────────────────────────────────────

    private fun hideProgramPanel() {
        programPanel?.visibility = View.GONE
        programPlaceholder?.visibility = View.VISIBLE
        tvHoveredDate?.visibility = View.GONE
        tvPlaceholderTitle?.text = "Select a channel"
        tvPlaceholderTitle?.setTextColor(0xCCF1F5F9.toInt())
        tvPlaceholderSubtitle?.text = "Press → to browse programs"
        tvPlaceholderSubtitle?.setTextColor(0x60B0B3F5.toInt())
    }

    private fun showLockedChannelInfo(channel: Channel) {
        programPanel?.visibility = View.GONE
        tvHoveredDate?.visibility = View.GONE
        programPlaceholder?.visibility = View.VISIBLE
        tvPlaceholderTitle?.text = channel.name
        tvPlaceholderTitle?.setTextColor(0xCCF1F5F9.toInt())
        tvPlaceholderSubtitle?.text = "Not included in your subscription"
        tvPlaceholderSubtitle?.setTextColor(0xBBF87171.toInt())
    }

    private fun loadProgramsForChannel(channelIndex: Int) {
        val channel = filteredChannels.getOrNull(channelIndex) ?: return
        if (channel.isLocked) { showLockedChannelInfo(channel); return }

        programPlaceholder?.visibility = View.VISIBLE
        programPanel?.visibility = View.GONE
        tvHoveredDate?.visibility = View.GONE

        scope.launch {
            val allPrograms = withContext(Dispatchers.IO) {
                runCatching { ApiService.fetchAllPrograms(channel.apiId).sortedBy { it.startTime } }
                    .getOrDefault(emptyList())
            }

            val items = buildProgramItemList(allPrograms)
            currentProgramItems = items

            // Scroll to current program, skipping any leading divider
            var targetPos = findCurrentProgramPosition(allPrograms, items)
            while (targetPos < items.size && items[targetPos] is ProgramItem.DateDivider) targetPos++
            selectedProgramIndex = targetPos

            val rv = binding.root.findViewById<RecyclerView>(R.id.programList) ?: return@launch
            rv.visibility = View.INVISIBLE
            programAdapter.updatePrograms(items)

            rv.post {
                (rv.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(targetPos, 0)
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

    private fun buildProgramItemList(programs: List<Program>): List<ProgramItem> {
        val items = mutableListOf<ProgramItem>()
        var lastDate: String? = null
        programs.forEach { program ->
            val date = fullDateFmt.format(Date(program.startTime))
            if (date != lastDate) { items.add(ProgramItem.DateDivider(date)); lastDate = date }
            items.add(ProgramItem.ProgramData(program))
        }
        return items
    }

    private fun findCurrentProgramPosition(programs: List<Program>, items: List<ProgramItem>): Int {
        if (programs.isEmpty()) return 0
        val currentIndex = programs.indexOfFirst { it.isCurrentlyPlaying() }
        if (currentIndex < 0) return 0
        // Count date dividers that appear before currentIndex in the flat list
        var dividers = 0
        for (i in 0 until currentIndex) {
            val cur  = fullDateFmt.format(Date(programs[i].startTime))
            val prev = if (i > 0) fullDateFmt.format(Date(programs[i - 1].startTime)) else null
            if (cur != prev) dividers++
        }
        return currentIndex + dividers
    }

    private fun updateHoveredDate(position: Int) {
        val tv = tvHoveredDate ?: return
        val dateStr = when (val item = currentProgramItems.getOrNull(position)) {
            is ProgramItem.ProgramData -> shortDateFmt.format(Date(item.program.startTime))
            is ProgramItem.DateDivider -> {
                (currentProgramItems.getOrNull(position + 1) as? ProgramItem.ProgramData)
                    ?.let { shortDateFmt.format(Date(it.program.startTime)) }
                    ?: item.date
            }
            null -> null
        }
        tv.text = dateStr ?: ""
        tv.visibility = if (dateStr != null) View.VISIBLE else View.GONE
    }

    private fun handleProgramClick(program: Program) {
        val channel     = filteredChannels.getOrNull(selectedChannelIndex) ?: return
        val globalIndex = channels.indexOfFirst { it.id == channel.id }
        val now         = System.currentTimeMillis()
        when {
            program.isCurrentlyPlaying(now)   -> if (globalIndex >= 0) onChannelSelected(globalIndex)
            program.endTime < now             -> onArchiveSelected("ARCHIVE_ID:${channel.id}:TIME:${program.startTime}")
            else -> Toast.makeText(activity, "This program hasn't started yet", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Public interface ──────────────────────────────────────────────────────

    fun refreshData(newChannels: List<Channel>) {
        channels = newChannels
        setupCategories()
        filteredChannels = repository.getChannelsByCategory(currentCategory)
        channelAdapter.updateChannels(filteredChannels)
        selectedChannelIndex = 0
        hideProgramPanel()
    }

    fun requestFocus() {
        focusSection = FocusSection.CATEGORIES
        highlightCategory()
        channelAdapter.setHighlight(-1)
    }

    // ── Key handling ──────────────────────────────────────────────────────────

    fun handleKeyEvent(keyCode: Int): Boolean = when (focusSection) {
        FocusSection.CATEGORIES -> handleCategoryKeys(keyCode)
        FocusSection.CHANNELS   -> handleChannelKeys(keyCode)
        FocusSection.PROGRAMS   -> handleProgramKeys(keyCode)
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
                focusSection = FocusSection.CHANNELS
                channelAdapter.setHighlight(selectedChannelIndex)
                scrollChannelListTo(selectedChannelIndex)
                highlightCategory()
                filteredChannels.getOrNull(selectedChannelIndex)
                    ?.takeIf { it.isLocked }
                    ?.let { showLockedChannelInfo(it) }
            }
            true
        }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> true
        else -> false
    }

    private fun handleChannelKeys(keyCode: Int): Boolean {
        val channelList = binding.root.findViewById<RecyclerView>(R.id.channelList) ?: return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val now = System.currentTimeMillis()
                if (now - lastChannelScrollTime < scrollThrottleMs) return true
                lastChannelScrollTime = now
                val delta = if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                val next  = (selectedChannelIndex + delta).coerceIn(0, filteredChannels.size - 1)
                if (next != selectedChannelIndex) {
                    selectedChannelIndex = next
                    channelAdapter.setHighlight(selectedChannelIndex)
                    channelList.smoothScrollToPosition(selectedChannelIndex)
                    val ch = filteredChannels.getOrNull(selectedChannelIndex)
                    if (ch?.isLocked == true) showLockedChannelInfo(ch) else hideProgramPanel()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                focusSection = FocusSection.CATEGORIES
                channelAdapter.setHighlight(selectedChannelIndex)
                highlightCategory()
                hideProgramPanel()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val ch = filteredChannels.getOrNull(selectedChannelIndex)
                if (ch?.isLocked == true) showLockedChannelInfo(ch)
                else {
                    loadProgramsForChannel(selectedChannelIndex)
                    focusSection = FocusSection.PROGRAMS
                    programAdapter.setHighlight(selectedProgramIndex)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val ch = filteredChannels.getOrNull(selectedChannelIndex) ?: return false
                if (ch.isLocked) { showLockedChannelInfo(ch); return true }
                val globalIndex = channels.indexOfFirst { it.id == ch.id }
                if (globalIndex >= 0) onChannelSelected(globalIndex)
                true
            }
            else -> false
        }
    }

    private fun handleProgramKeys(keyCode: Int): Boolean {
        val programList = binding.root.findViewById<RecyclerView>(R.id.programList) ?: return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val now = System.currentTimeMillis()
                if (now - lastProgramScrollTime < scrollThrottleMs) return true
                lastProgramScrollTime = now
                val delta = if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                var target = selectedProgramIndex + delta
                // Skip over date dividers
                while (target in 0 until programAdapter.itemCount &&
                    currentProgramItems.getOrNull(target) is ProgramItem.DateDivider) {
                    target += delta
                }
                if (target in 0 until programAdapter.itemCount) {
                    selectedProgramIndex = target
                    programAdapter.setHighlight(selectedProgramIndex)
                    programList.smoothScrollToPosition(selectedProgramIndex)
                    updateHoveredDate(selectedProgramIndex)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                focusSection = FocusSection.CHANNELS
                programAdapter.setHighlight(-1)
                channelAdapter.setHighlight(selectedChannelIndex)
                scrollChannelListTo(selectedChannelIndex)
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                (currentProgramItems.getOrNull(selectedProgramIndex) as? ProgramItem.ProgramData)
                    ?.let { handleProgramClick(it.program) }
                true
            }
            else -> false
        }
    }

    private fun scrollChannelListTo(position: Int) {
        binding.root.findViewById<RecyclerView>(R.id.channelList)
            ?.let { (it.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(position, 0) }
    }

    // ── Channel Adapter ───────────────────────────────────────────────────────

    inner class ChannelAdapter(
        private var channels: List<Channel>,
        private val onChannelClick: (Int) -> Unit
    ) : RecyclerView.Adapter<ChannelAdapter.VH>() {

        private var highlightedPos = -1

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val logo:            ImageView = itemView.findViewById(R.id.channelLogo)
            val number:          TextView  = itemView.findViewById(R.id.tvChannelNumber)
            val name:            TextView  = itemView.findViewById(R.id.tvChannelName)
            val favoriteIcon:    ImageView = itemView.findViewById(R.id.ivFavorite)
            val selectionOutline: View     = itemView.findViewById(R.id.selectionOutline)

            init {
                itemView.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) { highlightedPos = pos; notifyDataSetChanged(); onChannelClick(pos) }
                }
            }

            fun bind(channel: Channel, isHighlighted: Boolean) {
                number.text = channel.number.toString()
                name.text   = channel.name
                selectionOutline.visibility = if (isHighlighted) View.VISIBLE else View.GONE

                if (!channel.logoUrl.isNullOrEmpty()) {
                    Glide.with(activity).load(channel.logoUrl)
                        .placeholder(R.color.surface_light).error(R.color.surface_light).into(logo)
                }

                if (channel.isLocked) {
                    favoriteIcon.visibility = View.GONE
                    name.setTextColor(0x55F1F5F9.toInt())
                    number.setTextColor(0x3394A3B8.toInt())
                    itemView.alpha = 0.35f
                } else {
                    favoriteIcon.visibility = if (channel.isFavorite) View.VISIBLE else View.GONE
                    name.setTextColor(if (isHighlighted) 0xFFF1F5F9.toInt() else 0xDDF1F5F9.toInt())
                    number.setTextColor(if (isHighlighted) 0xFFB0B3F5.toInt() else 0xBBB0B3F5.toInt())
                    itemView.alpha = if (isHighlighted) 1f else 0.85f
                }
            }
        }

        fun setHighlight(pos: Int) {
            val old = highlightedPos
            highlightedPos = pos
            if (old in 0 until itemCount) notifyItemChanged(old)
            if (pos in 0 until itemCount) notifyItemChanged(pos)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_epg_channel, parent, false)
        )
        override fun onBindViewHolder(holder: VH, position: Int) {
            if (position in channels.indices) holder.bind(channels[position], position == highlightedPos)
        }
        override fun getItemCount() = channels.size

        fun updateChannels(newChannels: List<Channel>) {
            channels = newChannels
            highlightedPos = -1
            notifyDataSetChanged()
        }
    }

    // ── Program Adapter ───────────────────────────────────────────────────────

    class ProgramAdapter(
        private var items: List<ProgramItem>,
        private val onProgramClick: (Program) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val TYPE_PROGRAM = 0
            private const val TYPE_DIVIDER = 1
        }

        private var highlightedPos = -1
        private val rowDateFmt = SimpleDateFormat("d MMM", Locale.getDefault())
        private val timeFmt    = SimpleDateFormat("HH:mm", Locale.getDefault())

        inner class ProgramVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val time:      TextView  = itemView.findViewById(R.id.tvProgramTime)
            val title:     TextView  = itemView.findViewById(R.id.tvProgramTitle)
            val accentBar: View?     = itemView.findViewById(R.id.programAccentBar)
            val nowBadge:  TextView? = itemView.findViewById(R.id.tvNowBadge)
            val dateCol:   TextView? = itemView.findViewById(R.id.tvProgramDate)

            init {
                itemView.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION)
                        (items[pos] as? ProgramItem.ProgramData)?.let { onProgramClick(it.program) }
                }
            }

            fun bind(program: Program, isHighlighted: Boolean) {
                time.text  = "${timeFmt.format(Date(program.startTime))}–${timeFmt.format(Date(program.endTime))}"
                title.text = program.title
                dateCol?.text = rowDateFmt.format(Date(program.startTime))

                val isPlaying = program.isCurrentlyPlaying()
                val isPast    = program.endTime < System.currentTimeMillis()

                itemView.isActivated = isHighlighted
                itemView.isSelected  = isPlaying && !isHighlighted
                accentBar?.visibility = if (isPlaying) View.VISIBLE else View.INVISIBLE
                nowBadge?.visibility  = if (isPlaying) View.VISIBLE else View.GONE

                time.setTextColor(if (isHighlighted || isPlaying) 0xFFB0B3F5.toInt() else if (isPast) 0xC46366F1.toInt() else 0x886366F1.toInt())
                title.setTextColor(if (isHighlighted) 0xFFF1F5F9.toInt() else 0xEEF1F5F9.toInt())
                dateCol?.setTextColor(if (isHighlighted) 0xAAB0B3F5.toInt() else if (isPast) 0x8094A3B8.toInt() else 0x3594A3B8.toInt())
                itemView.alpha = if (isHighlighted || isPast || isPlaying) 1f else 0.8f
            }
        }

        inner class DividerVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val dateLabel: TextView = itemView.findViewById(R.id.tvDateDivider)
        }

        fun setHighlight(pos: Int) {
            val old = highlightedPos
            highlightedPos = pos
            if (old in 0 until itemCount) notifyItemChanged(old)
            if (pos in 0 until itemCount) notifyItemChanged(pos)
        }

        override fun getItemViewType(pos: Int) =
            if (items[pos] is ProgramItem.ProgramData) TYPE_PROGRAM else TYPE_DIVIDER

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