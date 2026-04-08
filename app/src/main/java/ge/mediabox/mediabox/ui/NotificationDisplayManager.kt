package ge.mediabox.mediabox.ui

import android.app.Activity
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import ge.mediabox.mediabox.R
import ge.mediabox.mediabox.data.api.ApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.LinkedList
import java.util.Queue

object NotificationDisplayManager {
    private const val TAG = "Pairing"
    private val notificationQueue: Queue<ApiService.Notification> = LinkedList()
    private var isShowing = false

    private var currentActivity: Activity? = null
    private var currentOverlay: View? = null
    private var currentToken: String? = null

    fun isShowing() = isShowing

    /**
     * Call this in onResume to allow the manager to show notifications on the current activity
     */
    fun register(activity: Activity, overlay: View, token: String) {
        Log.d(TAG, "🔔 [NotificationManager] Registered. Queue size: ${notificationQueue.size}")
        currentActivity = activity
        currentOverlay = overlay
        currentToken = token
        
        // If there are pending notifications in queue, start showing them
        if (!isShowing && notificationQueue.isNotEmpty()) {
            Log.d(TAG, "🔔 [NotificationManager] Pending notifications found on register, showing next...")
            showNext()
        }
    }

    /**
     * Call this in onPause to prevent crashes when activity is not visible
     */
    fun unregister() {
        Log.d(TAG, "🔔 [NotificationManager] Unregistered")
        currentActivity = null
        currentOverlay = null
    }

    /**
     * Adds a new notification to the queue (e.g. from Sockets)
     */
    fun addNotification(notification: ApiService.Notification) {
        Log.d(TAG, "🔔 [NotificationManager] Adding notification to queue: ${notification.message}")
        notificationQueue.add(notification)
        if (!isShowing) {
            showNext()
        }
    }

    /**
     * Fetches unread notifications from API and adds them to queue
     */
    fun fetchInitialNotifications(token: String, scope: CoroutineScope) {
        Log.d(TAG, "🔔 [NotificationManager] Fetching initial notifications...")
        scope.launch {
            try {
                val notifications = withContext(Dispatchers.IO) {
                    ApiService.fetchNotifications(token)
                }
                Log.d(TAG, "🔔 [NotificationManager] Fetched ${notifications.size} notifications")
                notifications.forEach { notificationQueue.add(it) }
                if (!isShowing && notificationQueue.isNotEmpty()) {
                    showNext()
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ [NotificationManager] Error fetching initial notifications", e)
            }
        }
    }

    private fun showNext() {
        val activity = currentActivity ?: run {
            Log.w(TAG, "⚠️ [NotificationManager] showNext called but no activity registered")
            return
        }
        val overlay = currentOverlay ?: return
        val token = currentToken ?: return

        val notification = notificationQueue.poll() ?: run {
            Log.d(TAG, "🔔 [NotificationManager] Queue empty, hiding overlay")
            isShowing = false
            activity.runOnUiThread { overlay.visibility = View.GONE }
            return
        }

        Log.d(TAG, "🔔 [NotificationManager] Displaying notification: ${notification.id}")
        isShowing = true
        activity.runOnUiThread {
            val titleView = overlay.findViewById<TextView>(R.id.tvSubNotificationTitle)
            val messageView = overlay.findViewById<TextView>(R.id.tvSubNotificationMessage)
            val okBtn = overlay.findViewById<Button>(R.id.btnSubNotificationOk)

            titleView?.text = notification.title ?: (if (LangPrefs.isKa(activity)) "შეტყობინება" else "Notification")
            messageView?.text = notification.message
            okBtn?.text = if (LangPrefs.isKa(activity)) "გასაგებია" else "OK"

            overlay.visibility = View.VISIBLE
            okBtn?.requestFocus()

            okBtn?.setOnClickListener {
                Log.d(TAG, "🔔 [NotificationManager] OK clicked for: ${notification.id}")
                // Mark as read in background
                CoroutineScope(Dispatchers.IO).launch {
                    val success = ApiService.markNotificationAsRead(notification.id, token)
                    Log.d(TAG, "🔔 [NotificationManager] Mark as read success: $success")
                }
                isShowing = false // Reset before showNext to allow recursion
                showNext()
            }
        }
    }
}