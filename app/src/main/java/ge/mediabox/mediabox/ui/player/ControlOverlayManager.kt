package ge.mediabox.mediabox.ui.player

import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.bumptech.glide.Glide
import ge.mediabox.mediabox.R
import ge.mediabox.mediabox.data.model.Channel
import ge.mediabox.mediabox.data.model.Program
import ge.mediabox.mediabox.databinding.ActivityPlayerBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ControlOverlayManager(
    private val binding: ActivityPlayerBinding,
    private val onFavoriteToggle: () -> Unit
) {
    private val timeFormat     = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateTimeFormat = SimpleDateFormat("HH:mm  dd.MM.yy", Locale.getDefault())

    init { setupControls() }

    private fun setupControls() {
        binding.root.findViewById<ImageButton>(R.id.btnFavorite)?.setOnClickListener {
            onFavoriteToggle()
        }
    }

    /**
     * FIX 6: Update rewind button visibility - dim when unavailable, don't hide
     */
    fun updateChannelInfo(
        channel: Channel,
        currentProgram: Program?,
        streamTimestamp: Long? = null,
        isPlaying: Boolean = true
    ) {
        val isLive = streamTimestamp == null

        // Channel logo
        val channelLogo = binding.root.findViewById<ImageView>(R.id.channelLogo)
        if (!channel.logoUrl.isNullOrEmpty() && channelLogo != null) {
            Glide.with(binding.root.context)
                .load(channel.logoUrl)
                .placeholder(R.color.surface_light)
                .error(R.color.surface_light)
                .into(channelLogo)
        }

        binding.root.findViewById<TextView>(R.id.tvChannelName)?.text = channel.name
        binding.root.findViewById<TextView>(R.id.tvChannelNumber)?.text = "#${channel.number}"

        // Timestamp display
        val displayTime = if (streamTimestamp != null) {
            dateTimeFormat.format(Date(streamTimestamp))
        } else {
            dateTimeFormat.format(Date())
        }
        binding.root.findViewById<TextView>(R.id.tvCurrentTime)?.text = displayTime

        // Program info
        if (currentProgram != null) {
            binding.root.findViewById<TextView>(R.id.tvProgramTitle)?.text = currentProgram.title
            val start = timeFormat.format(Date(currentProgram.startTime))
            val end   = timeFormat.format(Date(currentProgram.endTime))
            binding.root.findViewById<TextView>(R.id.tvProgramTime)?.text = "$start – $end"
            val progress = (currentProgram.getProgress() * 100).toInt()
            binding.root.findViewById<ProgressBar>(R.id.programProgress)?.progress = progress
        }

        // Live indicator
        updateLiveIndicator(isLive = isLive, isPlaying = isPlaying)

        // FIX 6: Forward buttons - DIM when live, don't hide
        val forwardAlpha = if (isLive) 0.3f else 1.0f
        val forwardEnabled = !isLive

        binding.root.findViewById<LinearLayout>(R.id.layoutForward15s)?.apply {
            alpha = forwardAlpha
            isEnabled = forwardEnabled
            findViewById<ImageButton>(R.id.btnForward15s)?.isEnabled = forwardEnabled
        }
        binding.root.findViewById<LinearLayout>(R.id.layoutForward1m)?.apply {
            alpha = forwardAlpha
            isEnabled = forwardEnabled
            findViewById<ImageButton>(R.id.btnForward1m)?.isEnabled = forwardEnabled
        }
        binding.root.findViewById<LinearLayout>(R.id.layoutForward5m)?.apply {
            alpha = forwardAlpha
            isEnabled = forwardEnabled
            findViewById<ImageButton>(R.id.btnForward5m)?.isEnabled = forwardEnabled
        }

        updateFavoriteButton(channel.isFavorite)
    }

    /**
     * Updates the live indicator button appearance:
     * - Live + playing  → red, full opacity, "LIVE" text
     * - Live + paused   → red, dimmed, broadcast icon (stream paused)
     * - Archive         → dimmed, broadcast icon (watching past content)
     */
    fun updateLiveIndicator(isLive: Boolean, isPlaying: Boolean = true) {
        val btnLive       = binding.root.findViewById<View>(R.id.btnLive) ?: return
        val tvLiveLabel   = binding.root.findViewById<TextView>(R.id.tvLiveLabel)
        val ivBroadcast   = binding.root.findViewById<ImageView>(R.id.ivLiveBroadcastIcon)

        when {
            isLive && isPlaying -> {
                // Fully live and playing
                btnLive.alpha = 1.0f
                tvLiveLabel?.visibility = View.VISIBLE
                ivBroadcast?.visibility = View.GONE
            }
            isLive && !isPlaying -> {
                // Live stream but paused — show broadcast icon, dimmed
                btnLive.alpha = 0.5f
                tvLiveLabel?.visibility = View.GONE
                ivBroadcast?.visibility = View.VISIBLE
            }
            else -> {
                // Archive mode — show broadcast icon, dimmed
                btnLive.alpha = 0.4f
                tvLiveLabel?.visibility = View.GONE
                ivBroadcast?.visibility = View.VISIBLE
            }
        }
    }

    fun updateFavoriteButton(isFavorite: Boolean) {
        binding.root.findViewById<ImageButton>(R.id.btnFavorite)?.setImageResource(
            if (isFavorite) R.drawable.ic_star else R.drawable.ic_star_outline
        )
    }
}