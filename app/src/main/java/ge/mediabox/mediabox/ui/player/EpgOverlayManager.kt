package ge.mediabox.mediabox.ui.player

import android.app.Activity
import android.graphics.drawable.AnimatedVectorDrawable
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import ge.mediabox.mediabox.R
import ge.mediabox.mediabox.data.model.Channel
import ge.mediabox.mediabox.data.model.Program
import ge.mediabox.mediabox.data.repository.ChannelRepository
import ge.mediabox.mediabox.databinding.ActivityPlayerBinding
import ge.mediabox.mediabox.ui.LangPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job

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
    private var currentCategory = ""
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
    private var fullDateFmt = SimpleDateFormat("EEEE, d MMM yyyy", LangPrefs.getLocale(activity))
    private var shortDateFmt = SimpleDateFormat("EEE d MMM", LangPrefs.getLocale(activity))

    // Lazy view references
    private val programPanel     by lazy { binding.root.findViewById<View>(R.id.programPanel) }
    private val programPlaceholder by lazy { binding.root.findViewById<View>(R.id.programPlaceholder) }
    private val tvHoveredDate    by lazy { binding.root.findViewById<TextView>(R.id.tvHoveredDate) }
    private val tvPlaceholderTitle    by lazy { binding.root.findViewById<TextView>(R.id.tvPlaceholderTitle) }
    private val tvPlaceholderSubtitle by lazy { binding.root.findViewById<TextView>(R.id.tvPlaceholderSubtitle) }
    private val channelListScrim  by lazy { binding.root.findViewById<View>(R.id.channelListScrim) }
    private val programPanelScrim by lazy { binding.root.findViewById<View>(R.id.programPanelScrim) }

    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var programAdapter: ProgramAdapter

    init { setupEpg() }

    private fun scrollCategoryIntoView(index: Int) {
        val scrollView = binding.root.findViewById<HorizontalScrollView>(R.id.categoryScrollView) ?: return
        val btn = categoryButtons.getOrNull(index) ?: return
        // post so layout is measured before we scroll
        scrollView.post {
            val btnLeft = btn.left
            val btnRight = btn.right
            val scrollX = scrollView.scrollX
            val width = scrollView.width
            when {
                btnLeft < scrollX -> scrollView.smoothScrollTo(btnLeft - 20, 0)
                btnRight > scrollX + width -> scrollView.smoothScrollTo(btnRight - width + 20, 0)
            }
        }
    }

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
            setItemViewCacheSize(40)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        programAdapter = ProgramAdapter(activity, emptyList()) { handleProgramClick(it) }
        binding.root.findViewById<RecyclerView>(R.id.programList)?.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = programAdapter
            isFocusable = false
            isFocusableInTouchMode = false
            setHasFixedSize(false)
            setItemViewCacheSize(40)
            itemAnimator = null
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

        }

        setupCategories()
        hideProgramPanel()
    }

    private fun setupCategories() {
        val container = binding.root.findViewById<LinearLayout>(R.id.categoryButtons)
        val scrollView = binding.root.findViewById<HorizontalScrollView>(R.id.categoryScrollView)

        // Prevent ScrollView from ever stealing DPAD events
        scrollView?.isFocusable = false
        scrollView?.isFocusableInTouchMode = false
        scrollView?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

        container?.removeAllViews()
        categoryButtons.clear()

        val isKa = LangPrefs.isKa(activity)
        val categories = repository.getCategories(isKa)

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

        // Logic to maintain category selection on language swap or refresh
        if (currentCategory.isEmpty() || !categories.contains(currentCategory)) {
            selectedCategoryIndex = 0
            currentCategory = categories.firstOrNull() ?: ""
        } else {
            selectedCategoryIndex = categories.indexOf(currentCategory)
        }

        selectCategory(currentCategory)
        highlightCategory()
    }

    private fun selectCategory(category: String) {
        val isKa = LangPrefs.isKa(activity)
        currentCategory = category
        filteredChannels = repository.getChannelsByCategory(category, isKa)
        channelAdapter.updateChannels(filteredChannels)
        selectedChannelIndex = 0
        binding.root.findViewById<RecyclerView>(R.id.channelList)
            ?.let { (it.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0, 0) }
        hideProgramPanel()
    }

    private fun updateScrimForSection() {
        when (focusSection) {
            FocusSection.CATEGORIES -> {
                channelListScrim?.visibility  = View.GONE
                programPanelScrim?.visibility = View.VISIBLE
            }
            FocusSection.CHANNELS -> {
                channelListScrim?.visibility  = View.GONE
                programPanelScrim?.visibility = View.VISIBLE
            }
            FocusSection.PROGRAMS -> {
                channelListScrim?.visibility  = View.VISIBLE
                programPanelScrim?.visibility = View.GONE
            }
        }
    }
    private fun highlightCategory() {
        categoryButtons.forEachIndexed { i, btn ->
            val isSelected = i == selectedCategoryIndex
            val isFocused  = focusSection == FocusSection.CATEGORIES && isSelected
            btn.isSelected = isSelected
            btn.alpha = when {
                isFocused  -> 1f
                isSelected -> 0.60f
                else       -> 0.45f
            }
        }
        categoryButtons.getOrNull(selectedCategoryIndex)?.let { btn ->
            binding.root.findViewById<HorizontalScrollView>(R.id.categoryScrollView)
                ?.smoothScrollTo(btn.left - 40, 0)
        }
        updateScrimForSection()
    }

    // ── Program panel helpers ─────────────────────────────────────────────────

    private fun hideProgramPanel() {
        programPanel?.visibility = View.GONE
        programPlaceholder?.visibility = View.GONE  // No placeholder at all
        tvHoveredDate?.visibility = View.GONE
    }

    private fun showLockedChannelInfo(channel: Channel) {
        programPanel?.visibility = View.GONE
        tvHoveredDate?.visibility = View.GONE
        programPlaceholder?.visibility = View.VISIBLE
        tvPlaceholderTitle?.text = channel.name
        tvPlaceholderTitle?.setTextColor(0xCCF1F5F9.toInt())
        tvPlaceholderSubtitle?.text = if (LangPrefs.isKa(activity)) "არ შედის თქვენს პაკეტში" else "Not included in your subscription"
        tvPlaceholderSubtitle?.setTextColor(0xBBF87171.toInt())
    }

    private var loadProgramsJob: Job? = null

    private fun loadProgramsForChannel(channelIndex: Int) {
        val channel = filteredChannels.getOrNull(channelIndex) ?: return
        if (channel.isLocked) { showLockedChannelInfo(channel); return }

        loadProgramsJob?.cancel()

        val cached = channel.programs
        if (cached.isNotEmpty()) {
            loadProgramsJob = scope.launch {
                renderPrograms(channel, cached)
            }
        } else {
            // No placeholder — just silently wait for data
            hideProgramPanel()
            loadProgramsJob = scope.launch {
                val programs = ChannelRepository.getProgramsForChannel(channel.id)
                if (selectedChannelIndex == channelIndex && programs.isNotEmpty()) {
                    renderPrograms(channel, programs)
                }
            }
        }
    }

    private suspend fun renderPrograms(
        channel: Channel,
        programs: List<Program>,
        overrideTimestampMs: Long? = null
    ) {
        val items = buildProgramItemList(programs)
        currentProgramItems = items

        // Find position: use override timestamp (archive mode) or current time (live)
        val anchorMs = overrideTimestampMs ?: System.currentTimeMillis()
        var targetPos = findProgramPositionForTime(programs, anchorMs)
        while (targetPos < items.size && items[targetPos] is ProgramItem.DateDivider) targetPos++
        selectedProgramIndex = targetPos

        val rv = binding.root.findViewById<RecyclerView>(R.id.programList) ?: return

        withContext(Dispatchers.Main) {
            rv.visibility = View.INVISIBLE
            programAdapter.updatePrograms(items, overrideTimestampMs)

            rv.post {
                (rv.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(targetPos, 0)
                rv.post {
                    binding.root.findViewById<TextView>(R.id.tvSelectedChannelName)
                        ?.text = channel.name
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

    // REMOVE the old findCurrentProgramPosition() entirely, replace with:
    private fun findProgramPositionForTime(
        programs: List<Program>,
        timestampMs: Long
    ): Int {
        if (programs.isEmpty()) return 0
        val targetIndex = programs.indexOfFirst { timestampMs in it.startTime until it.endTime }
        val idx = if (targetIndex >= 0) targetIndex else {
            // Fallback: find closest past program
            programs.indexOfLast { it.endTime <= timestampMs }.coerceAtLeast(0)
        }
        // Count date dividers before idx in flat list
        var dividers = 0
        for (i in 0 until idx) {
            val cur  = fullDateFmt.format(Date(programs[i].startTime))
            val prev = if (i > 0) fullDateFmt.format(Date(programs[i - 1].startTime)) else null
            if (cur != prev) dividers++
        }
        return idx + dividers
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
            else -> Toast.makeText(activity, if (LangPrefs.isKa(activity)) "ეს პროგრამა ჯერ არ დაწყებულა" else "This program hasn't started yet", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Public interface ──────────────────────────────────────────────────────

    fun refreshData(newChannels: List<Channel>) {
        channels = newChannels
        val isKa = LangPrefs.isKa(activity)
        val locale = LangPrefs.getLocale(activity)
        
        fullDateFmt = SimpleDateFormat("EEEE, d MMM yyyy", locale)
        shortDateFmt = SimpleDateFormat("EEE d MMM", locale)
        programAdapter.updateLocale(locale)

        repository.refreshLocalization(isKa)
        setupCategories()
        filteredChannels = repository.getChannelsByCategory(currentCategory, isKa)
        channelAdapter.updateChannels(filteredChannels)
        selectedChannelIndex = 0
        hideProgramPanel()
    }

    fun requestFocus(currentChannelId: Int, currentTimestampMs: Long = System.currentTimeMillis()) {
        val index = filteredChannels.indexOfFirst { it.id == currentChannelId }

        if (index != -1) {
            focusSection = FocusSection.CHANNELS
            selectedChannelIndex = index
            channelAdapter.setHighlight(selectedChannelIndex)
            updateScrimForSection()

            val rv = binding.root.findViewById<RecyclerView>(R.id.channelList)
            (rv?.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(selectedChannelIndex, 150)

            val channel = filteredChannels[index]
            if (channel.isLocked) {
                showLockedChannelInfo(channel)
                return
            }

            loadProgramsJob?.cancel()
            val cached = channel.programs
            if (cached.isNotEmpty()) {
                loadProgramsJob = scope.launch {
                    renderPrograms(channel, cached, currentTimestampMs)
                }
            } else {
                hideProgramPanel()
                loadProgramsJob = scope.launch {
                    val programs = ChannelRepository.getProgramsForChannel(channel.id)
                    if (programs.isNotEmpty()) renderPrograms(channel, programs, currentTimestampMs)
                }
            }
        } else {
            focusSection = FocusSection.CATEGORIES
            highlightCategory()
            updateScrimForSection()
        }
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
                scrollCategoryIntoView(selectedCategoryIndex)
            }
            true
        }
        KeyEvent.KEYCODE_DPAD_RIGHT -> {
            if (selectedCategoryIndex < categoryButtons.size - 1) {
                selectedCategoryIndex++
                selectCategory(categoryButtons[selectedCategoryIndex].text.toString())
                highlightCategory()
                scrollCategoryIntoView(selectedCategoryIndex)
            }
            true
        }
        KeyEvent.KEYCODE_DPAD_DOWN -> {
            if (filteredChannels.isNotEmpty()) {
                focusSection = FocusSection.CHANNELS
                channelAdapter.setHighlight(selectedChannelIndex)
                scrollChannelListTo(selectedChannelIndex)
                highlightCategory()
                updateScrimForSection()
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
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (selectedChannelIndex > 0) {
                    val now = System.currentTimeMillis()
                    if (now - lastChannelScrollTime < scrollThrottleMs) return true
                    lastChannelScrollTime = now
                    selectedChannelIndex--
                    channelAdapter.setHighlight(selectedChannelIndex)
                    channelList.smoothScrollToPosition(selectedChannelIndex)
                    val ch = filteredChannels.getOrNull(selectedChannelIndex)
                    if (ch?.isLocked == true) showLockedChannelInfo(ch)
                    else loadProgramsForChannel(selectedChannelIndex)
                } else {
                    focusSection = FocusSection.CATEGORIES
                    channelAdapter.setHighlight(-1)
                    highlightCategory()
                    updateScrimForSection()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (selectedChannelIndex < filteredChannels.size - 1) {
                    val now = System.currentTimeMillis()
                    if (now - lastChannelScrollTime < scrollThrottleMs) return true
                    lastChannelScrollTime = now
                    selectedChannelIndex++
                    channelAdapter.setHighlight(selectedChannelIndex)
                    channelList.smoothScrollToPosition(selectedChannelIndex)
                    val ch = filteredChannels.getOrNull(selectedChannelIndex)
                    if (ch?.isLocked == true) showLockedChannelInfo(ch)
                    else loadProgramsForChannel(selectedChannelIndex)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                focusSection = FocusSection.CATEGORIES
                channelAdapter.setHighlight(-1)
                highlightCategory()
                updateScrimForSection()
                hideProgramPanel()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val ch = filteredChannels.getOrNull(selectedChannelIndex)
                if (ch?.isLocked == true) {
                    showLockedChannelInfo(ch)
                } else {
                    // Enter programs regardless — if not loaded yet, wait for it
                    focusSection = FocusSection.PROGRAMS
                    updateScrimForSection()
                    if (programPanel?.visibility == View.VISIBLE) {
                        programAdapter.setHighlight(selectedProgramIndex)
                    }
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
                updateScrimForSection()
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
        private var list: List<Channel>,
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
                    name.setTextColor(if (isHighlighted) 0xFFFFFFFF.toInt() else 0xEEF1F5F9.toInt())
                    number.setTextColor(if (isHighlighted) 0xFFE5E7EB.toInt() else 0x8894A3B8.toInt())
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
            if (position in list.indices) holder.bind(list[position], position == highlightedPos)
        }
        override fun getItemCount() = list.size

        fun updateChannels(newChannels: List<Channel>) {
            list = newChannels
            highlightedPos = -1
            notifyDataSetChanged()
        }
    }

    // ── Program Adapter ───────────────────────────────────────────────────────

    // FIX: Removed 'inner' to allow Companion Object
    class ProgramAdapter(
        private val activity: Activity,
        private var items: List<ProgramItem>,
        private val onProgramClick: (Program) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val TYPE_PROGRAM = 0
            private const val TYPE_DIVIDER = 1
        }

        private var highlightedPos = -1

        // Change the class-level field — add this near the top of ProgramAdapter:
        private var watchingTimestampMs: Long? = null  // null = live mode
        private var rowDateFmt = SimpleDateFormat("d MMM", LangPrefs.getLocale(activity))
        private var timeFmt    = SimpleDateFormat("HH:mm", LangPrefs.getLocale(activity))

        fun updateLocale(locale: Locale) {
            rowDateFmt = SimpleDateFormat("d MMM", locale)
            timeFmt    = SimpleDateFormat("HH:mm", locale)
            notifyDataSetChanged()
        }

        inner class ProgramVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val time:      TextView  = itemView.findViewById(R.id.tvProgramTime)
            val title:     TextView  = itemView.findViewById(R.id.tvProgramTitle)
            val accentBar: View?     = itemView.findViewById(R.id.programAccentBar)
            val playingAnim: ImageView? = itemView.findViewById(R.id.ivPlayingAnim)
            val dateCol:   TextView? = itemView.findViewById(R.id.tvProgramDate)

            fun bind(program: Program, isHighlighted: Boolean) {
                val now = System.currentTimeMillis()
                val anchorMs = watchingTimestampMs ?: now

                // Is this the program currently being watched (live or archive)?
                val isWatching = anchorMs in program.startTime until program.endTime
                // Is it truly live (no archive override, program is airing now)?
                val isTrulyLive = watchingTimestampMs == null && program.isCurrentlyPlaying(now)

                val isPast   = program.endTime < now
                val isFuture = program.startTime > now

                time.text  = "${timeFmt.format(Date(program.startTime))}–${timeFmt.format(Date(program.endTime))}"
                title.text = program.title
                dateCol?.text = rowDateFmt.format(Date(program.startTime))

                itemView.isActivated = isHighlighted
                itemView.isSelected  = isWatching && !isHighlighted
                accentBar?.visibility = if (isWatching) View.VISIBLE else View.INVISIBLE

                // Playing animation — shown when this is the program being watched
                val liveBadge = itemView.findViewById<TextView>(R.id.tvLiveBadge)
                if (isWatching) {
                    playingAnim?.visibility = View.VISIBLE
                    playingAnim?.post {
                        (playingAnim.drawable as? AnimatedVectorDrawable)?.let {
                            if (!it.isRunning) it.start()
                        }
                    }
                    // LIVE badge only when truly watching live broadcast
                    liveBadge?.visibility = if (isTrulyLive) View.VISIBLE else View.GONE
                } else {
                    playingAnim?.visibility = View.GONE
                    (playingAnim?.drawable as? AnimatedVectorDrawable)?.stop()
                    liveBadge?.visibility = View.GONE
                }

                // Text colors
                time.setTextColor(when {
                    isHighlighted || isWatching -> 0xFFE5E7EB.toInt()
                    isPast                      -> 0x8894A3B8.toInt()
                    else                        -> 0x4494A3B8.toInt()
                })
                title.setTextColor(when {
                    isHighlighted -> 0xFFFFFFFF.toInt()
                    isFuture      -> 0x55F1F5F9.toInt()
                    else          -> 0xEEF1F5F9.toInt()
                })
                dateCol?.setTextColor(when {
                    isHighlighted -> 0xAA94A3B8.toInt()
                    isFuture      -> 0x2294A3B8.toInt()
                    else          -> 0x8094A3B8.toInt()
                })

                // Alpha
                itemView.alpha = when {
                    isHighlighted -> 1.0f
                    isWatching    -> 1.0f
                    isPast        -> 0.85f
                    isFuture      -> 0.35f
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



        // Replace updatePrograms():
        fun updatePrograms(newItems: List<ProgramItem>, overrideTimestampMs: Long? = null) {
            items = newItems
            highlightedPos = -1
            watchingTimestampMs = overrideTimestampMs
            notifyDataSetChanged()
        }
    }
}