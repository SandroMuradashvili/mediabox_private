package ge.mediabox.mediabox.ui.player

import android.app.Activity
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

    init {
        setupEpg()
    }

    private fun setupEpg() {
        // Initialize adapters FIRST before anything else
        val channelList = binding.root.findViewById<RecyclerView>(R.id.channelList)
        channelAdapter = ChannelAdapter(filteredChannels) { position ->
            selectedChannelIndex = position
            updateProgramList(position)
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
        categoryButtons.values.firstOrNull()?.requestFocus()
    }
}