package ge.mediabox.mediabox.ui

import android.content.Context
import ge.mediabox.mediabox.data.remote.MyPlan
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SubscriptionNotificationManager {
    private const val PREFS_NAME = "SubscriptionNotifications"
    
    /**
     * Checks if any plan needs a notification today.
     * Returns the first plan that should be notified but hasn't been today.
     */
    fun getPlanToNotify(context: Context, plans: List<MyPlan>): MyPlan? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        
        for (plan in plans) {
            // Production check: 3 days or less
            if (plan.days_left <= 3.0 && plan.days_left > 0) {
                // Use plan_id + expires_at to uniquely identify this specific subscription instance
                val uniqueKey = "notified_${plan.plan_id}_${plan.expires_at}"
                val lastNotifiedDate = prefs.getString(uniqueKey, "")
                
                // If it hasn't been shown yet today, return it
                if (lastNotifiedDate != today) {
                    return plan
                }
            }
        }
        return null
    }
    
    /**
     * Marks a specific subscription instance as notified for today.
     */
    fun markAsNotified(context: Context, plan: MyPlan) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val uniqueKey = "notified_${plan.plan_id}_${plan.expires_at}"
        prefs.edit().putString(uniqueKey, today).apply()
    }
}
