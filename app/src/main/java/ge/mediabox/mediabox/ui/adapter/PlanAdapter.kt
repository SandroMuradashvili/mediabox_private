package ge.mediabox.mediabox.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import ge.mediabox.mediabox.R
import ge.mediabox.mediabox.data.remote.Plan
import ge.mediabox.mediabox.data.remote.MyPlan

class PlanAdapter(
    private val plans: List<Plan>,
    private val purchasedPlans: Map<String, MyPlan> = emptyMap()
) : RecyclerView.Adapter<PlanAdapter.PlanViewHolder>() {

    inner class PlanViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val planName: TextView = itemView.findViewById(R.id.tvPlanName)
        val planDescription: TextView = itemView.findViewById(R.id.tvPlanDescription)
        val planPrice: TextView = itemView.findViewById(R.id.tvPlanPrice)
        val planDuration: TextView = itemView.findViewById(R.id.tvPlanDuration)

        fun bind(plan: Plan, isPurchased: Boolean) {
            planName.text = plan.name_en
            planDescription.text = plan.description_en
            planPrice.text = plan.price
            planDuration.text = "${plan.duration_days} days"

            if (isPurchased) {
                itemView.setBackgroundResource(R.drawable.purchased_plan_background)
                planName.append(" (Purchased)")
                planName.setTextColor(ContextCompat.getColor(itemView.context, R.color.success))
            } else {
                itemView.setBackgroundResource(R.drawable.plan_item_background)
                planName.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_primary))
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlanViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_plan, parent, false)
        return PlanViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlanViewHolder, position: Int) {
        val plan = plans[position]
        holder.bind(plan, purchasedPlans.containsKey(plan.id))
    }

    override fun getItemCount(): Int = plans.size
}
