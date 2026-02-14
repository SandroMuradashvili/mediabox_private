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

    // Use a coroutine scope tied to the activity lifecycle
    private val scope = CoroutineScope(Dispatchers.Main)

    // Track which section has focus
    private enum class FocusSection { CATEGORIES, CHANNELS, PROGRAMS }
    private var currentFocusSection: FocusSection = FocusSection.CATEGORIES

    init {
        setupEpg()
    }

    private fun setupEpg() {
        // Initialize Channel Adapter
        val channelList = binding.root.findViewById<RecyclerView>(R.id.channelList)
        channelAdapter = ChannelAdapter(filteredChannels) { position ->
            selectedChannelIndex = position
            loadProgramsForChannel(position)
        }
        channelList?.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = channelAdapter
        }

        // Initialize Program Adapter
        val programList = binding.root.findViewById<RecyclerView>(R.id.programList)
        programAdapter = ProgramAdapter(emptyList()) { program ->
            handleProgramClick(program)
        }
        programList?.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = programAdapter
        }

        // Setup categories
        setupCategories()

        if (filteredChannels.isNotEmpty()) {
            loadProgramsForChannel(0)
        }
    }

    private fun setupCategories() {
        val categoryContainer = binding.root.findViewById<LinearLayout>(R.id.categoryButtons)
        categoryContainer?.removeAllViews()
        categoryButtons.clear()

        val categories = repository.getCategories()

        categories.forEach { category ->
            val button = TextView(activity).apply {
                text = category
                setTextAppearance(R.style.CategoryButton)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(8, 8, 8, 8)
                }
                setPadding(40, 20, 40, 20)
                setBackgroundResource(R.drawable.category_button_background)
                isFocusable = true
                isFocusableInTouchMode = true

                setOnClickListener { selectCategory(category) }
                setOnFocusChangeListener { _, hasFocus -> isSelected = hasFocus }
            }

            categoryContainer?.addView(button)
            categoryButtons[category] = button
        }

        selectCategory("All")
    }

    private fun selectCategory(category: String) {
        currentCategory = category
        categoryButtons.forEach { (cat, button) -> button.isSelected = cat == category }

        filteredChannels = repository.getChannelsByCategory(category)
        channelAdapter.updateChannels(filteredChannels)

        if (filteredChannels.isNotEmpty()) {
            selectedChannelIndex = 0
            loadProgramsForChannel(0)
        }
    }

    private fun loadProgramsForChannel(channelIndex: Int) {
        if (channelIndex < 0 || channelIndex >= filteredChannels.size) return

        val channel = filteredChannels[channelIndex]

        val channelNameHeader = binding.root.findViewById<TextView>(R.id.tvSelectedChannelName)
        channelNameHeader?.text = "${channel.name} - TV Programs"

        // Fetch all rewindable programs using new API endpoint
        scope.launch {
            val allPrograms = fetchAllProgramsForChannel(channel.apiId)

            // Group by date and add dividers
            val programsWithDividers = createProgramListWithDividers(allPrograms)

            programAdapter.updatePrograms(programsWithDividers)

            // Auto-scroll to currently playing program
            scrollToCurrentProgram(allPrograms)
        }
    }

    private suspend fun fetchAllProgramsForChannel(channelApiId: String): List<Program> = withContext(Dispatchers.IO) {
        try {
            // Use new /programs/all endpoint
            val programs = ApiService.fetchAllPrograms(channelApiId)
            // Sort by start time
            programs.sortedBy { it.startTime }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun createProgramListWithDividers(programs: List<Program>): List<ProgramItem> {
        val items = mutableListOf<ProgramItem>()
        var lastDate: String? = null

        programs.forEach { program ->
            val programDate = dateFormat.format(Date(program.startTime))

            // Add date divider if day changed
            if (programDate != lastDate) {
                items.add(ProgramItem.DateDivider(programDate))
                lastDate = programDate
            }

            items.add(ProgramItem.ProgramData(program))
        }

        return items
    }

    private fun scrollToCurrentProgram(programs: List<Program>) {
        val currentTime = System.currentTimeMillis()
        val currentProgramIndex = programs.indexOfFirst { it.isCurrentlyPlaying(currentTime) }

        if (currentProgramIndex >= 0) {
            // Count dividers before this program
            var dividersBeforeIndex = 0

            for (i in 0 until currentProgramIndex) {
                val programDate = dateFormat.format(Date(programs[i].startTime))
                val prevDate = if (i > 0) dateFormat.format(Date(programs[i - 1].startTime)) else null
                if (programDate != prevDate) {
                    dividersBeforeIndex++
                }
            }

            val scrollPosition = currentProgramIndex + dividersBeforeIndex

            val programList = binding.root.findViewById<RecyclerView>(R.id.programList)
            programList?.scrollToPosition(scrollPosition)
        }
    }

    private fun handleProgramClick(program: Program) {
        val currentTime = System.currentTimeMillis()
        val channel = filteredChannels.getOrNull(selectedChannelIndex) ?: return
        val globalIndex = channels.indexOfFirst { it.id == channel.id }

        when {
            program.isCurrentlyPlaying(currentTime) -> {
                // Currently playing: switch to channel
                if (globalIndex >= 0) {
                    onChannelSelected(globalIndex)
                }
            }
            program.endTime < currentTime -> {
                // Past program: play archive
                onArchiveSelected("ARCHIVE_ID:${channel.id}:TIME:${program.startTime}")
            }
            else -> {
                // Future program
                Toast.makeText(activity, "This program hasn't started yet", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun refreshData(newChannels: List<Channel>) {
        channels = newChannels
        filteredChannels = repository.getChannelsByCategory(currentCategory)
        channelAdapter.updateChannels(filteredChannels)

        if (selectedChannelIndex < filteredChannels.size) {
            loadProgramsForChannel(selectedChannelIndex)
        }
    }

    fun requestFocus() {
        currentFocusSection = FocusSection.CATEGORIES
        categoryButtons.values.firstOrNull()?.requestFocus()
    }

    fun handleKeyEvent(keyCode: Int): Boolean {
        return when (currentFocusSection) {
            FocusSection.CATEGORIES -> handleCategoryKeyEvent(keyCode)
            FocusSection.CHANNELS -> handleChannelKeyEvent(keyCode)
            FocusSection.PROGRAMS -> handleProgramKeyEvent(keyCode)
        }
    }

    private fun handleCategoryKeyEvent(keyCode: Int): Boolean {
        val buttons = categoryButtons.values.toList()
        if (buttons.isEmpty()) return false

        val focusedIndex = buttons.indexOfFirst { it.isFocused }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (focusedIndex > 0) buttons[focusedIndex - 1].requestFocus()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (focusedIndex in buttons.indices && focusedIndex < buttons.lastIndex) {
                    buttons[focusedIndex + 1].requestFocus()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val channelList = binding.root.findViewById<RecyclerView>(R.id.channelList)
                currentFocusSection = FocusSection.CHANNELS
                channelList?.requestFocus()
                true
            }
            else -> false
        }
    }

    private fun handleChannelKeyEvent(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                val buttons = categoryButtons.values.toList()
                if (buttons.isNotEmpty()) {
                    currentFocusSection = FocusSection.CATEGORIES
                    categoryButtons[currentCategory]?.requestFocus()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val programList = binding.root.findViewById<RecyclerView>(R.id.programList)
                currentFocusSection = FocusSection.PROGRAMS
                programList?.requestFocus()
                true
            }
            else -> false
        }
    }

    private fun handleProgramKeyEvent(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val channelList = binding.root.findViewById<RecyclerView>(R.id.channelList)
                currentFocusSection = FocusSection.CHANNELS
                channelList?.requestFocus()
                true
            }
            else -> false
        }
    }

    // Sealed class for program list items (programs + date dividers)
    sealed class ProgramItem {
        data class ProgramData(val program: Program) : ProgramItem()
        data class DateDivider(val date: String) : ProgramItem()
    }

    // Channel Adapter
    inner class ChannelAdapter(
        private var channels: List<Channel>,
        private val onChannelClick: (Int) -> Unit
    ) : RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder>() {

        private var selectedPosition = 0

        inner class ChannelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val channelLogo: ImageView = itemView.findViewById(R.id.channelLogo)
            val channelNumber: TextView = itemView.findViewById(R.id.tvChannelNumber)
            val channelName: TextView = itemView.findViewById(R.id.tvChannelName)

            init {
                itemView.setOnClickListener {
                    val position = adapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        selectedPosition = position
                        notifyDataSetChanged()
                        onChannelClick(position)
                    }
                }

                itemView.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        val position = adapterPosition
                        if (position != RecyclerView.NO_POSITION) {
                            selectedPosition = position
                            onChannelClick(position)
                        }
                    }
                }
            }

            fun bind(channel: Channel) {
                channelNumber.text = channel.number.toString()
                channelName.text = channel.name

                if (!channel.logoUrl.isNullOrEmpty()) {
                    Glide.with(activity)
                        .load(channel.logoUrl)
                        .placeholder(R.color.surface_light)
                        .error(R.color.surface_light)
                        .into(channelLogo)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_epg_channel, parent, false)
            return ChannelViewHolder(view)
        }

        override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
            holder.bind(channels[position])
        }

        override fun getItemCount() = channels.size

        fun updateChannels(newChannels: List<Channel>) {
            channels = newChannels
            selectedPosition = 0
            notifyDataSetChanged()
        }
    }

    // Program Adapter with Date Dividers (removed extra 'inner' keyword)
    class ProgramAdapter(
        private var items: List<ProgramItem>,
        private val onProgramClick: (Program) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            const val TYPE_PROGRAM = 0
            const val TYPE_DATE_DIVIDER = 1
        }

        inner class ProgramViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val programTime: TextView = itemView.findViewById(R.id.tvProgramTime)
            val programTitle: TextView = itemView.findViewById(R.id.tvProgramTitle)
            val programDescription: TextView = itemView.findViewById(R.id.tvProgramDescription)
            val container: View = itemView.findViewById(R.id.programContainer)

            init {
                itemView.setOnClickListener {
                    val position = adapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        val item = items[position]
                        if (item is ProgramItem.ProgramData) {
                            onProgramClick(item.program)
                        }
                    }
                }
            }
        }

        inner class DateDividerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val dateDivider: TextView = itemView.findViewById(R.id.tvDateDivider)
        }

        override fun getItemViewType(position: Int): Int {
            return when (items[position]) {
                is ProgramItem.ProgramData -> TYPE_PROGRAM
                is ProgramItem.DateDivider -> TYPE_DATE_DIVIDER
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                TYPE_PROGRAM -> {
                    val view = LayoutInflater.from(parent.context)
                        .inflate(R.layout.item_epg_program, parent, false)
                    ProgramViewHolder(view)
                }
                else -> {
                    val view = LayoutInflater.from(parent.context)
                        .inflate(R.layout.item_epg_date_divider, parent, false)
                    DateDividerViewHolder(view)
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]

            when (holder) {
                is ProgramViewHolder -> {
                    val programItem = item as ProgramItem.ProgramData
                    val program = programItem.program
                    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

                    holder.programTime.text = "${timeFormat.format(Date(program.startTime))} - ${timeFormat.format(Date(program.endTime))}"
                    holder.programTitle.text = program.title
                    holder.programDescription.text = program.description

                    // Highlight currently playing
                    holder.container.isSelected = program.isCurrentlyPlaying()
                }
                is DateDividerViewHolder -> {
                    val dividerItem = item as ProgramItem.DateDivider
                    holder.dateDivider.text = dividerItem.date
                }
            }
        }

        override fun getItemCount() = items.size

        fun updatePrograms(newItems: List<ProgramItem>) {
            items = newItems
            notifyDataSetChanged()
        }
    }
}