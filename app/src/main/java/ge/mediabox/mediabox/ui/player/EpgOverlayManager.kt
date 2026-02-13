package ge.mediabox.mediabox.ui.player

import android.app.Activity
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ge.mediabox.mediabox.R
import ge.mediabox.mediabox.data.model.Channel
import ge.mediabox.mediabox.data.repository.ChannelRepository
import ge.mediabox.mediabox.databinding.ActivityPlayerBinding
import ge.mediabox.mediabox.ui.player.adapter.ChannelAdapter
import ge.mediabox.mediabox.ui.player.adapter.ProgramAdapter

class EpgOverlayManager(
    private val activity: Activity,
    private val binding: ActivityPlayerBinding,
    private var channels: List<Channel>,
    private val onChannelSelected: (Int) -> Unit
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
        // Initialize adapters FIRST before anything else
        val channelList = binding.root.findViewById<RecyclerView>(R.id.channelList)
        channelAdapter = ChannelAdapter(filteredChannels) { position ->
            selectedChannelIndex = position
            updateProgramList(position)
            // Also notify activity so the actual playing channel changes when user moves selection
            val channel = filteredChannels.getOrNull(position)
            if (channel != null) {
                val globalIndex = channels.indexOfFirst { it.id == channel.id }
                if (globalIndex >= 0) {
                    onChannelSelected(globalIndex)
                }
            }
        }
        channelList?.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = channelAdapter
        }

        val programList = binding.root.findViewById<RecyclerView>(R.id.programList)
        programAdapter = ProgramAdapter(emptyList())
        programList?.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = programAdapter
        }

        // Now setup categories (which calls selectCategory)
        setupCategories()

        if (filteredChannels.isNotEmpty()) {
            updateProgramList(0)
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
        // When EPG is opened, always start in the category row
        currentFocusSection = FocusSection.CATEGORIES
        categoryButtons.values.firstOrNull()?.requestFocus()
    }

    /**
     * Custom DPAD navigation for EPG sections:
     * - Left/Right in categories stay within the category row
     * - Down from categories moves into channel list
     * - Left/Right between channel list and program list
     * - Channel selection triggers playback on OK/ENTER
     */
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
                if (focusedIndex > 0) {
                    buttons[focusedIndex - 1].requestFocus()
                }
                // Always consume so focus doesn't drop into lists
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (focusedIndex in buttons.indices && focusedIndex < buttons.lastIndex) {
                    buttons[focusedIndex + 1].requestFocus()
                }
                // Stay in category row even at the ends
                true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                // Move into channel list
                val channelList =
                    binding.root.findViewById<RecyclerView>(R.id.channelList) ?: return false
                currentFocusSection = FocusSection.CHANNELS
                channelList.requestFocus()
                true
            }

            else -> false
        }
    }

    private fun handleChannelKeyEvent(keyCode: Int): Boolean {
        val channelList = binding.root.findViewById<RecyclerView>(R.id.channelList)
        val programList = binding.root.findViewById<RecyclerView>(R.id.programList)

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                // Go back to categories
                currentFocusSection = FocusSection.CATEGORIES
                // Focus currently selected category button
                categoryButtons[currentCategory]?.requestFocus()
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // Move into program list
                if (programList != null) {
                    currentFocusSection = FocusSection.PROGRAMS
                    programList.requestFocus()
                    true
                } else {
                    false
                }
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // Confirm current channel selection and notify activity
                selectCurrentChannel()
                true
            }

            // Let UP/DOWN be handled by RecyclerView so it can scroll naturally
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> false

            else -> false
        }
    }

    private fun handleProgramKeyEvent(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                // Return focus to channel list
                val channelList =
                    binding.root.findViewById<RecyclerView>(R.id.channelList) ?: return false
                currentFocusSection = FocusSection.CHANNELS
                channelList.requestFocus()
                true
            }

            // Let UP/DOWN/RIGHT behave normally inside the program list
            else -> false
        }
    }

    private fun selectCurrentChannel() {
        if (filteredChannels.isEmpty()) return

        val channel = filteredChannels.getOrNull(selectedChannelIndex) ?: return
        // Find this channel in the full list so PlayerActivity can play it
        val globalIndex = channels.indexOfFirst { it.id == channel.id }
        if (globalIndex >= 0) {
            onChannelSelected(globalIndex)
        }
    }
}