package ge.mediabox.mediabox.ui.player

import android.graphics.drawable.AnimatedVectorDrawable
import android.view.View
import android.widget.*
import com.bumptech.glide.Glide
import ge.mediabox.mediabox.R
import ge.mediabox.mediabox.data.model.Channel
import ge.mediabox.mediabox.data.model.Program
import ge.mediabox.mediabox.databinding.ActivityPlayerBinding
import ge.mediabox.mediabox.ui.LangPrefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ControlOverlayManager(
    private val binding: ActivityPlayerBinding,
    private val onFavoriteToggle: () -> Unit
) {
    private var timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var dateTimeFormat = SimpleDateFormat("HH:mm  dd.MM.yy", Locale.getDefault())

    init {
        binding.root.findViewById<ImageButton>(R.id.btnFavorite)?.setOnClickListener { onFavoriteToggle() }
        updateLocale()
    }

    fun updateLocale() {
        val locale = LangPrefs.getLocale(binding.root.context)
        timeFormat = SimpleDateFormat("HH:mm", locale)
        dateTimeFormat = SimpleDateFormat("HH:mm  dd.MM.yy", locale)
    }

    fun updateChannelInfo(channel: Channel, currentProgram: Program?, streamTimestamp: Long? = null, isPlaying: Boolean = true) {
        val root = binding.root
        val isLive = streamTimestamp == null

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
        val root = binding.root
        val btnLive = root.findViewById<View>(R.id.btnLive) ?: return
        val ivPlayingAnim = root.findViewById<ImageView>(R.id.ivLivePlayingAnim)
        val ivBroadcastIcon = root.findViewById<ImageView>(R.id.ivLiveBroadcastIcon)

        if (isLive) {
            // Live mode: Show animation
            ivPlayingAnim?.visibility = View.VISIBLE
            ivBroadcastIcon?.visibility = View.GONE
            
            val drawable = ivPlayingAnim?.drawable
            if (drawable is AnimatedVectorDrawable) {
                // Ensure the animation is running if playing, or stopped at a frame if not
                if (isPlaying) {
                    if (!drawable.isRunning) drawable.start()
                } else {
                    drawable.stop()
                }
            }
            btnLive.alpha = 1.0f
        } else {
            // Archive/Delayed mode: Show broadcast icon to return to live
            ivPlayingAnim?.visibility = View.GONE
            ivBroadcastIcon?.visibility = View.VISIBLE
            
            val drawable = ivPlayingAnim?.drawable
            if (drawable is AnimatedVectorDrawable) drawable.stop()
            
            btnLive.alpha = 0.8f // Slightly dimmed but still indicates action
        }
    }

    fun updateFavoriteButton(isFav: Boolean) {
        binding.root.findViewById<ImageButton>(R.id.btnFavorite)?.setImageResource(
            if (isFav) R.drawable.ic_star else R.drawable.ic_star_outline
        )
    }

    fun updateQualityInfo(height: Int) {
        // No-op: Quality indicator moved to unified Settings panel
    }
}
