package ge.mediabox.mediabox.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ge.mediabox.mediabox.R
import ge.mediabox.mediabox.data.remote.AuthApiService
import ge.mediabox.mediabox.data.remote.Plan
import ge.mediabox.mediabox.data.remote.PurchaseRequest
import ge.mediabox.mediabox.databinding.ActivityPlansBinding
import kotlinx.coroutines.launch

class PlansActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlansBinding
    private val authApi by lazy { AuthApiService.create(applicationContext) }

    private var allPlans: List<Plan> = emptyList()
    private var myPlanIds: Set<String> = emptySet()
    private var currentBalance: String = "0.00"

    private lateinit var adapter: PlansAdapter
    private var selectedIndex = 0
    private var focusOnBack = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlansBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = PlansAdapter(emptyList(), emptySet()) { plan -> confirmPurchase(plan) }

        binding.rvPlans.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvPlans.adapter = adapter
        binding.rvPlans.isFocusable = false
        binding.rvPlans.isFocusableInTouchMode = false

        binding.btnBack.setOnClickListener { finish() }
        binding.btnBack.setOnFocusChangeListener { _, hasFocus ->
            focusOnBack = hasFocus
            binding.btnBack.alpha = if (hasFocus) 1f else 0.4f
        }

        loadData()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { finish(); true }

            KeyEvent.KEYCODE_DPAD_UP -> {
                focusOnBack = true
                binding.btnBack.requestFocus()
                true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                focusOnBack = false
                adapter.setSelected(selectedIndex)
                true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (!focusOnBack) {
                    if (selectedIndex > 0) {
                        selectedIndex--
                        adapter.setSelected(selectedIndex)
                        binding.rvPlans.smoothScrollToPosition(selectedIndex)
                    }
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!focusOnBack) {
                    if (selectedIndex < allPlans.size - 1) {
                        selectedIndex++
                        adapter.setSelected(selectedIndex)
                        binding.rvPlans.smoothScrollToPosition(selectedIndex)
                    }
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (focusOnBack) {
                    finish()
                } else {
                    allPlans.getOrNull(selectedIndex)?.let { plan ->
                        if (!myPlanIds.contains(plan.id) && plan.is_active) {
                            confirmPurchase(plan)
                        } else if (myPlanIds.contains(plan.id)) {
                            Toast.makeText(this, "ეს პაკეტი უკვე გაქვს", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                true
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun loadData() {
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.rvPlans.visibility = View.GONE
        binding.tvSectionLabel.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val user = authApi.getUser()
                currentBalance = user.account?.balance ?: "0.00"

                runOnUiThread {
                    binding.tvBalance.text = "ბალანსი: ₾ $currentBalance"
                }

                allPlans = authApi.getPlans().filter { it.is_active }
                val myPlans = runCatching { authApi.getMyPlans() }.getOrDefault(emptyList())
                myPlanIds = myPlans.map { it.plan_id }.toSet()

                runOnUiThread {
                    adapter.updateData(allPlans, myPlanIds)
                    selectedIndex = 0
                    adapter.setSelected(0)
                    binding.loadingIndicator.visibility = View.GONE
                    binding.rvPlans.visibility = View.VISIBLE
                    binding.tvSectionLabel.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.loadingIndicator.visibility = View.GONE
                    binding.rvPlans.visibility = View.VISIBLE
                    binding.tvSectionLabel.visibility = View.VISIBLE
                    Toast.makeText(
                        this@PlansActivity,
                        "შეცდომა მონაცემების ჩატვირთვაში",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun confirmPurchase(plan: Plan) {
        val balanceVal = currentBalance.toDoubleOrNull() ?: 0.0
        val priceVal = plan.price.toDoubleOrNull() ?: 0.0

        if (balanceVal < priceVal) {
            Toast.makeText(this, "არასაკმარისი ბალანსი. საჭიროა ₾ ${plan.price}", Toast.LENGTH_LONG).show()
            return
        }

        val remaining = "%.2f".format(balanceVal - priceVal)

        AlertDialog.Builder(this, R.style.Theme_Mediabox_Dialog)
            .setTitle("პაკეტის შეძენა")
            .setMessage(
                "${plan.name_ka}\n\n" +
                        "ფასი: ₾ ${plan.price}\n" +
                        "ხანგრძლივობა: ${plan.duration_days} დღე\n\n" +
                        "შეძენის შემდეგ ბალანსი: ₾ $remaining"
            )
            .setPositiveButton("შეძენა") { _, _ -> purchasePlan(plan) }
            .setNegativeButton("გაუქმება", null)
            .show()
    }

    private fun purchasePlan(plan: Plan) {
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.rvPlans.visibility = View.GONE
        binding.tvSectionLabel.visibility = View.GONE

        lifecycleScope.launch {
            try {
                authApi.purchasePlan(PurchaseRequest(plan.id))
                runOnUiThread {
                    Toast.makeText(
                        this@PlansActivity,
                        "✓ ${plan.name_ka} წარმატებით შეძენილია!",
                        Toast.LENGTH_LONG
                    ).show()
                    loadData()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.loadingIndicator.visibility = View.GONE
                    binding.rvPlans.visibility = View.VISIBLE
                    binding.tvSectionLabel.visibility = View.VISIBLE
                    Toast.makeText(
                        this@PlansActivity,
                        "შეძენა ვერ მოხერხდა. სცადეთ თავიდან.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun getSavedToken(): String? =
        getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE).getString("auth_token", null)

    // ── Adapter ───────────────────────────────────────────────────────────────

    inner class PlansAdapter(
        private var plans: List<Plan>,
        private var ownedIds: Set<String>,
        private val onPurchase: (Plan) -> Unit
    ) : RecyclerView.Adapter<PlansAdapter.VH>() {

        private var selectedPos = -1

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView        = v.findViewById(R.id.tvPlanName)
            val tvDesc: TextView        = v.findViewById(R.id.tvPlanDescription)
            val tvPrice: TextView       = v.findViewById(R.id.tvPlanPrice)
            val tvDuration: TextView    = v.findViewById(R.id.tvPlanDuration)
            val tvOwned: TextView       = v.findViewById(R.id.tvOwnedBadge)
            val tvBuyBtn: TextView      = v.findViewById(R.id.tvBuyButton)
        }

        fun setSelected(pos: Int) {
            val old = selectedPos
            selectedPos = pos
            if (old in 0 until itemCount) notifyItemChanged(old)
            if (pos in 0 until itemCount) {
                notifyItemChanged(pos)
                binding.rvPlans.smoothScrollToPosition(pos)
            }
        }

        fun updateData(newPlans: List<Plan>, newOwned: Set<String>) {
            plans = newPlans
            ownedIds = newOwned
            selectedPos = -1
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_plan_new, parent, false)
        )

        override fun getItemCount() = plans.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val plan = plans[position]
            val owned = ownedIds.contains(plan.id)
            val selected = position == selectedPos

            holder.tvName.text     = plan.name_ka
            holder.tvDesc.text     = plan.description_ka
            holder.tvPrice.text    = "₾ ${plan.price}"
            holder.tvDuration.text = "${plan.duration_days} დღე"

            holder.tvOwned.visibility  = if (owned) View.VISIBLE else View.GONE
            holder.tvBuyBtn.visibility = if (!owned) View.VISIBLE else View.GONE

            // Background
            holder.itemView.setBackgroundResource(
                when {
                    owned    -> R.drawable.purchased_plan_background
                    selected -> R.drawable.menu_card_glass_selected
                    else     -> R.drawable.menu_card_glass
                }
            )

            // Scale + alpha
            val scale = if (selected && !owned) 1.04f else 1f
            holder.itemView.animate()
                .scaleX(scale).scaleY(scale)
                .setDuration(120).start()

            holder.itemView.alpha = if (owned) 0.65f else 1f

            // Buy button highlight
            holder.tvBuyBtn.alpha = if (selected) 1f else 0.45f
            holder.tvBuyBtn.setBackgroundResource(
                if (selected) R.drawable.epg_now_badge else R.drawable.badge_archive_range
            )

            holder.itemView.setOnClickListener {
                if (!owned) onPurchase(plan)
                else Toast.makeText(context, "ეს პაკეტი უკვე გაქვს", Toast.LENGTH_SHORT).show()
            }
        }

        private val context get() = this@PlansActivity
    }
}