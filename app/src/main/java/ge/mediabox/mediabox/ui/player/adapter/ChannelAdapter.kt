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

    inner class ChannelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val channelNumber:   TextView  = itemView.findViewById(R.id.tvChannelNumber)
        val channelLogo:     ImageView = itemView.findViewById(R.id.channelLogo)
        val channelName:     TextView  = itemView.findViewById(R.id.tvChannelName)
        val channelCategory: TextView  = itemView.findViewById(R.id.tvChannelCategory)
        val favoriteIcon:    ImageView = itemView.findViewById(R.id.ivFavorite)

        init {
            itemView.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    selectedPosition = pos; notifyDataSetChanged(); onChannelClick(pos)
                }
            }
            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        selectedPosition = pos; notifyDataSetChanged(); onChannelClick(pos)
                    }
                }
            }
        }

        fun bind(channel: Channel, isSelected: Boolean) {
            channelNumber.text   = channel.number.toString()
            channelName.text     = channel.name
            channelCategory.text = channel.category

            if (!channel.logoUrl.isNullOrEmpty()) {
                Glide.with(itemView.context)
                    .load(channel.logoUrl)
                    .placeholder(R.color.surface_light)
                    .error(R.color.surface_light)
                    .into(channelLogo)
            }

            favoriteIcon.visibility = if (channel.isFavorite) View.VISIBLE else View.GONE
            itemView.alpha = if (isSelected) 1.0f else 0.7f
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ChannelViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
    )

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(channels[position], position == selectedPosition)
    }

    override fun getItemCount() = channels.size

    fun updateChannels(newChannels: List<Channel>) {
        channels = newChannels; notifyDataSetChanged()
    }
}