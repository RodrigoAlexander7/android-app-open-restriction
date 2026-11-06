package net.jakstech.openlimit

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager

/**
 * Watches which app comes to the foreground and, through [OpenTracker], counts opens of
 * limited apps. If an app already used all its opens for this hour, it's sent home and
 * the block screen is shown instead.
 */
class LimitAccessibilityService : AccessibilityService() {

    private lateinit var store: LimitStore
    private var ignored: Set<String> = emptySet()

    private lateinit var overlay: BlockOverlay

    override fun onServiceConnected() {
        store = LimitStore(this)
        overlay = BlockOverlay(this)
        ignored = buildIgnoredSet()
        goForeground()
    }

    /**
     * A foreground notification raises the process priority (adj ~200 instead of ~900),
     * so MIUI's "clear all" / swipe cleanup no longer kills the watcher.
     */
    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Servicio activo", NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("OpenLimit activo")
            .setContentText("Vigilando los límites de apps")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, n)
            }
        }
    }

    /**
     * Filters raw window events down to "a real Activity of pkg came to the front" and
     * hands them to [OpenTracker], which decides opens/resumes/blocks (see its docs).
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        // Overlays (status bar, keyboard, share sheet...) don't mean we left the current app.
        if (pkg in ignored) return
        // Pane changes inside an app (miniplayer, drawers...) are not window switches.
        if (event.contentChangeTypes != 0) return
        // Dialogs, popups and views also fire this event; only real screens count.
        if (!isActivity(pkg, cls)) return

        act(tracker.onActivityEvent(pkg, event.eventTime))
        handler.removeCallbacks(settle)
        handler.postDelayed(
            settle, (tracker.settleDeadline() - SystemClock.uptimeMillis()).coerceAtLeast(0)
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private val settle = Runnable { act(tracker.settle()) }

    private val tracker by lazy {
        OpenTracker(store, packageName, object : OpenTracker.Clock {
            override fun uptimeMillis() = SystemClock.uptimeMillis()
            override fun elapsedRealtime() = SystemClock.elapsedRealtime()
        })
    }

    /** Only decisions about limited apps are logged: no trail of everything else you use. */
    private fun act(d: OpenTracker.Decision?) {
        when (d) {
            null -> Unit
            is OpenTracker.Decision.Open -> Log.d(TAG, "open ${d.pkg} -> ${d.used}/${d.limit}")
            is OpenTracker.Decision.Resume ->
                Log.d(TAG, "resume ${d.pkg} after ${d.awayMs / 1000}s: same session")
            is OpenTracker.Decision.Block -> {
                Log.d(TAG, "BLOCK ${d.pkg} (${d.used}/${d.limit})" +
                    if (d.live) "" else " - already left, not shown")
                if (d.live) block(d.pkg, d.limit)
            }
        }
    }

    private val activityCache = HashMap<String, Boolean>()

    private fun isActivity(pkg: String, cls: String): Boolean =
        activityCache.getOrPut("$pkg/$cls") {
            runCatching {
                packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
                true
            }.getOrDefault(false)
        }

    private fun block(pkg: String, limit: Int) {
        performGlobalAction(GLOBAL_ACTION_HOME)
        overlay.show(pkg, limit)
    }

    private fun buildIgnoredSet(): Set<String> {
        val imes = runCatching {
            getSystemService(InputMethodManager::class.java)
                .enabledInputMethodList.map { it.packageName }
        }.getOrDefault(emptyList())
        return setOf(
            "com.android.systemui",
            "android",
            "com.android.intentresolver",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
        ) + imes
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(settle)
        overlay.hide()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "OpenLimit"
        private const val CHANNEL = "watcher"
    }
}
