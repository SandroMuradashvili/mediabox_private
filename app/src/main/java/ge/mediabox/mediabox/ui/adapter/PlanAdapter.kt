package ge.mediabox.mediabox.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ge.mediabox.mediabox.R
import ge.mediabox.mediabox.data.remote.Plan

class PlanAdapter(
    private val plans: List<Plan>
) : RecyclerView.Adapter<PlanAdapter.PlanViewHolder>() {

    inner class PlanViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val planName: TextView = itemView.findViewById(R.id.tvPlanName)
        val planDescription: TextView = itemView.findViewById(R.id.tvPlanDescription)
        val planPrice: TextView = itemView.findViewById(R.id.tvPlanPrice)
        val planDuration: TextView = itemView.findViewById(R.id.tvPlanDuration)

        fun bind(plan: Plan) {
            planName.text = plan.name_en
            planDescription.text = plan.description_en
            planPrice.text = "$${plan.price}"
            planDuration.text = "${plan.duration_days} ${if (plan.duration_days == 1) "day" else "days"}"
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlanViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_plan, parent, false)
        return PlanViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlanViewHolder, position: Int) {
        holder.bind(plans[position])
    }

    override fun getItemCount(): Int = plans.size
}
