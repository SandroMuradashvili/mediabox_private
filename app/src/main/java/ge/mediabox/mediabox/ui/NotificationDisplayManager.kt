package ge.mediabox.mediabox.ui

import android.annotation.SuppressLint
import android.app.Activity
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

@SuppressLint("StaticFieldLeak")
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
        currentActivity = activity
        currentOverlay = overlay
        currentToken = token

        // If there are pending notifications in queue, start showing them
        if (!isShowing && notificationQueue.isNotEmpty()) {
            showNext()
        }
    }

    /**
     * Call this in onPause to prevent crashes when activity is not visible
     */
    fun unregister() {
        currentActivity = null
        currentOverlay = null
    }

    /**
     * Adds a new notification to the queue (e.g. from Sockets)
     */
    fun addNotification(notification: ApiService.Notification) {
        notificationQueue.add(notification)
        if (!isShowing) {
            showNext()
        }
    }

    /**
     * Fetches unread notifications from API and adds them to queue
     */
    fun fetchInitialNotifications(token: String, scope: CoroutineScope) {
        scope.launch {
            try {
                val notifications = withContext(Dispatchers.IO) {
                    ApiService.fetchNotifications(token)
                }
                notifications.forEach { notificationQueue.add(it) }
                if (!isShowing && notificationQueue.isNotEmpty()) {
                    showNext()
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun showNext() {
        val activity = currentActivity ?: run {
            return
        }
        val overlay = currentOverlay ?: return
        val token = currentToken ?: return

        val notification = notificationQueue.poll() ?: run {
            isShowing = false
            activity.runOnUiThread { overlay.visibility = View.GONE }
            return
        }

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
                // Mark as read in background
                CoroutineScope(Dispatchers.IO).launch {
                    val success = ApiService.markNotificationAsRead(notification.id, token)
                }
                isShowing = false // Reset before showNext to allow recursion
                showNext()
            }
        }
    }
}