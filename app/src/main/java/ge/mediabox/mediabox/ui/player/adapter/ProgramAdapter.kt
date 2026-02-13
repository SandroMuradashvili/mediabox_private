package ge.mediabox.mediabox.ui.player.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import ge.mediabox.mediabox.R
import ge.mediabox.mediabox.data.model.Program
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProgramAdapter(
    private var programs: List<Program>,
    private val onProgramClick: (Program) -> Unit
) : RecyclerView.Adapter<ProgramAdapter.ProgramViewHolder>() {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var focusedPosition = RecyclerView.NO_POSITION

    inner class ProgramViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        val programTime: TextView = itemView.findViewById(R.id.tvProgramTime)
        val programTitle: TextView = itemView.findViewById(R.id.tvProgramTitle)
        val programDescription: TextView = itemView.findViewById(R.id.tvProgramDescription)
        val programProgress: ProgressBar = itemView.findViewById(R.id.programProgress)
        val container: View = itemView.findViewById(R.id.programContainer)

        init {
            // Click listener
            itemView.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onProgramClick(programs[position])
                }
            }

            // Focus listener (for TV navigation)
            itemView.setOnFocusChangeListener { view, hasFocus ->
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    if (hasFocus) {
                        focusedPosition = position
                    } else if (focusedPosition == position) {
                        focusedPosition = RecyclerView.NO_POSITION
                    }
                    notifyItemChanged(position)
                }
            }
        }

        fun bind(program: Program, isFocused: Boolean) {
            val startTime = timeFormat.format(Date(program.startTime))
            val endTime = timeFormat.format(Date(program.endTime))
            programTime.text = "$startTime - $endTime"

            programTitle.text = program.title
            programDescription.text = program.description

            val isPlaying = program.isCurrentlyPlaying()

            // Highlight focused program (user is scrolling on it)
            if (isFocused) {
                container.setBackgroundColor(
                    ContextCompat.getColor(itemView.context, android.R.color.holo_blue_light)
                )
            } else {
                // Show currently playing program with progress bar
                if (isPlaying) {
                    programProgress.visibility = View.VISIBLE
                    programProgress.progress = (program.getProgress() * 100).toInt()
                    itemView.alpha = 1.0f

                    programTime.setTextColor(
                        ContextCompat.getColor(itemView.context, R.color.primary)
                    )
                    
                    container.setBackgroundResource(R.drawable.channel_item_background)
                } else {
                    programProgress.visibility = View.GONE

                    itemView.alpha =
                        if (program.endTime < System.currentTimeMillis()) 0.8f else 1.0f

                    programTime.setTextColor(
                        ContextCompat.getColor(itemView.context, R.color.text_tertiary)
                    )
                    
                    container.setBackgroundResource(R.drawable.channel_item_background)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProgramViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_program, parent, false)
        return ProgramViewHolder(view)
    }

    override fun onBindViewHolder(holder: ProgramViewHolder, position: Int) {
        holder.bind(programs[position], position == focusedPosition)
    }

    override fun getItemCount(): Int = programs.size

    fun updatePrograms(newPrograms: List<Program>) {
        programs = newPrograms
        notifyDataSetChanged()
    }
}
