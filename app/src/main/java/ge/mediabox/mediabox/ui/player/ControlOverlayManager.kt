package ge.mediabox.mediabox.ui.player

import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
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

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateTimeFormat = SimpleDateFormat("HH:mm  dd.MM.yy", Locale.getDefault())

    init {
        setupControls()
    }

    private fun setupControls() {
        binding.root.findViewById<ImageButton>(R.id.btnFavorite)?.setOnClickListener {
            onFavoriteToggle()
        }
    }

    /**
     * Update channel info with optional stream timestamp
     * @param streamTimestamp: If provided (not null), shows this timestamp instead of current time
     */
    fun updateChannelInfo(channel: Channel, currentProgram: Program?, streamTimestamp: Long? = null) {
        // Load channel logo
        val channelLogo = binding.root.findViewById<ImageView>(R.id.channelLogo)
        if (!channel.logoUrl.isNullOrEmpty() && channelLogo != null) {
            Glide.with(binding.root.context)
                .load(channel.logoUrl)
                .placeholder(R.color.surface_light)
                .error(R.color.surface_light)
                .into(channelLogo)
        }

        binding.root.findViewById<TextView>(R.id.tvChannelName)?.text = channel.name

        // Show stream timestamp if provided, otherwise show current time
        val displayTime = if (streamTimestamp != null) {
            dateTimeFormat.format(Date(streamTimestamp))
        } else {
            dateTimeFormat.format(Date())
        }
        binding.root.findViewById<TextView>(R.id.tvCurrentTime)?.text = displayTime

        binding.root.findViewById<TextView>(R.id.tvChannelNumber)?.text = channel.number.toString()

        val qualityBadge = binding.root.findViewById<TextView>(R.id.tvQualityBadge)
        qualityBadge?.apply {
            text = if (channel.isHD) "HD" else "SD"
            setBackgroundResource(if (channel.isHD) R.drawable.badge_hd else R.drawable.badge_sd)
        }

        if (currentProgram != null) {
            binding.root.findViewById<TextView>(R.id.tvProgramTitle)?.text =
                currentProgram.title

            val startTime = timeFormat.format(Date(currentProgram.startTime))
            val endTime = timeFormat.format(Date(currentProgram.endTime))
            binding.root.findViewById<TextView>(R.id.tvProgramTime)?.text =
                "$startTime - $endTime"

            val progress = (currentProgram.getProgress() * 100).toInt()
            binding.root.findViewById<ProgressBar>(R.id.programProgress)?.progress = progress
        }

        updateFavoriteButton(channel.isFavorite)
    }

    fun updateFavoriteButton(isFavorite: Boolean) {
        val favoriteButton = binding.root.findViewById<ImageButton>(R.id.btnFavorite)
        favoriteButton?.setImageResource(
            if (isFavorite) R.drawable.ic_star else R.drawable.ic_star_outline
        )
    }
}