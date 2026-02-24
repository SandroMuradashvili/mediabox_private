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

    private val categoryButtons = mutableMapOf<String, TextView>()
    private val dateFormat = SimpleDateFormat("EEEE, d MMM yyyy", Locale.getDefault())

    private val scope = CoroutineScope(Dispatchers.Main)

    // Focus sections: CATEGORIES, CHANNELS, PROGRAMS
    private enum class FocusSection { CATEGORIES, CHANNELS, PROGRAMS }
    private var currentFocusSection = FocusSection.CATEGORIES

    init { setupEpg() }

    // -----------------------------------------------------------------------
    // Setup
    // -----------------------------------------------------------------------

    private fun setupEpg() {
        val channelList = binding.root.findViewById<RecyclerView>(R.id.channelList)
        channelAdapter = ChannelAdapter(filteredChannels) { position ->
            selectedChannelIndex = position
            loadProgramsForChannel(position)
        }
        channelList?.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = channelAdapter
        }

        val programList = binding.root.findViewById<RecyclerView>(R.id.programList)
        programAdapter = ProgramAdapter(emptyList()) { program ->
            handleProgramClick(program)
        }
        programList?.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = programAdapter
        }

        setupCategories()
        if (filteredChannels.isNotEmpty()) loadProgramsForChannel(0)
    }

    private fun setupCategories() {
        val container = binding.root.findViewById<LinearLayout>(R.id.categoryButtons)
        container?.removeAllViews()
        categoryButtons.clear()

        repository.getCategories().forEach { category ->
            val btn = TextView(activity).apply {
                text = category
                setTextAppearance(R.style.CategoryButton)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(8, 8, 8, 8) }
                setPadding(40, 20, 40, 20)
                setBackgroundResource(R.drawable.category_button_background)
                isFocusable = true
                isFocusableInTouchMode = true
                setOnClickListener { selectCategory(category) }
                setOnFocusChangeListener { _, hasFocus -> isSelected = hasFocus }
            }
            container?.addView(btn)
            categoryButtons[category] = btn
        }

        selectCategory("All")
    }

    private fun selectCategory(category: String) {
        currentCategory = category
        categoryButtons.forEach { (cat, btn) -> btn.isSelected = cat == category }
        filteredChannels = repository.getChannelsByCategory(category)
        channelAdapter.updateChannels(filteredChannels)
        if (filteredChannels.isNotEmpty()) {
            selectedChannelIndex = 0
            loadProgramsForChannel(0)
        }
    }

    // -----------------------------------------------------------------------
    // Programs
    // -----------------------------------------------------------------------

    private fun loadProgramsForChannel(channelIndex: Int) {
        if (channelIndex < 0 || channelIndex >= filteredChannels.size) return
        val channel = filteredChannels[channelIndex]
        binding.root.findViewById<TextView>(R.id.tvSelectedChannelName)?.text =
            "${channel.name} – TV Programs"

        scope.launch {
            val allPrograms = withContext(Dispatchers.IO) {
                try { ApiService.fetchAllPrograms(channel.apiId).sortedBy { it.startTime } }
                catch (e: Exception) { emptyList() }
            }
            programAdapter.updatePrograms(createProgramListWithDividers(allPrograms))
            scrollToCurrentProgram(allPrograms)
        }
    }

    private fun createProgramListWithDividers(programs: List<Program>): List<ProgramItem> {
        val items = mutableListOf<ProgramItem>()
        var lastDate: String? = null
        programs.forEach { program ->
            val date = dateFormat.format(Date(program.startTime))
            if (date != lastDate) { items.add(ProgramItem.DateDivider(date)); lastDate = date }
            items.add(ProgramItem.ProgramData(program))
        }
        return items
    }

    private fun scrollToCurrentProgram(programs: List<Program>) {
        val currentTime = System.currentTimeMillis()
        val idx = programs.indexOfFirst { it.isCurrentlyPlaying(currentTime) }
        if (idx < 0) return
        var dividers = 0
        for (i in 0 until idx) {
            val d = dateFormat.format(Date(programs[i].startTime))
            val p = if (i > 0) dateFormat.format(Date(programs[i - 1].startTime)) else null
            if (d != p) dividers++
        }
        binding.root.findViewById<RecyclerView>(R.id.programList)
            ?.scrollToPosition(idx + dividers)
    }

    private fun handleProgramClick(program: Program) {
        val currentTime = System.currentTimeMillis()
        val channel = filteredChannels.getOrNull(selectedChannelIndex) ?: return
        val globalIndex = channels.indexOfFirst { it.id == channel.id }
        when {
            program.isCurrentlyPlaying(currentTime) -> { if (globalIndex >= 0) onChannelSelected(globalIndex) }
            program.endTime < currentTime -> onArchiveSelected("ARCHIVE_ID:${channel.id}:TIME:${program.startTime}")
            else -> Toast.makeText(activity, "This program hasn't started yet", Toast.LENGTH_SHORT).show()
        }
    }

    // -----------------------------------------------------------------------
    // Public
    // -----------------------------------------------------------------------

    fun refreshData(newChannels: List<Channel>) {
        channels = newChannels
        setupCategories() // rebuild categories in case Recently Watched changed
        filteredChannels = repository.getChannelsByCategory(currentCategory)
        channelAdapter.updateChannels(filteredChannels)
        if (selectedChannelIndex < filteredChannels.size) loadProgramsForChannel(selectedChannelIndex)
    }

    fun requestFocus() {
        currentFocusSection = FocusSection.CATEGORIES
        categoryButtons.values.firstOrNull()?.requestFocus()
    }

    // -----------------------------------------------------------------------
    // Key handling — fixed navigation
    // -----------------------------------------------------------------------

    fun handleKeyEvent(keyCode: Int): Boolean = when (currentFocusSection) {
        FocusSection.CATEGORIES -> handleCategoryKey(keyCode)
        FocusSection.CHANNELS   -> handleChannelKey(keyCode)
        FocusSection.PROGRAMS   -> handleProgramKey(keyCode)
    }

    private fun handleCategoryKey(keyCode: Int): Boolean {
        val buttons = categoryButtons.values.toList()
        if (buttons.isEmpty()) return false
        val focusedIndex = buttons.indexOfFirst { it.isFocused }
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT  -> { if (focusedIndex > 0) buttons[focusedIndex - 1].requestFocus(); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (focusedIndex in 0 until buttons.lastIndex) buttons[focusedIndex + 1].requestFocus(); true }
            KeyEvent.KEYCODE_DPAD_DOWN  -> {
                currentFocusSection = FocusSection.CHANNELS
                binding.root.findViewById<RecyclerView>(R.id.channelList)?.requestFocus()
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                buttons.getOrNull(focusedIndex)?.performClick(); true
            }
            else -> false
        }
    }

    private fun handleChannelKey(keyCode: Int): Boolean {
        val channelList = binding.root.findViewById<RecyclerView>(R.id.channelList) ?: return false
        val lm = channelList.layoutManager as? LinearLayoutManager ?: return false

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                val firstVisible = lm.findFirstCompletelyVisibleItemPosition()
                if (firstVisible == 0) {
                    // Already at top — go back to categories
                    currentFocusSection = FocusSection.CATEGORIES
                    categoryButtons[currentCategory]?.requestFocus()
                } else {
                    // Scroll up within channel list
                    val prev = (selectedChannelIndex - 1).coerceAtLeast(0)
                    selectedChannelIndex = prev
                    channelAdapter.setSelected(prev)
                    lm.smoothScrollToPosition(channelList, RecyclerView.State(), prev)
                    loadProgramsForChannel(prev)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val next = (selectedChannelIndex + 1).coerceAtMost(filteredChannels.lastIndex)
                selectedChannelIndex = next
                channelAdapter.setSelected(next)
                lm.smoothScrollToPosition(channelList, RecyclerView.State(), next)
                loadProgramsForChannel(next)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                currentFocusSection = FocusSection.PROGRAMS
                binding.root.findViewById<RecyclerView>(R.id.programList)?.requestFocus()
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

    private fun handleProgramKey(keyCode: Int): Boolean {
        val programList = binding.root.findViewById<RecyclerView>(R.id.programList) ?: return false
        val lm = programList.layoutManager as? LinearLayoutManager ?: return false

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                currentFocusSection = FocusSection.CHANNELS
                binding.root.findViewById<RecyclerView>(R.id.channelList)?.requestFocus()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                val firstVisible = lm.findFirstCompletelyVisibleItemPosition()
                if (firstVisible <= 0) {
                    // At top of programs — jump to categories
                    currentFocusSection = FocusSection.CATEGORIES
                    categoryButtons[currentCategory]?.requestFocus()
                }
                // else let RecyclerView handle normal scroll
                false
            }
            else -> false
        }
    }

    // -----------------------------------------------------------------------
    // Sealed items
    // -----------------------------------------------------------------------

    sealed class ProgramItem {
        data class ProgramData(val program: Program) : ProgramItem()
        data class DateDivider(val date: String) : ProgramItem()
    }

    // -----------------------------------------------------------------------
    // Channel Adapter
    // -----------------------------------------------------------------------

    inner class ChannelAdapter(
        private var channels: List<Channel>,
        private val onChannelClick: (Int) -> Unit
    ) : RecyclerView.Adapter<ChannelAdapter.VH>() {

        private var selectedPos = 0

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val logo:   ImageView = itemView.findViewById(R.id.channelLogo)
            val number: TextView  = itemView.findViewById(R.id.tvChannelNumber)
            val name:   TextView  = itemView.findViewById(R.id.tvChannelName)

            init {
                itemView.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) { selectedPos = pos; notifyDataSetChanged(); onChannelClick(pos) }
                }
                itemView.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        val pos = adapterPosition
                        if (pos != RecyclerView.NO_POSITION) { selectedPos = pos; onChannelClick(pos) }
                    }
                }
            }

            fun bind(channel: Channel) {
                number.text = channel.number.toString()
                name.text = channel.name
                if (!channel.logoUrl.isNullOrEmpty()) {
                    Glide.with(activity).load(channel.logoUrl)
                        .placeholder(R.color.surface_light).error(R.color.surface_light).into(logo)
                }
            }
        }

        fun setSelected(pos: Int) { val old = selectedPos; selectedPos = pos; notifyItemChanged(old); notifyItemChanged(pos) }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_epg_channel, parent, false)
        )
        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(channels[position])
        override fun getItemCount() = channels.size
        fun updateChannels(newChannels: List<Channel>) { channels = newChannels; selectedPos = 0; notifyDataSetChanged() }
    }

    // -----------------------------------------------------------------------
    // Program Adapter
    // -----------------------------------------------------------------------

    class ProgramAdapter(
        private var items: List<ProgramItem>,
        private val onProgramClick: (Program) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object { const val TYPE_PROGRAM = 0; const val TYPE_DIVIDER = 1 }

        inner class ProgramVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val time:        TextView = itemView.findViewById(R.id.tvProgramTime)
            val title:       TextView = itemView.findViewById(R.id.tvProgramTitle)
            val description: TextView = itemView.findViewById(R.id.tvProgramDescription)
            val container:   View     = itemView.findViewById(R.id.programContainer)

            init {
                itemView.setOnClickListener {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        (items[pos] as? ProgramItem.ProgramData)?.let { onProgramClick(it.program) }
                    }
                }
            }
        }

        inner class DividerVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val dateLabel: TextView = itemView.findViewById(R.id.tvDateDivider)
        }

        override fun getItemViewType(pos: Int) = when (items[pos]) {
            is ProgramItem.ProgramData -> TYPE_PROGRAM; is ProgramItem.DateDivider -> TYPE_DIVIDER
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
                    val p = item.program
                    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                    holder.time.text = "${fmt.format(Date(p.startTime))} – ${fmt.format(Date(p.endTime))}"
                    holder.title.text = p.title
                    holder.description.text = p.description
                    holder.container.isSelected = p.isCurrentlyPlaying()
                }
                holder is DividerVH && item is ProgramItem.DateDivider -> {
                    holder.dateLabel.text = item.date
                }
            }
        }

        override fun getItemCount() = items.size
        fun updatePrograms(newItems: List<ProgramItem>) { items = newItems; notifyDataSetChanged() }
    }
}