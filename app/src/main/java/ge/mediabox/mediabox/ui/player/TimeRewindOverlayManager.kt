package ge.mediabox.mediabox.ui.player

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import ge.mediabox.mediabox.R
import ge.mediabox.mediabox.data.repository.ChannelRepository
import ge.mediabox.mediabox.ui.LangPrefs
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class TimeRewindOverlayManager(
    private val activity: Activity,
    val overlayView: View,
    private val channelIdProvider: () -> Int,
    private val onTimeSelected: (timestampMs: Long) -> Unit,
    private val onDismiss: () -> Unit
) {
    private val tvSelectedTime: TextView  = overlayView.findViewById(R.id.tvSelectedTime)
    private val tvArchiveRange: TextView  = overlayView.findViewById(R.id.tvArchiveRange)
    private val tvOutOfRange:   TextView  = overlayView.findViewById(R.id.tvOutOfRange)
    private val rvDays:         RecyclerView = overlayView.findViewById(R.id.rvDays)
    private val rvHours:        RecyclerView = overlayView.findViewById(R.id.rvHours)
    private val rvMinutes:      RecyclerView = overlayView.findViewById(R.id.rvMinutes)
    private val btnConfirm:     Button    = overlayView.findViewById(R.id.btnRewindConfirm)

    private data class DayEntry(val label: String, val dayStart: Calendar)

    private var dayEntries: List<DayEntry> = emptyList()
    private var selDay    = 0
    private var selHour   = 0
    private var selMinute = 0

    // focusCol: 0=days 1=hours 2=minutes 3=confirm
    private var focusCol = 0

    private lateinit var dayAdapter:    PickerAdapter
    private lateinit var hourAdapter:   PickerAdapter
    private lateinit var minuteAdapter: PickerAdapter

    private var displayFmt = SimpleDateFormat("EEE, d MMM  HH:mm", LangPrefs.getLocale(activity))
    private var dayFmt     = SimpleDateFormat("EEE, d MMM", LangPrefs.getLocale(activity))

    val isVisible: Boolean get() = overlayView.visibility == View.VISIBLE

    init {
        setupColumns()
        setupButtons()
    }

    fun show(hoursBack: Int, currentPlaybackMs: Long) {
        overlayView.visibility = View.VISIBLE
        val isKa = LangPrefs.isKa(activity)
        val locale = LangPrefs.getLocale(activity)

        displayFmt = SimpleDateFormat("EEE, d MMM  HH:mm", locale)
        dayFmt     = SimpleDateFormat("EEE, d MMM", locale)

        if (hoursBack <= 0) {
            tvSelectedTime.text = ""
            tvArchiveRange.visibility = View.GONE
            tvOutOfRange.visibility = View.VISIBLE
            tvOutOfRange.text = if (isKa) "არქივი მიუწვდომელია" else "Archive not supported"
            btnConfirm.visibility = View.GONE

            rvDays.alpha = 0.2f
            rvHours.alpha = 0.2f
            rvMinutes.alpha = 0.2f

            overlayView.requestFocus()
        } else {
            tvOutOfRange.visibility = View.GONE
            btnConfirm.visibility = View.VISIBLE
            rvDays.alpha = 1.0f
            rvHours.alpha = 1.0f
            rvMinutes.alpha = 1.0f

            // Pass the current playback time here
            buildDayList(hoursBack, currentPlaybackMs)
            refreshAdapters()

            focusCol = 0
            rvDays.requestFocus()
            updateDisplay()
        }
    }

    fun dismiss() {
        overlayView.visibility = View.GONE
        onDismiss()
    }

    fun handleKeyEvent(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP    -> { nudge(-1); true }
        KeyEvent.KEYCODE_DPAD_DOWN  -> { nudge(+1); true }
        KeyEvent.KEYCODE_DPAD_LEFT  -> { moveFocusLeft();  true }
        KeyEvent.KEYCODE_DPAD_RIGHT -> { moveFocusRight(); true }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
            // Immediately attempt to confirm when OK is pressed anywhere
            if (btnConfirm.isEnabled) {
                val ts = buildTimestamp()
                dismiss()
                onTimeSelected(ts)
            } else {
                // If it's out of range, show a toast and do not rewind
                val isKa = LangPrefs.isKa(activity)
                Toast.makeText(
                    activity,
                    if (isKa) "არქივი მიუწვდომელია" else "Out of archive range",
                    Toast.LENGTH_SHORT
                ).show()
            }
            true
        }
        KeyEvent.KEYCODE_BACK -> { dismiss(); true }
        else -> false
    }

    private fun buildDayList(hoursBack: Int, currentPlaybackMs: Long) {
        val isKa = LangPrefs.isKa(activity)

        tvArchiveRange.visibility = View.VISIBLE

        // Calculate days and hide excess hours as requested
        val d = hoursBack / 24

        tvArchiveRange.text = if (isKa) {
            if (d > 0) "${d} დღიანი არქივი" else "${hoursBack} საათიანი არქივი"
        } else {
            if (d > 0) "${d} day archive" else "${hoursBack} hour archive"
        }

        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }

        val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        val earliest = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, -hoursBack) }
        val cursor = (earliest.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }

        val entries = mutableListOf<DayEntry>()
        while (!cursor.after(today)) {
            val snap = cursor.clone() as Calendar
            val label = when {
                sameDay(snap, today) -> if (isKa) "დღეს" else "Today"
                sameDay(snap, yesterday) -> if (isKa) "გუშინ" else "Yesterday"
                else -> dayFmt.format(snap.time)
            }
            entries.add(DayEntry(label, snap))
            cursor.add(Calendar.DAY_OF_YEAR, 1)
        }
        dayEntries = entries

        // SNAP TO CURRENT PLAYBACK TIME INSTEAD OF REAL TIME
        val targetCal = Calendar.getInstance().apply { timeInMillis = currentPlaybackMs }

        selDay = entries.indexOfFirst { sameDay(it.dayStart, targetCal) }
        if (selDay == -1) selDay = entries.size - 1 // Fallback to today if not found

        selHour = targetCal.get(Calendar.HOUR_OF_DAY)
        selMinute = targetCal.get(Calendar.MINUTE)
    }

    private fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    private fun setupColumns() {
        dayAdapter    = PickerAdapter()
        hourAdapter   = PickerAdapter()
        minuteAdapter = PickerAdapter()

        setupColumn(rvDays,    dayAdapter,    0)
        setupColumn(rvHours,   hourAdapter,   1)
        setupColumn(rvMinutes, minuteAdapter, 2)
    }

    private fun setupColumn(rv: RecyclerView, adapter: PickerAdapter, colIndex: Int) {
        rv.layoutManager = LinearLayoutManager(activity)
        rv.adapter = adapter
        // DISABLE FADE ANIMATION
        rv.itemAnimator = null
        LinearSnapHelper().attachToRecyclerView(rv)

        rv.setOnFocusChangeListener { _, hasFocus ->
            adapter.setFocused(hasFocus)
            if (hasFocus) focusCol = colIndex
        }

        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                val lm   = recyclerView.layoutManager as LinearLayoutManager
                val snap = LinearSnapHelper()
                snap.findSnapView(lm)?.let { v ->
                    val pos = lm.getPosition(v)
                    if (pos >= 0) {
                        when (colIndex) {
                            0 -> { selDay    = pos; adapter.center = pos }
                            1 -> { selHour   = pos; adapter.center = pos }
                            2 -> { selMinute = pos; adapter.center = pos }
                        }
                        updateDisplay()
                    }
                }
            }
        })
    }

    private fun refreshAdapters() {
        dayAdapter.setItems(dayEntries.map { it.label })
        hourAdapter.setItems((0..23).map { "%02d".format(it) })
        minuteAdapter.setItems((0..59).map { "%02d".format(it) })

        scrollTo(rvDays,    dayAdapter,    selDay)
        scrollTo(rvHours,   hourAdapter,   selHour)
        scrollTo(rvMinutes, minuteAdapter, selMinute)
    }

    private fun scrollTo(rv: RecyclerView, adapter: PickerAdapter, pos: Int) {
        adapter.center = pos
        (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 0)
    }

    private fun nudge(delta: Int) {
        when (focusCol) {
            0 -> moveColumn(rvDays,    dayAdapter,    delta, dayEntries.size - 1) { selDay    = it }
            1 -> moveColumn(rvHours,   hourAdapter,   delta, 23)                  { selHour   = it }
            2 -> moveColumn(rvMinutes, minuteAdapter, delta, 59)                  { selMinute = it }
        }
    }

    private fun moveColumn(rv: RecyclerView, adapter: PickerAdapter, delta: Int, max: Int, onSet: (Int) -> Unit) {
        val next = (adapter.center + delta).coerceIn(0, max)
        if (next == adapter.center) return
        adapter.center = next
        onSet(next)
        // Set to scroll instantly (no smooth scroll)
        (rv.layoutManager as LinearLayoutManager).scrollToPosition(next)
        updateDisplay()
    }

    private fun moveFocusLeft() {
        when (focusCol) {
            1 -> { focusCol = 0; rvDays.requestFocus() }
            2 -> { focusCol = 1; rvHours.requestFocus() }
            3 -> { focusCol = 2; rvMinutes.requestFocus() }
        }
    }

    private fun moveFocusRight() {
        when (focusCol) {
            0 -> { focusCol = 1; rvHours.requestFocus() }
            1 -> { focusCol = 2; rvMinutes.requestFocus() }
            2 -> { focusCol = 3; btnConfirm.requestFocus() }
        }
    }

    private fun updateDisplay() {
        val tsMs = buildTimestamp()
        tvSelectedTime.text = displayFmt.format(Calendar.getInstance().apply { timeInMillis = tsMs }.time)

        val archiveStart = ChannelRepository.getArchiveStartMs(channelIdProvider())
        val outOfRange   = archiveStart != null && tsMs < archiveStart

        val isKa = LangPrefs.isKa(activity)
        tvOutOfRange.text = if (isKa) "არქივი მიუწვდომელია" else "Out of archive range"
        tvOutOfRange.visibility = if (outOfRange) View.VISIBLE else View.GONE
        tvSelectedTime.setTextColor(if (outOfRange) 0xFFEF4444.toInt() else 0xFFF1F5F9.toInt())

        btnConfirm.isEnabled = !outOfRange
        btnConfirm.alpha     = if (outOfRange) 0.4f else 1f
        btnConfirm.text = if (isKa) "დადასტურება" else "Confirm"
    }

    private fun buildTimestamp(): Long {
        val entry = dayEntries.getOrNull(selDay) ?: return System.currentTimeMillis()
        return (entry.dayStart.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, selHour)
            set(Calendar.MINUTE,      selMinute)
            set(Calendar.SECOND,      0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun setupButtons() {
        btnConfirm.setOnClickListener {
            val ts = buildTimestamp()
            dismiss()
            onTimeSelected(ts)
        }
        btnConfirm.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                focusCol = 3
                v.alpha = 1.0f
            } else {
                v.alpha = 0.9f
            }
        }
    }

    inner class PickerAdapter : RecyclerView.Adapter<PickerAdapter.VH>() {
        private var items = listOf<String>()
        private var focused = false

        var center = 0
            set(value) {
                val old = field; field = value
                notifyItemChanged(old); notifyItemChanged(value)
            }

        fun setItems(newItems: List<String>) { items = newItems; notifyDataSetChanged() }
        fun setFocused(f: Boolean)           { focused = f; notifyDataSetChanged() }

        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            TextView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 72.dp)
                gravity = android.view.Gravity.CENTER
            }
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val dist     = kotlin.math.abs(position - center)
            val isCenter = dist == 0
            holder.tv.apply {
                text     = items.getOrElse(position) { "" }
                textSize = when (dist) { 0 -> if (focused) 26f else 24f; 1 -> 19f; else -> 15f }
                alpha    = when (dist) { 0 -> 1f; 1 -> 0.55f; 2 -> 0.28f; else -> 0.12f }
                setTextColor(when {
                    isCenter && focused -> 0xFFFFFFFF.toInt()
                    isCenter           -> 0xFFF1F5F9.toInt()
                    else               -> 0x99F1F5F9.toInt()
                })
                setBackgroundColor(when {
                    isCenter && focused -> 0x33FFFFFF.toInt()
                    isCenter           -> 0x11FFFFFF.toInt()
                    else               -> 0x00000000
                })
                typeface = if (isCenter) android.graphics.Typeface.DEFAULT_BOLD
                else          android.graphics.Typeface.DEFAULT
            }
        }

        private val Int.dp get() = (this * activity.resources.displayMetrics.density).toInt()
    }
}