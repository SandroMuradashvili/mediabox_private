package ge.mediabox.mediabox.ui.player

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import ge.mediabox.mediabox.R
import ge.mediabox.mediabox.data.repository.ChannelRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * FIX 11: Removed cancel button - only confirm button remains
 */
class TimeRewindOverlayManager(
    private val activity: Activity,
    val overlayView: View,
    private val channelIdProvider: () -> Int,
    private val onTimeSelected: (timestampMs: Long) -> Unit,
    private val onDismiss: () -> Unit
) {
    private val tvSelectedTime: TextView = overlayView.findViewById(R.id.tvSelectedTime)
    private val tvArchiveRange: TextView = overlayView.findViewById(R.id.tvArchiveRange)
    private val tvOutOfRange:   TextView = overlayView.findViewById(R.id.tvOutOfRange)
    private val rvDays:         RecyclerView = overlayView.findViewById(R.id.rvDays)
    private val rvHours:        RecyclerView = overlayView.findViewById(R.id.rvHours)
    private val rvMinutes:      RecyclerView = overlayView.findViewById(R.id.rvMinutes)
    private val btnConfirm:     Button = overlayView.findViewById(R.id.btnRewindConfirm)

    private data class DayEntry(val label: String, val dayStart: Calendar)

    private var dayEntries: List<DayEntry> = emptyList()
    private var selDay    = 0
    private var selHour   = 0
    private var selMinute = 0

    // FIX 11: Focus columns: 0=days, 1=hours, 2=minutes, 3=confirm (no cancel)
    private var focusCol = 0

    private lateinit var dayAdapter:    PickerAdapter
    private lateinit var hourAdapter:   PickerAdapter
    private lateinit var minuteAdapter: PickerAdapter

    private val timeFmt = SimpleDateFormat("EEE, d MMM  HH:mm", Locale.getDefault())
    private val dayFmt  = SimpleDateFormat("EEE, d MMM", Locale.getDefault())

    init {
        setupColumns()
        setupButtons()
    }

    val isVisible get() = overlayView.visibility == View.VISIBLE

    fun show() {
        buildDayList()
        refreshAdapters()
        overlayView.visibility = View.VISIBLE
        focusCol = 0
        rvDays.requestFocus()
        updateDisplay()
    }

    fun dismiss() {
        overlayView.visibility = View.GONE
        onDismiss()
    }

    fun handleKeyEvent(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP   -> { nudge(-1); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { nudge(+1); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { moveFocusLeft();  true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { moveFocusRight(); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (focusCol == 3) btnConfirm.performClick()
                true
            }
            KeyEvent.KEYCODE_BACK -> { dismiss(); true }
            else -> false
        }
    }

    private fun buildDayList() {
        val channelId  = channelIdProvider()
        val hoursBack  = ChannelRepository.getHoursBack(channelId)
        val effectiveH = if (hoursBack > 0) hoursBack else 7 * 24

        if (hoursBack > 0) {
            val d = hoursBack / 24
            val h = hoursBack % 24
            tvArchiveRange.text = if (h == 0) "${d}d rewind" else "${d}d ${h}h rewind"
            tvArchiveRange.visibility = View.VISIBLE
        } else {
            tvArchiveRange.visibility = View.GONE
        }

        val now = Calendar.getInstance()
        val earliest = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, -effectiveH) }

        val cursor = earliest.clone() as Calendar
        cursor.set(Calendar.HOUR_OF_DAY, 0)
        cursor.set(Calendar.MINUTE, 0)
        cursor.set(Calendar.SECOND, 0)
        cursor.set(Calendar.MILLISECOND, 0)

        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }

        val entries = mutableListOf<DayEntry>()
        while (!cursor.after(today)) {
            val snap = cursor.clone() as Calendar
            val label = when {
                sameDay(snap, today)     -> "Today"
                sameDay(snap, yesterday) -> "Yesterday"
                else -> dayFmt.format(snap.time)
            }
            entries.add(DayEntry(label, snap))
            cursor.add(Calendar.DAY_OF_YEAR, 1)
        }
        dayEntries = entries

        selDay    = entries.size - 1
        selHour   = now.get(Calendar.HOUR_OF_DAY)
        selMinute = now.get(Calendar.MINUTE)
    }

    private fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    private fun setupColumns() {
        dayAdapter    = PickerAdapter()
        hourAdapter   = PickerAdapter()
        minuteAdapter = PickerAdapter()

        fun init(rv: RecyclerView, adapter: PickerAdapter, colIndex: Int) {
            rv.layoutManager = LinearLayoutManager(activity)
            rv.adapter = adapter
            LinearSnapHelper().attachToRecyclerView(rv)
            rv.setOnFocusChangeListener { _, hasFocus ->
                adapter.setFocused(hasFocus)
                if (hasFocus) focusCol = colIndex
            }
            rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, state: Int) {
                    if (state == RecyclerView.SCROLL_STATE_IDLE) {
                        val lm = recyclerView.layoutManager as LinearLayoutManager
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
                }
            })
        }

        init(rvDays, dayAdapter, 0)
        init(rvHours, hourAdapter, 1)
        init(rvMinutes, minuteAdapter, 2)
    }

    private fun refreshAdapters() {
        dayAdapter.setItems(dayEntries.map { it.label })
        hourAdapter.setItems((0..23).map { String.format("%02d", it) })
        minuteAdapter.setItems((0..59).map { String.format("%02d", it) })

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
            0 -> moveColumn(rvDays, dayAdapter, delta, dayEntries.size - 1)    { selDay    = it }
            1 -> moveColumn(rvHours, hourAdapter, delta, 23)                   { selHour   = it }
            2 -> moveColumn(rvMinutes, minuteAdapter, delta, 59)               { selMinute = it }
        }
    }

    private fun moveColumn(rv: RecyclerView, adapter: PickerAdapter, delta: Int, max: Int, onSet: (Int) -> Unit) {
        val next = (adapter.center + delta).coerceIn(0, max)
        if (next == adapter.center) return
        adapter.center = next
        onSet(next)
        (rv.layoutManager as LinearLayoutManager).smoothScrollToPosition(rv, RecyclerView.State(), next)
        updateDisplay()
    }

    // FIX 11: Simplified focus navigation without cancel button
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
        val cal  = Calendar.getInstance().apply { timeInMillis = tsMs }
        tvSelectedTime.text = timeFmt.format(cal.time)

        val channelId    = channelIdProvider()
        val archiveStart = ChannelRepository.getArchiveStartMs(channelId)
        val outOfRange   = archiveStart != null && tsMs < archiveStart

        tvOutOfRange.visibility = if (outOfRange) View.VISIBLE else View.GONE
        tvSelectedTime.setTextColor(if (outOfRange) 0xFFF87171.toInt() else 0xFF818CF8.toInt())
        btnConfirm.isEnabled = !outOfRange
        btnConfirm.alpha     = if (outOfRange) 0.4f else 1.0f
    }

    private fun buildTimestamp(): Long {
        val entry = dayEntries.getOrNull(selDay) ?: return System.currentTimeMillis()
        return (entry.dayStart.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, selHour)
            set(Calendar.MINUTE, selMinute)
            set(Calendar.SECOND, 0)
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
            v.scaleX = if (hasFocus) 1.06f else 1f
            v.scaleY = if (hasFocus) 1.06f else 1f
            if (hasFocus) focusCol = 3
        }
    }

    inner class PickerAdapter : RecyclerView.Adapter<PickerAdapter.VH>() {
        private var items = listOf<String>()
        var center = 0
            set(value) { val old = field; field = value; notifyItemChanged(old); notifyItemChanged(value) }
        private var focused = false

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
            holder.tv.text = items.getOrElse(position) { "" }
            val dist = Math.abs(position - center)
            val isCenter = dist == 0
            holder.tv.apply {
                textSize = when (dist) { 0 -> if (focused) 26f else 24f; 1 -> 19f; else -> 15f }
                alpha    = when (dist) { 0 -> 1f; 1 -> 0.55f; 2 -> 0.28f; else -> 0.12f }
                setTextColor(when {
                    isCenter && focused -> 0xFFFFFFFF.toInt()
                    isCenter           -> 0xFF818CF8.toInt()
                    else               -> 0xFF94A3B8.toInt()
                })
                setBackgroundColor(when {
                    isCenter && focused -> 0x336366F1.toInt()
                    isCenter           -> 0x196366F1.toInt()
                    else               -> 0x00000000
                })
                typeface = if (isCenter) android.graphics.Typeface.DEFAULT_BOLD
                else          android.graphics.Typeface.DEFAULT
            }
        }

        private val Int.dp get() = (this * activity.resources.displayMetrics.density).toInt()
    }
}