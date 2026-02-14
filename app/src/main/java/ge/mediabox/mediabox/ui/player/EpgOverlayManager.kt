package ge.mediabox.mediabox.ui.player

import android.app.Activity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import ge.mediabox.mediabox.R
import ge.mediabox.mediabox.data.model.Channel
import ge.mediabox.mediabox.data.model.Program
import ge.mediabox.mediabox.data.repository.ChannelRepository
import ge.mediabox.mediabox.databinding.ActivityPlayerBinding
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
    private val categoryButtons = mutableMapOf<String, TextView>()

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEEE, d MMM yyyy", Locale.getDefault())

    // Time slots for EPG grid (24 hours in 1-hour blocks)
    private val timeSlots = mutableListOf<Long>()
    private val SLOT_WIDTH_DP = 200 // Width of each hour slot in dp
    private val SLOT_DURATION_MS = 60 * 60 * 1000L // 1 hour

    init {
        setupEpg()
    }

    private fun setupEpg() {
        generateTimeSlots()
        setupCategories()
        setupDateHeader()
        setupTimeHeader()
        setupGrid()
    }

    private fun generateTimeSlots() {
        timeSlots.clear()
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        // Generate 24 hour slots for today
        for (i in 0 until 24) {
            timeSlots.add(calendar.timeInMillis)
            calendar.add(Calendar.HOUR_OF_DAY, 1)
        }
    }

    private fun setupDateHeader() {
        val dateTextView = binding.root.findViewById<TextView>(R.id.tvCurrentDate)
        dateTextView?.text = dateFormat.format(Date())
    }

    private fun setupTimeHeader() {
        val timeSlotsContainer = binding.root.findViewById<LinearLayout>(R.id.timeSlots)
        timeSlotsContainer?.removeAllViews()

        val widthPx = (SLOT_WIDTH_DP * activity.resources.displayMetrics.density).toInt()

        timeSlots.forEach { slotTime ->
            val timeView = TextView(activity).apply {
                text = timeFormat.format(Date(slotTime))
                layoutParams = LinearLayout.LayoutParams(widthPx, ViewGroup.LayoutParams.MATCH_PARENT)
                gravity = android.view.Gravity.CENTER
                setTextColor(activity.getColor(R.color.text_primary))
                textSize = 16f
                setBackgroundColor(activity.getColor(R.color.surface))
            }
            timeSlotsContainer?.addView(timeView)
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
        setupGrid()
    }

    private fun setupGrid() {
        val recyclerView = binding.root.findViewById<RecyclerView>(R.id.epgGridRecycler)
        val adapter = EpgGridAdapter(filteredChannels, timeSlots)
        recyclerView?.apply {
            layoutManager = LinearLayoutManager(activity)
            this.adapter = adapter
        }
    }

    fun refreshData(newChannels: List<Channel>) {
        channels = newChannels
        filteredChannels = repository.getChannelsByCategory(currentCategory)
        setupGrid()
    }

    fun requestFocus() {
        categoryButtons.values.firstOrNull()?.requestFocus()
    }

    fun handleKeyEvent(keyCode: Int): Boolean {
        // Let the RecyclerView and ScrollViews handle navigation
        return false
    }

    // EPG Grid Adapter
    inner class EpgGridAdapter(
        private val channels: List<Channel>,
        private val timeSlots: List<Long>
    ) : RecyclerView.Adapter<EpgGridAdapter.ChannelRowViewHolder>() {

        inner class ChannelRowViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val channelLogo: ImageView = view.findViewById(R.id.channelLogo)
            val channelNumber: TextView = view.findViewById(R.id.tvChannelNumber)
            val channelName: TextView = view.findViewById(R.id.tvChannelName)
            val programsRow: LinearLayout = view.findViewById(R.id.programsRow)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelRowViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_epg_row, parent, false)
            return ChannelRowViewHolder(view)
        }

        override fun onBindViewHolder(holder: ChannelRowViewHolder, position: Int) {
            val channel = channels[position]

            // Channel info
            holder.channelNumber.text = channel.number.toString()
            holder.channelName.text = channel.name

            if (!channel.logoUrl.isNullOrEmpty()) {
                Glide.with(activity)
                    .load(channel.logoUrl)
                    .placeholder(R.color.surface_light)
                    .error(R.color.surface_light)
                    .into(holder.channelLogo)
            }

            // Clear previous programs
            holder.programsRow.removeAllViews()

            // Add program blocks for each time slot
            val widthPx = (SLOT_WIDTH_DP * activity.resources.displayMetrics.density).toInt()
            val currentTime = System.currentTimeMillis()

            timeSlots.forEach { slotStart ->
                val slotEnd = slotStart + SLOT_DURATION_MS

                // Find programs that overlap with this slot
                val programsInSlot = channel.programs.filter { program ->
                    program.startTime < slotEnd && program.endTime > slotStart
                }

                if (programsInSlot.isNotEmpty()) {
                    // Show first program that overlaps (simplified)
                    val program = programsInSlot.first()

                    val programView = createProgramView(program, channel, widthPx, currentTime)
                    holder.programsRow.addView(programView)
                } else {
                    // Empty slot
                    val emptyView = View(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(widthPx, ViewGroup.LayoutParams.MATCH_PARENT)
                        setBackgroundColor(activity.getColor(R.color.surface))
                    }
                    holder.programsRow.addView(emptyView)
                }
            }
        }

        private fun createProgramView(program: Program, channel: Channel, widthPx: Int, currentTime: Long): View {
            val programView = TextView(activity).apply {
                text = program.title
                layoutParams = LinearLayout.LayoutParams(widthPx, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    setMargins(2, 2, 2, 2)
                }
                setPadding(12, 12, 12, 12)
                textSize = 14f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END

                // Highlight currently playing
                if (program.isCurrentlyPlaying(currentTime)) {
                    isSelected = true
                    setTextColor(activity.getColor(android.R.color.white))
                } else {
                    setTextColor(activity.getColor(R.color.text_primary))
                }

                setBackgroundResource(R.drawable.epg_program_cell_background)
                isFocusable = true
                isFocusableInTouchMode = true

                setOnClickListener {
                    handleProgramClick(program, channel, currentTime)
                }

                setOnFocusChangeListener { v, hasFocus ->
                    v.scaleX = if (hasFocus) 1.05f else 1f
                    v.scaleY = if (hasFocus) 1.05f else 1f
                }
            }

            return programView
        }

        private fun handleProgramClick(program: Program, channel: Channel, currentTime: Long) {
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
                    android.widget.Toast.makeText(activity, "This program hasn't started yet", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }

        override fun getItemCount() = channels.size
    }
}