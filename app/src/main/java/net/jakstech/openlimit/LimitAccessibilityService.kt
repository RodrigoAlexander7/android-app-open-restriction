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
 * Watches which app comes to the foreground. Every time a limited app comes to the
 * foreground from a *different* app (launcher, another app, recents...), that's one "open".
 * If the app already used all its opens for this hour, it's sent home and the block
 * screen is shown instead.
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
     * Window events are noisy: launch/close animations, gestures, dialogs, panes and
     * late events from an app that is closing all fire TYPE_WINDOW_STATE_CHANGED.
     * So we count *sessions*, not events:
     *  1. only events whose class is a real Activity of that package are considered;
     *  2. the foreground app is decided once it's stable for [SETTLE_MS], measured with
     *     the events' own timestamps: delivery can be delayed (e.g. the OS pauses our
     *     process) and then events arrive in a burst, which must be replayed in order;
     *  3. the first stable app after the service (re)connects is not counted.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        // Overlays (status bar, keyboard, share sheet...) don't mean we left the current app.
        if (pkg in ignored) return
        val t = event.eventTime // uptimeMillis when the event happened
        Log.v(
            TAG, "event $pkg/$cls changes=${event.contentChangeTypes} " +
                "lag=${SystemClock.uptimeMillis() - t}ms"
        )
        // Pane changes inside an app (miniplayer, drawers...) are not window switches.
        if (event.contentChangeTypes != 0) return
        if (!isActivity(pkg, cls)) {
            Log.d(TAG, "ignore non-activity $pkg/$cls")
            return
        }
        // The previous candidate stayed in front long enough before this event: it was a
        // real foreground app, even if we only learn it now (late burst).
        pendingPkg?.let { if (t - pendingAt >= SETTLE_MS) onForeground(it, live = false) }
        pendingPkg = pkg
        pendingAt = t
        handler.removeCallbacks(settle)
        handler.postDelayed(settle, (t + SETTLE_MS - SystemClock.uptimeMillis()).coerceAtLeast(0))
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingPkg: String? = null
    private var pendingAt = 0L
    /** No newer event arrived for [SETTLE_MS]: the candidate is what's on screen now. */
    private val settle = Runnable { pendingPkg?.let { onForeground(it, live = true) } }

    /** Foreground app as last decided. */
    private var current: String? = null
    private var initialized = false

    /**
     * [live] = the app is on screen right now. A non-live decision comes from replaying
     * a late burst: the open still counts, but there's nothing on screen left to block.
     */
    private fun onForeground(pkg: String, live: Boolean) {
        if (pkg == current) return
        current = pkg

        if (!initialized) {
            initialized = true
            Log.d(TAG, "initial foreground $pkg (not counted)")
            return
        }
        if (pkg == packageName) return
        val limit = store.limitOf(pkg)
        if (limit == null) {
            Log.d(TAG, "foreground $pkg (no limit)")
            return
        }

        val used = store.countOf(pkg)
        if (used >= limit) {
            Log.d(TAG, "BLOCK $pkg ($used/$limit)${if (live) "" else " - already left, not shown"}")
            if (live) block(pkg, limit)
        } else {
            store.increment(pkg)
            Log.d(TAG, "open $pkg -> ${used + 1}/$limit")
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
        private const val SETTLE_MS = 400L
    }
}
