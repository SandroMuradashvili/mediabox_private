package ge.mediabox.mediabox.ui.player.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ge.mediabox.mediabox.R
import ge.mediabox.mediabox.data.model.Program
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProgramAdapter(
    private var programs: List<Program>
) : RecyclerView.Adapter<ProgramAdapter.ProgramViewHolder>() {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    inner class ProgramViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val programTime: TextView = itemView.findViewById(R.id.tvProgramTime)
        val programTitle: TextView = itemView.findViewById(R.id.tvProgramTitle)
        val programDescription: TextView = itemView.findViewById(R.id.tvProgramDescription)
        val programProgress: ProgressBar = itemView.findViewById(R.id.programProgress)

        fun bind(program: Program) {
            val startTime = timeFormat.format(Date(program.startTime))
            val endTime = timeFormat.format(Date(program.endTime))
            programTime.text = "$startTime - $endTime"

            programTitle.text = program.title
            programDescription.text = program.description

            val isPlaying = program.isCurrentlyPlaying()
            if (isPlaying) {
                programProgress.visibility = View.VISIBLE
                programProgress.progress = (program.getProgress() * 100).toInt()
                itemView.alpha = 1.0f
                programTime.setTextColor(itemView.context.getColor(R.color.primary))
            } else {
                programProgress.visibility = View.GONE
                itemView.alpha = 0.7f
                programTime.setTextColor(itemView.context.getColor(R.color.text_tertiary))
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProgramViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_program, parent, false)
        return ProgramViewHolder(view)
    }

    override fun onBindViewHolder(holder: ProgramViewHolder, position: Int) {
        holder.bind(programs[position])
    }

    override fun getItemCount() = programs.size

    fun updatePrograms(newPrograms: List<Program>) {
        programs = newPrograms
        notifyDataSetChanged()
    }
}