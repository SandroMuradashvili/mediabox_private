package ge.mediabox.mediabox.ui.player.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import ge.mediabox.mediabox.R
import ge.mediabox.mediabox.data.model.Channel

class ChannelAdapter(
    private var channels: List<Channel>,
    private val onChannelClick: (Int) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder>() {

    private var selectedPosition = 0
    private var focusedPosition = RecyclerView.NO_POSITION

    inner class ChannelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val channelNumber: TextView = itemView.findViewById(R.id.tvChannelNumber)
        val channelLogo: View = itemView.findViewById(R.id.channelLogo)
        val channelName: TextView = itemView.findViewById(R.id.tvChannelName)
        val channelCategory: TextView = itemView.findViewById(R.id.tvChannelCategory)
        val qualityBadge: TextView = itemView.findViewById(R.id.tvQualityBadge)
        val favoriteIcon: ImageView = itemView.findViewById(R.id.ivFavorite)

        init {
            itemView.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    selectedPosition = position
                    notifyDataSetChanged()
                    onChannelClick(position)
                }
            }

            itemView.setOnFocusChangeListener { _, hasFocus ->
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    if (hasFocus) {
                        focusedPosition = position
                        selectedPosition = position
                    } else if (focusedPosition == position) {
                        focusedPosition = RecyclerView.NO_POSITION
                    }
                    notifyDataSetChanged()
                    if (hasFocus) {
                        onChannelClick(position)
                    }
                }
            }
        }

        fun bind(channel: Channel, isSelected: Boolean, isFocused: Boolean) {
            channelNumber.text = channel.number.toString()
            channelName.text = channel.name
            channelCategory.text = channel.category

            // Load channel logo using Glide
            if (!channel.logoUrl.isNullOrEmpty()) {
                if (channelLogo is ImageView) {
                    Glide.with(itemView.context)
                        .load(channel.logoUrl)
                        .placeholder(R.color.surface_light)
                        .error(R.color.surface_light)
                        .into(channelLogo)
                }
            }

            qualityBadge.text = if (channel.isHD) "HD" else "SD"
            qualityBadge.setBackgroundResource(
                if (channel.isHD) R.drawable.badge_hd else R.drawable.badge_sd
            )

            favoriteIcon.visibility = if (channel.isFavorite) View.VISIBLE else View.GONE
            
            // Blue highlight for focused channel
            if (isFocused) {
                itemView.setBackgroundColor(
                    androidx.core.content.ContextCompat.getColor(itemView.context, android.R.color.holo_blue_light)
                )
                itemView.alpha = 1.0f
            } else {
                itemView.setBackgroundResource(android.R.color.transparent)
                itemView.alpha = if (isSelected) 1.0f else 0.7f
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel, parent, false)
        return ChannelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(channels[position], position == selectedPosition, position == focusedPosition)
    }

    override fun getItemCount() = channels.size

    fun updateChannels(newChannels: List<Channel>) {
        channels = newChannels
        notifyDataSetChanged()
    }
}