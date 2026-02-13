package ge.mediabox.mediabox.ui.player

import android.app.Activity
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ge.mediabox.mediabox.R
import ge.mediabox.mediabox.data.model.Channel
import ge.mediabox.mediabox.data.model.Program
import ge.mediabox.mediabox.data.repository.ChannelRepository
import ge.mediabox.mediabox.databinding.ActivityPlayerBinding
import ge.mediabox.mediabox.ui.player.adapter.ChannelAdapter
import ge.mediabox.mediabox.ui.player.adapter.ProgramAdapter

class EpgOverlayManager(
    private val activity: Activity,
    private val binding: ActivityPlayerBinding,
    private var channels: List<Channel>,
    private val onChannelSelected: (Int) -> Unit,
    private val onArchiveSelected: (String) -> Unit // Added Callback for Archive/Catch-up
) {

    private val repository = ChannelRepository
    private var currentCategory = "All"
    private var filteredChannels: List<Channel> = channels
    private var selectedChannelIndex = 0

    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var programAdapter: ProgramAdapter

    private val categoryButtons = mutableMapOf<String, TextView>()

    // Track which EPG section currently has focus
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
            updateProgramList(position)
            // Note: We don't switch channels immediately on focus/click in list
            // User must press CENTER/ENTER on the channel to switch (handled in handleChannelKeyEvent)
        }
        channelList?.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = channelAdapter
            // Enable smooth scrolling for TV remote navigation
            isFocusable = true
            isFocusableInTouchMode = true
        }

        // Initialize Program Adapter with Click Logic
        val programList = binding.root.findViewById<RecyclerView>(R.id.programList)
        programAdapter = ProgramAdapter(emptyList()) { program ->
            handleProgramClick(program)
        }

        programList?.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = programAdapter
        }

        // Now setup categories
        setupCategories()

        if (filteredChannels.isNotEmpty()) {
            updateProgramList(0)
        }
    }

    private fun handleProgramClick(program: Program) {
        val currentTime = System.currentTimeMillis()

        // Use the currently selected channel in EPG (not program.channelId which is backend ID)
        val selectedChannel = filteredChannels.getOrNull(selectedChannelIndex) ?: return
        val globalIndex = channels.indexOfFirst { it.id == selectedChannel.id }

        if (program.isCurrentlyPlaying(currentTime)) {
            // Program is currently playing: Switch to that channel
            if (globalIndex >= 0) {
                onChannelSelected(globalIndex)
            }
        } else if (program.endTime < currentTime) {
            // Program is in the past: Request Archive
            // Use the selected channel's internal ID, not program.channelId (backend ID)
            onArchiveSelected("ARCHIVE_ID:${selectedChannel.id}:TIME:${program.startTime}")
        } else {
            // Future program
            Toast.makeText(activity, "This program has not started yet.", Toast.LENGTH_SHORT).show()
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
                if (android.os.Build.VERSION.SDK_INT >= 23) {
                    setTextAppearance(R.style.CategoryButton)
                } else {
                    @Suppress("DEPRECATION")
                    setTextAppearance(activity, R.style.CategoryButton)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(8, 8, 8, 8)
                }
                setPadding(48, 24, 48, 24)
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
            updateProgramList(0)
        }
    }

    private fun updateProgramList(channelIndex: Int) {
        if (channelIndex < 0 || channelIndex >= filteredChannels.size) return

        val channel = filteredChannels[channelIndex]

        val channelNameHeader = binding.root.findViewById<TextView>(R.id.tvSelectedChannelName)
        channelNameHeader?.text = "${channel.name} - TV Programs"

        programAdapter.updatePrograms(channel.programs)

        val currentProgramIndex = channel.programs.indexOfFirst { it.isCurrentlyPlaying() }
        if (currentProgramIndex >= 0) {
            val programList = binding.root.findViewById<RecyclerView>(R.id.programList)
            programList?.scrollToPosition(currentProgramIndex)
        }
    }

    fun refreshData(newChannels: List<Channel>) {
        channels = newChannels
        filteredChannels = repository.getChannelsByCategory(currentCategory)
        channelAdapter.updateChannels(filteredChannels)

        if (selectedChannelIndex < filteredChannels.size) {
            updateProgramList(selectedChannelIndex)
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
        
        // Remove border highlight when in categories
        updateChannelListBorder(false)

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (focusedIndex > 0) buttons[focusedIndex - 1].requestFocus()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (focusedIndex in buttons.indices && focusedIndex < buttons.lastIndex) buttons[focusedIndex + 1].requestFocus()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val channelList = binding.root.findViewById<RecyclerView>(R.id.channelList) ?: return false
                currentFocusSection = FocusSection.CHANNELS
                updateChannelListBorder(true)
                channelList.requestFocus()
                true
            }
            else -> false
        }
    }

    private fun handleChannelKeyEvent(keyCode: Int): Boolean {
        val channelList = binding.root.findViewById<RecyclerView>(R.id.channelList)
        val programList = binding.root.findViewById<RecyclerView>(R.id.programList)
        
        // Update channel list border highlight
        updateChannelListBorder(true)

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                currentFocusSection = FocusSection.CATEGORIES
                updateChannelListBorder(false)
                categoryButtons[currentCategory]?.requestFocus()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (programList != null) {
                    currentFocusSection = FocusSection.PROGRAMS
                    updateChannelListBorder(false)
                    programList.requestFocus()
                    true
                } else {
                    false
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                selectCurrentChannel()
                true
            }
            // UP/DOWN: Let RecyclerView handle scrolling naturally
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                // Ensure focus stays in channel list and scrolls
                channelList?.requestFocus()
                false // Return false to let RecyclerView handle the scroll
            }
            else -> false
        }
    }

    private fun handleProgramKeyEvent(keyCode: Int): Boolean {
        // Remove border highlight from channel list when in programs
        updateChannelListBorder(false)
        
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val channelList = binding.root.findViewById<RecyclerView>(R.id.channelList) ?: return false
                currentFocusSection = FocusSection.CHANNELS
                updateChannelListBorder(true)
                channelList.requestFocus()
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // Manually trigger click on focused view holder
                val list = binding.root.findViewById<RecyclerView>(R.id.programList)
                val view = list?.focusedChild
                view?.performClick()
                true
            }
            else -> false
        }
    }
    
    private fun updateChannelListBorder(highlight: Boolean) {
        val channelListCard = binding.root.findViewById<CardView>(R.id.channelListCard) ?: return
        
        if (highlight) {
            channelListCard.strokeWidth = 8
            channelListCard.strokeColor = ContextCompat.getColor(
                activity,
                android.R.color.holo_blue_bright
            )
        } else {
            channelListCard.strokeWidth = 0
        }
    }

    private fun selectCurrentChannel() {
        if (filteredChannels.isEmpty()) return
        val channel = filteredChannels.getOrNull(selectedChannelIndex) ?: return
        val globalIndex = channels.indexOfFirst { it.id == channel.id }
        if (globalIndex >= 0) {
            onChannelSelected(globalIndex)
        }
    }
}