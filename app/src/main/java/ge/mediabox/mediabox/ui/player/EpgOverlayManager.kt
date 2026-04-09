package ge.mediabox.mediabox.ui.player

import android.app.Activity
import android.graphics.drawable.AnimatedVectorDrawable
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
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
import kotlinx.coroutines.Job
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

    // ── Sealed item type ──────────────────────────────────────────────────────

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

    // The timestamp to use for cursor positioning — set from PlayerActivity
    // This is getCurrentAbsoluteTime() at the moment EPG opens (live or archive)
    private var playbackTimestampMs: Long = System.currentTimeMillis()

    private val categoryButtons = mutableListOf<TextView>()

    private val ioJob   = kotlinx.coroutines.SupervisorJob()
    private val mainJob = kotlinx.coroutines.SupervisorJob()
    private val ioScope = CoroutineScope(Dispatchers.IO + ioJob)
    private val scope   = CoroutineScope(Dispatchers.Main + mainJob)

    private enum class FocusSection { CATEGORIES, CHANNELS, PROGRAMS }
    private var focusSection = FocusSection.CATEGORIES

    // Throttling
    private var lastProgramScrollTime = 0L
    private val scrollThrottleMs = 80L
    private var lastMoveTime = 0L
    private val moveThrottleMs = 60L

    // Date formatters
    private var fullDateFmt  = SimpleDateFormat("EEEE, d MMM yyyy", LangPrefs.getLocale(activity))
    private var shortDateFmt = SimpleDateFormat("EEE d MMM",        LangPrefs.getLocale(activity))

    private var lastBuiltCategoryLabels = ""

    // ── Shared RecycledViewPool ───────────────────────────────────────────────
    private val sharedProgramPool = RecyclerView.RecycledViewPool().apply {
        setMaxRecycledViews(0, 30)
        setMaxRecycledViews(1, 6)
    }

    // Lazy view references
    private val programPanel          by lazy { binding.root.findViewById<View>(R.id.programPanel) }
    private val programPlaceholder    by lazy { binding.root.findViewById<View>(R.id.programPlaceholder) }
    private val tvHoveredDate         by lazy { binding.root.findViewById<TextView>(R.id.tvHoveredDate) }
    private val tvPlaceholderTitle    by lazy { binding.root.findViewById<TextView>(R.id.tvPlaceholderTitle) }
    private val tvPlaceholderSubtitle by lazy { binding.root.findViewById<TextView>(R.id.tvPlaceholderSubtitle) }
    private val channelFocusLine      by lazy { binding.root.findViewById<View>(R.id.channelFocusLine) }
    private val programFocusLine      by lazy { binding.root.findViewById<View>(R.id.programFocusLine) }

    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var programAdapter: ProgramAdapter
    private var loadProgramsJob: Job? = null

    init { setupEpg() }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun destroy() {
        loadProgramsJob?.cancel()
        ioJob.cancel()
        mainJob.cancel()
    }

    fun overrideChannelFocus(currentChannelId: Int) {
        val index = filteredChannels.indexOfFirst { it.id == currentChannelId }
        if (index != -1) {
            selectedChannelIndex = index
        }
    }


    // ── Pre-warm ──────────────────────────────────────────────────────────────
    fun preWarmPools() {
        activity.runOnUiThread {
            val rv = binding.root.findViewById<RecyclerView>(R.id.programList) ?: return@runOnUiThread
            try {
                repeat(20) {
                    val vh = programAdapter.createViewHolder(rv, 0)
                    sharedProgramPool.putRecycledView(vh)
                }
                repeat(5) {
                    val vh = programAdapter.createViewHolder(rv, 1)
                    sharedProgramPool.putRecycledView(vh)
                }
            } catch (e: Exception) {
                android.util.Log.e("EPG_OPTIMIZE", "Prewarm failed: ${e.message}")
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
        }

        programAdapter = ProgramAdapter(activity, emptyList()) { handleProgramClick(it) }
        binding.root.findViewById<RecyclerView>(R.id.programList)?.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = programAdapter
            setRecycledViewPool(sharedProgramPool)
            isFocusable = false
            isFocusableInTouchMode = false
            setHasFixedSize(false)
            setItemViewCacheSize(40)
            itemAnimator = null
        }

        setupCategories()
        hideProgramPanel()
    }

    private fun setupCategories() {
        val isKa = LangPrefs.isKa(activity)
        val categories = repository.getCategories(isKa)

        val newLabels = categories.joinToString("|")
        if (newLabels == lastBuiltCategoryLabels) {
            highlightCategory()
            return
        }
        lastBuiltCategoryLabels = newLabels

        val container  = binding.root.findViewById<LinearLayout>(R.id.categoryButtons)
        val scrollView = binding.root.findViewById<HorizontalScrollView>(R.id.categoryScrollView)

        scrollView?.isFocusable = false
        scrollView?.isFocusableInTouchMode = false
        scrollView?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

        container?.removeAllViews()
        categoryButtons.clear()

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
        scrollChannelListTo(0)
        hideProgramPanel()
    }

    private fun scrollCategoryIntoView(index: Int) {
        val scrollView = binding.root.findViewById<HorizontalScrollView>(R.id.categoryScrollView) ?: return
        val btn = categoryButtons.getOrNull(index) ?: return
        scrollView.post {
            val btnLeft  = btn.left
            val btnRight = btn.right
            val scrollX  = scrollView.scrollX
            val width    = scrollView.width
            when {
                btnLeft  < scrollX         -> scrollView.smoothScrollTo(btnLeft - 20, 0)
                btnRight > scrollX + width -> scrollView.smoothScrollTo(btnRight - width + 20, 0)
            }
        }
    }

    private fun updateFocusIndicator() {
        when (focusSection) {
            FocusSection.CATEGORIES, FocusSection.CHANNELS -> {
                channelFocusLine?.visibility = View.VISIBLE
                programFocusLine?.visibility = View.INVISIBLE
            }
            FocusSection.PROGRAMS -> {
                channelFocusLine?.visibility = View.INVISIBLE
                programFocusLine?.visibility = View.VISIBLE
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
        updateFocusIndicator()
    }

    private fun hideProgramPanel() {
        android.util.Log.e("EPG_BUG", "--> hideProgramPanel() WAS CALLED!")
        programPanel?.visibility       = View.GONE
        programPlaceholder?.visibility = View.GONE
        tvHoveredDate?.visibility      = View.GONE
    }

    /**
     * Show default placeholder with "press right to view programs" hint.
     * Called when highlight moves to a new channel but user hasn't pressed right yet.
     */
    private fun showDefaultPlaceholder() {
        android.util.Log.e("EPG_BUG", "--> showDefaultPlaceholder() WAS CALLED!")
        val isKa = LangPrefs.isKa(activity)
        programPanel?.visibility       = View.GONE
        tvHoveredDate?.visibility      = View.GONE
        programPlaceholder?.visibility = View.VISIBLE
        tvPlaceholderTitle?.text       = filteredChannels.getOrNull(selectedChannelIndex)?.name ?: ""
        tvPlaceholderTitle?.setTextColor(0xCCF1F5F9.toInt())
        tvPlaceholderSubtitle?.text    = if (isKa) "→ პროგრამების სანახავად" else "→ Press right to view programs"
        tvPlaceholderSubtitle?.setTextColor(0x8894A3B8.toInt())
    }

    private fun showLockedChannelInfo(channel: Channel) {
        programPanel?.visibility       = View.GONE
        tvHoveredDate?.visibility      = View.GONE
        programPlaceholder?.visibility = View.VISIBLE
        tvPlaceholderTitle?.text       = channel.name
        tvPlaceholderTitle?.setTextColor(0xCCF1F5F9.toInt())
        tvPlaceholderSubtitle?.text    =
            if (LangPrefs.isKa(activity)) "არ შედის თქვენს პაკეტში"
            else "Not included in your subscription"
        tvPlaceholderSubtitle?.setTextColor(0xBBF87171.toInt())
    }

    /**
     * Load programs for the given channel and render them.
     * This is called ONLY when the user explicitly presses right on a channel.
     * Uses cache if available (4h duration), otherwise fetches from network.
     */
    private fun loadAndShowPrograms(channelIndex: Int) {
        loadProgramsJob?.cancel()

        val channel = filteredChannels.getOrNull(channelIndex) ?: return
        if (channel.isLocked) { showLockedChannelInfo(channel); return }

        val cached = channel.programs
        if (cached.isNotEmpty()) {
            // Cached — render immediately, focusSection already set to PROGRAMS by caller
            loadProgramsJob = scope.launch {
                renderPrograms(channel, cached, playbackTimestampMs)
            }
        } else {
            // Not cached — show loading state but keep focusSection in CHANNELS until
            // programs actually load successfully. This prevents the cursor getting
            // stuck in an empty programs column.
            focusSection = FocusSection.CHANNELS
            updateFocusIndicator()

            val isKa = LangPrefs.isKa(activity)
            programPanel?.visibility       = View.GONE
            tvHoveredDate?.visibility      = View.GONE
            programPlaceholder?.visibility = View.VISIBLE
            tvPlaceholderTitle?.text       = channel.name
            tvPlaceholderTitle?.setTextColor(0xCCF1F5F9.toInt())
            tvPlaceholderSubtitle?.text    = if (isKa) "იტვირთება..." else "Loading..."
            tvPlaceholderSubtitle?.setTextColor(0x8894A3B8.toInt())

            loadProgramsJob = scope.launch {
                val programs = withContext(Dispatchers.IO) {
                    ChannelRepository.getProgramsForChannel(channel.id)
                }
                if (selectedChannelIndex != channelIndex) return@launch // user moved away

                if (programs.isNotEmpty()) {
                    // Success — now move focus into programs column
                    focusSection = FocusSection.PROGRAMS
                    updateFocusIndicator()
                    renderPrograms(channel, programs, playbackTimestampMs)
                } else {
                    android.util.Log.e("EPG_BUG", "--> API RETURNED EMPTY PROGRAMS! Showing error text.")
                    // Failed — stay on channels side, show message
                    val msg = if (LangPrefs.isKa(activity)) "პროგრამები მიუწვდომელია"
                    else "No programs available"
                    tvPlaceholderSubtitle?.text = msg
                    tvPlaceholderSubtitle?.setTextColor(0xBBF87171.toInt())
                }
            }
        }
    }

    private suspend fun renderPrograms(
        channel: Channel,
        programs: List<Program>,
        anchorTimestampMs: Long
    ) {
        val items = buildProgramItemList(programs)
        currentProgramItems = items

        var targetPos = findProgramPositionForTime(programs, anchorTimestampMs)
        while (targetPos < items.size && items[targetPos] is ProgramItem.DateDivider) targetPos++
        selectedProgramIndex = targetPos

        val rv = binding.root.findViewById<RecyclerView>(R.id.programList) ?: return

        withContext(Dispatchers.Main) {
            programAdapter.updatePrograms(items, anchorTimestampMs)
            rv.post {
                (rv.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(targetPos, 0)
                binding.root.findViewById<TextView>(R.id.tvSelectedChannelName)?.text = channel.name
                programPanel?.visibility       = View.VISIBLE
                programPlaceholder?.visibility = View.GONE
                programAdapter.setHighlight(targetPos)
                updateHoveredDate(targetPos)
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

    private fun findProgramPositionForTime(programs: List<Program>, timestampMs: Long): Int {
        if (programs.isEmpty()) return 0
        val targetIndex = programs.indexOfFirst { timestampMs in it.startTime until it.endTime }
        val idx = if (targetIndex >= 0) targetIndex else
            programs.indexOfLast { it.endTime <= timestampMs }.coerceAtLeast(0)
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
            is ProgramItem.DateDivider ->
                (currentProgramItems.getOrNull(position + 1) as? ProgramItem.ProgramData)
                    ?.let { shortDateFmt.format(Date(it.program.startTime)) } ?: item.date
            null -> null
        }
        tv.text       = dateStr ?: ""
        tv.visibility = if (dateStr != null) View.VISIBLE else View.GONE
    }

    private fun handleProgramClick(program: Program) {
        android.util.Log.e("EPG_BUG", "--> handleProgramClick fired for: ${program.title}")
        val channel     = filteredChannels.getOrNull(selectedChannelIndex) ?: return
        val globalIndex = channels.indexOfFirst { it.id == channel.id }
        val now         = System.currentTimeMillis()
        when {
            program.isCurrentlyPlaying(now) -> if (globalIndex >= 0) onChannelSelected(globalIndex)
            program.endTime < now           -> onArchiveSelected("ARCHIVE_ID:${channel.id}:TIME:${program.startTime}")
            else -> Toast.makeText(
                activity,
                if (LangPrefs.isKa(activity)) "ეს პროგრამა ჯერ არ დაწყებულა"
                else "This program hasn't started yet",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ── Public interface ──────────────────────────────────────────────────────

    fun refreshData(newChannels: List<Channel>) {
        channels = newChannels
        val isKa   = LangPrefs.isKa(activity)
        val locale = LangPrefs.getLocale(activity)
        fullDateFmt  = SimpleDateFormat("EEEE, d MMM yyyy", locale)
        shortDateFmt = SimpleDateFormat("EEE d MMM",        locale)
        programAdapter.updateLocale(locale)
        repository.refreshLocalization(isKa)
        setupCategories()
        filteredChannels = repository.getChannelsByCategory(currentCategory, isKa)
        channelAdapter.updateChannels(filteredChannels)
        selectedChannelIndex = 0
        hideProgramPanel()
    }

    /**
     * Called when EPG opens. Sets the playback timestamp for correct cursor positioning,
     * scrolls channel list to the current channel, and triggers first-10 prefetch.
     *
     * @param currentChannelId  The ID of the channel currently playing
     * @param currentTimestampMs The EXACT playback time — live = now, archive = archiveBase + playerPos
     */
    fun requestFocus(currentChannelId: Int, currentTimestampMs: Long = System.currentTimeMillis()) {
        // Store the playback timestamp — used for cursor in renderPrograms
        playbackTimestampMs = currentTimestampMs

        val index = filteredChannels.indexOfFirst { it.id == currentChannelId }

        if (index != -1) {
            focusSection         = FocusSection.CHANNELS
            selectedChannelIndex = index
            channelAdapter.setHighlight(selectedChannelIndex)
            updateFocusIndicator()

            val rv = binding.root.findViewById<RecyclerView>(R.id.channelList)
            (rv?.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(selectedChannelIndex, 150)

            val channel = filteredChannels[index]
            if (channel.isLocked) {
                showLockedChannelInfo(channel)
            } else if (channel.programs.isNotEmpty()) {
                // Already cached — jump straight into programs column with correct cursor
                focusSection = FocusSection.PROGRAMS
                updateFocusIndicator()
                loadProgramsJob?.cancel()
                loadProgramsJob = scope.launch {
                    renderPrograms(channel, channel.programs, playbackTimestampMs)
                }
            } else {
                // Not cached yet — show placeholder with "press right" hint
                showDefaultPlaceholder()
            }
        } else {
            focusSection = FocusSection.CATEGORIES
            highlightCategory()
            updateFocusIndicator()
        }

        // Silently prefetch first 10 channels in background (respects cache)
        ioScope.launch { repository.prefetchFirstTenEpg() }
    }

    // ── Key handling ──────────────────────────────────────────────────────────

    fun handleKeyEvent(keyCode: Int): Boolean {
        android.util.Log.e("EPG_BUG", "--> KEY PRESSED IN EPG: $keyCode")
        return when (focusSection) {
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
                updateFocusIndicator()
                // Show placeholder — don't auto-load
                val ch = filteredChannels.getOrNull(selectedChannelIndex)
                if (ch?.isLocked == true) showLockedChannelInfo(ch) else showDefaultPlaceholder()
            }
            true
        }
        else -> false
    }

    private fun handleChannelKeys(keyCode: Int): Boolean {
        val channelList = binding.root.findViewById<RecyclerView>(R.id.channelList) ?: return false

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val now = System.currentTimeMillis()
                if (now - lastMoveTime < moveThrottleMs) return true
                lastMoveTime = now

                val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                val newIndex = selectedChannelIndex + direction

                if (newIndex in filteredChannels.indices) {
                    selectedChannelIndex = newIndex
                    channelAdapter.setHighlight(selectedChannelIndex)
                    (channelList.layoutManager as LinearLayoutManager)
                        .scrollToPositionWithOffset(selectedChannelIndex, 0)

                    // Cancel any in-progress program load, hide panel, show placeholder
                    loadProgramsJob?.cancel()
                    hideProgramPanel()
                    val ch = filteredChannels.getOrNull(selectedChannelIndex)
                    if (ch?.isLocked == true) showLockedChannelInfo(ch) else showDefaultPlaceholder()

                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && selectedChannelIndex == 0) {
                    focusSection = FocusSection.CATEGORIES
                    channelAdapter.setHighlight(-1)
                    highlightCategory()
                    updateFocusIndicator()
                    loadProgramsJob?.cancel()
                    hideProgramPanel()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                focusSection = FocusSection.CATEGORIES
                channelAdapter.setHighlight(-1)
                highlightCategory()
                updateFocusIndicator()
                hideProgramPanel()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val ch = filteredChannels.getOrNull(selectedChannelIndex)
                if (ch?.isLocked == true) {
                    showLockedChannelInfo(ch)
                } else {
                    // If already cached, move to programs immediately.
                    // If not cached, loadAndShowPrograms will move focus only after successful load.
                    if (ch != null && ch.programs.isNotEmpty()) {
                        focusSection = FocusSection.PROGRAMS
                        updateFocusIndicator()
                    }
                    loadAndShowPrograms(selectedChannelIndex)
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

    fun saveState(prefs: android.content.SharedPreferences) {
        val sectionToSave = if (focusSection == FocusSection.PROGRAMS)
            FocusSection.CHANNELS else focusSection
        prefs.edit()
            .putInt("epg_category_index", selectedCategoryIndex)
            .putInt("epg_channel_index", selectedChannelIndex)
            .putInt("epg_program_index", selectedProgramIndex)
            .putString("epg_category", currentCategory)
            .putString("epg_focus_section", sectionToSave.name)
            .apply()
    }

    fun restoreState(prefs: android.content.SharedPreferences) {
        val savedCategory = prefs.getString("epg_category", "") ?: ""
        val isKa = LangPrefs.isKa(activity)
        val categories = repository.getCategories(isKa)

        if (savedCategory.isNotEmpty() && categories.contains(savedCategory)) {
            selectedCategoryIndex = prefs.getInt("epg_category_index", 0)
            currentCategory = savedCategory
            filteredChannels = repository.getChannelsByCategory(currentCategory, isKa)
            channelAdapter.updateChannels(filteredChannels)
        }

        val savedChannelIndex = prefs.getInt("epg_channel_index", 0)
        selectedChannelIndex = savedChannelIndex.coerceIn(0, (filteredChannels.size - 1).coerceAtLeast(0))
        selectedProgramIndex = prefs.getInt("epg_program_index", 0)

        val sectionName = prefs.getString("epg_focus_section", FocusSection.CHANNELS.name)
        val restored = try {
            FocusSection.valueOf(sectionName ?: FocusSection.CHANNELS.name)
        } catch (e: Exception) {
            FocusSection.CHANNELS
        }
        focusSection = if (restored == FocusSection.PROGRAMS) FocusSection.CHANNELS else restored
    }

    fun requestFocusOnRestored(currentTimestampMs: Long = System.currentTimeMillis()) {
        // Update playback timestamp so cursor lands on correct program
        playbackTimestampMs = currentTimestampMs

        channelAdapter.setHighlight(selectedChannelIndex)
        highlightCategory()
        updateFocusIndicator()

        val rv = binding.root.findViewById<RecyclerView>(R.id.channelList)
        (rv?.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(selectedChannelIndex, 0)

        val channel = filteredChannels.getOrNull(selectedChannelIndex)
        if (channel == null || channel.isLocked) {
            if (channel != null) showLockedChannelInfo(channel)
            return
        }

        if (channel.programs.isNotEmpty()) {
            // Cached — auto-enter programs column with correct cursor
            focusSection = FocusSection.PROGRAMS
            updateFocusIndicator()
            loadProgramsJob?.cancel()
            loadProgramsJob = scope.launch {
                renderPrograms(channel, channel.programs, playbackTimestampMs)
            }
        } else {
            // Not cached — show placeholder
            showDefaultPlaceholder()
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
                updateFocusIndicator()
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
            val logo:             ImageView = itemView.findViewById(R.id.channelLogo)
            val number:           TextView  = itemView.findViewById(R.id.tvChannelNumber)
            val name:             TextView  = itemView.findViewById(R.id.tvChannelName)
            val favoriteIcon:     ImageView = itemView.findViewById(R.id.ivFavorite)
            val selectionOutline: View      = itemView.findViewById(R.id.selectionOutline)

            init {
                // CRITICAL FIX: Kill native focus so Android doesn't hijack the OK button
                itemView.isFocusable = false
                itemView.isClickable = false
                itemView.isFocusableInTouchMode = false

                // DELETED the setOnClickListener entirely.
                // We handle DPAD_CENTER manually in handleChannelKeys now.
            }

            fun bind(channel: Channel, isHighlighted: Boolean) {
                number.text = channel.number.toString()
                name.text   = channel.name
                selectionOutline.visibility = if (isHighlighted) View.VISIBLE else View.GONE

                if (!channel.logoUrl.isNullOrEmpty()) {
                    Glide.with(activity).load(channel.logoUrl)
                        .placeholder(R.color.surface_light)
                        .error(R.color.surface_light)
                        .into(logo)
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
            if (old == pos) return
            highlightedPos = pos
            if (old != -1 && old < itemCount) notifyItemChanged(old)
            if (pos != -1 && pos < itemCount) notifyItemChanged(pos)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_epg_channel, parent, false)
        )

        override fun onBindViewHolder(holder: VH, position: Int) {
            if (position in list.indices) holder.bind(list[position], position == highlightedPos)
        }

        override fun getItemCount() = list.size

        fun updateChannels(newChannels: List<Channel>) {
            val old = list
            list = newChannels
            highlightedPos = -1
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = newChannels.size
                override fun areItemsTheSame(o: Int, n: Int) = old[o].id == newChannels[n].id
                override fun areContentsTheSame(o: Int, n: Int): Boolean {
                    val a = old[o]; val b = newChannels[n]
                    return a.name == b.name && a.isFavorite == b.isFavorite && a.isLocked == b.isLocked
                }
            }, false)
            diff.dispatchUpdatesTo(this)
        }
    }

    // ── Program Adapter ───────────────────────────────────────────────────────

    class ProgramAdapter(
        private val activity: Activity,
        private var items: List<ProgramItem>,
        private val onProgramClick: (Program) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            const val TYPE_PROGRAM = 0
            const val TYPE_DIVIDER = 1
        }

        private var highlightedPos      = -1
        private var watchingTimestampMs: Long? = null
        private var rowDateFmt = SimpleDateFormat("d MMM", LangPrefs.getLocale(activity))
        private var timeFmt    = SimpleDateFormat("HH:mm", LangPrefs.getLocale(activity))

        fun updateLocale(locale: Locale) {
            rowDateFmt = SimpleDateFormat("d MMM", locale)
            timeFmt    = SimpleDateFormat("HH:mm", locale)
            notifyDataSetChanged()
        }

        fun setHighlight(pos: Int) {
            val old = highlightedPos
            if (old == pos) return
            highlightedPos = pos
            if (old != -1 && old < itemCount) notifyItemChanged(old)
            if (pos != -1 && pos < itemCount) notifyItemChanged(pos)
        }

        fun updatePrograms(newItems: List<ProgramItem>, overrideTimestampMs: Long? = null) {
            android.util.Log.e("EPG_BUG", "--> ProgramAdapter UPDATED with ${newItems.size} items")
            val oldItems = items
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = oldItems.size
                override fun getNewListSize() = newItems.size
                override fun areItemsTheSame(o: Int, n: Int): Boolean {
                    val oi = oldItems[o]; val ni = newItems[n]
                    return if (oi is ProgramItem.ProgramData && ni is ProgramItem.ProgramData) {
                        oi.program.id == ni.program.id
                    } else if (oi is ProgramItem.DateDivider && ni is ProgramItem.DateDivider) {
                        oi.date == ni.date
                    } else false
                }
                override fun areContentsTheSame(o: Int, n: Int) = oldItems[o] == newItems[n]
            }, false)

            items = newItems
            highlightedPos = -1
            watchingTimestampMs = overrideTimestampMs
            diff.dispatchUpdatesTo(this)
        }

        inner class ProgramVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val time:        TextView   = itemView.findViewById(R.id.tvProgramTime)
            val title:       TextView   = itemView.findViewById(R.id.tvProgramTitle)
            val accentBar:   View?      = itemView.findViewById(R.id.programAccentBar)
            val playingAnim: ImageView? = itemView.findViewById(R.id.ivPlayingAnim)
            val dateCol:     TextView?  = itemView.findViewById(R.id.tvProgramDate)

            init {
                // CRITICAL FIX: Kill native focus here too
                itemView.isFocusable = false
                itemView.isClickable = false
                itemView.isFocusableInTouchMode = false
            }

            fun bind(program: Program, isHighlighted: Boolean) {

                itemView.isActivated = false
                itemView.isSelected = false

                val now      = System.currentTimeMillis()
                val anchorMs = watchingTimestampMs ?: now

                val isWatching  = anchorMs in program.startTime until program.endTime
                val isPast      = program.endTime < now

                time.text     = "${timeFmt.format(Date(program.startTime))}–${timeFmt.format(Date(program.endTime))}"
                title.text    = program.title
                dateCol?.text = rowDateFmt.format(Date(program.startTime))

                itemView.isActivated  = isHighlighted
                itemView.isSelected   = isWatching && !isHighlighted
                accentBar?.visibility = if (isWatching) View.VISIBLE else View.INVISIBLE

                if (isWatching) {
                    playingAnim?.visibility = View.VISIBLE
                    (playingAnim?.drawable as? AnimatedVectorDrawable)?.let { if (!it.isRunning) it.start() }
                } else {
                    playingAnim?.visibility = View.GONE
                    (playingAnim?.drawable as? AnimatedVectorDrawable)?.stop()
                }

                time.setTextColor(when {
                    isHighlighted || isWatching -> 0xFFE5E7EB.toInt()
                    isPast                      -> 0x8894A3B8.toInt()
                    else                        -> 0x4494A3B8.toInt()
                })
                itemView.alpha = when {
                    isHighlighted || isWatching -> 1.0f
                    isPast                      -> 0.85f
                    else                        -> 0.35f
                }
            }
        }

        inner class DividerVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val dateLabel: TextView = itemView.findViewById(R.id.tvDateDivider)
        }

        override fun getItemViewType(pos: Int) = if (items[pos] is ProgramItem.ProgramData) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) ProgramVH(inflater.inflate(R.layout.item_epg_program, parent, false))
            else DividerVH(inflater.inflate(R.layout.item_epg_date_divider, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            if (holder is ProgramVH && item is ProgramItem.ProgramData) holder.bind(item.program, position == highlightedPos)
            else if (holder is DividerVH && item is ProgramItem.DateDivider) holder.dateLabel.text = item.date
        }

        override fun getItemCount() = items.size
    }
}