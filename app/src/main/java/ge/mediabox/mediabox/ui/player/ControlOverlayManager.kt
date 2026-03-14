package ge.mediabox.mediabox.ui.player

import android.view.View
import android.widget.*
import com.bumptech.glide.Glide
import ge.mediabox.mediabox.R
import ge.mediabox.mediabox.data.model.Channel
import ge.mediabox.mediabox.data.model.Program
import ge.mediabox.mediabox.databinding.ActivityPlayerBinding
import ge.mediabox.mediabox.ui.LogoManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ControlOverlayManager(
    private val binding: ActivityPlayerBinding,
    private val onFavoriteToggle: () -> Unit
) {
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateTimeFormat = SimpleDateFormat("HH:mm  dd.MM.yy", Locale.getDefault())

    init {
        binding.root.findViewById<ImageButton>(R.id.btnFavorite)?.setOnClickListener { onFavoriteToggle() }
    }

    fun updateChannelInfo(channel: Channel, currentProgram: Program?, streamTimestamp: Long? = null, isPlaying: Boolean = true) {
        val root = binding.root
        val isLive = streamTimestamp == null

        // Load Channel-Specific Logo (Left side) - This stays because it changes per channel
        val logo = root.findViewById<ImageView>(R.id.channelLogo)
        if (!channel.logoUrl.isNullOrEmpty() && logo != null) {
            Glide.with(root.context).load(channel.logoUrl).into(logo)
        }

        root.findViewById<TextView>(R.id.tvChannelName)?.text = channel.name
        root.findViewById<TextView>(R.id.tvChannelNumber)?.text = "${channel.number}"

        val displayTime = if (streamTimestamp != null) dateTimeFormat.format(Date(streamTimestamp)) else dateTimeFormat.format(Date())
        root.findViewById<TextView>(R.id.tvCurrentTime)?.text = displayTime

        if (currentProgram != null) {
            root.findViewById<TextView>(R.id.tvProgramTitle)?.text = currentProgram.title
            root.findViewById<TextView>(R.id.tvProgramTime)?.text = "${timeFormat.format(Date(currentProgram.startTime))} – ${timeFormat.format(Date(currentProgram.endTime))}"

            val playbackTime = streamTimestamp ?: System.currentTimeMillis()
            root.findViewById<ProgressBar>(R.id.programProgress)?.progress = (currentProgram.getProgress(playbackTime) * 100).toInt()
        }

        updateLiveIndicator(isLive, isPlaying)

        root.findViewById<View>(R.id.layoutForward15s)?.alpha = 1.0f
        root.findViewById<View>(R.id.layoutForward1m)?.alpha = 1.0f
        root.findViewById<View>(R.id.layoutForward5m)?.alpha = 1.0f

        root.findViewById<ImageButton>(R.id.btnFavorite)?.setImageResource(
            if (channel.isFavorite) R.drawable.ic_star else R.drawable.ic_star_outline
        )
    }

    fun updateLiveIndicator(isLive: Boolean, isPlaying: Boolean = true) {
        val btnLive = binding.root.findViewById<View>(R.id.btnLive) ?: return
        val tvLabel = binding.root.findViewById<TextView>(R.id.tvLiveLabel)
        val ivIcon = binding.root.findViewById<ImageView>(R.id.ivLiveBroadcastIcon)

        if (isLive && isPlaying) {
            btnLive.alpha = 1.0f
            tvLabel?.visibility = View.VISIBLE
            ivIcon?.visibility = View.GONE
        } else {
            btnLive.alpha = 0.5f
            tvLabel?.visibility = View.GONE
            ivIcon?.visibility = View.VISIBLE
        }
    }

    fun updateFavoriteButton(isFav: Boolean) {
        binding.root.findViewById<ImageButton>(R.id.btnFavorite)?.setImageResource(
            if (isFav) R.drawable.ic_star else R.drawable.ic_star_outline
        )
    }

    // Inside ControlOverlayManager.kt

    fun updateQualityInfo(height: Int) {
        val tvQuality = binding.root.findViewById<TextView>(R.id.tvCurrentQuality) ?: return
        if (height <= 0) {
            tvQuality.text = ""
            return
        }
        // Main screen stays simple: just "HD" or "SD"
        tvQuality.text = if (height >= 700) "HD" else "SD"
    }
}