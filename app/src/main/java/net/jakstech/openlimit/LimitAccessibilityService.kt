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
     *  2. the foreground app is decided once it's stable for [SETTLE_MS];
     *  3. coming back to the same app within [REENTRY_GRACE_MS] of leaving an allowed
     *     session is the same session (cancelled gestures, flicker);
     *  4. the first stable app after the service (re)connects is not counted.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        // Overlays (status bar, keyboard, share sheet...) don't mean we left the current app.
        if (pkg in ignored) return
        // Pane changes inside an app (miniplayer, drawers...) are not window switches.
        if (event.contentChangeTypes != 0) return
        if (!isActivity(pkg, cls)) {
            Log.d(TAG, "ignore non-activity $pkg/$cls")
            return
        }
        pending = pkg
        handler.removeCallbacks(settle)
        handler.postDelayed(settle, SETTLE_MS)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pending: String? = null
    private val settle = Runnable { pending?.let { onForeground(it) } }

    /** Foreground app as last decided, and whether its session was allowed. */
    private var current: String? = null
    private var currentAllowed = true
    private var initialized = false
    /** When each app last left the foreground after an allowed session (elapsedRealtime). */
    private val leftAt = HashMap<String, Long>()

    private fun onForeground(pkg: String) {
        if (pkg == current) return
        val now = SystemClock.elapsedRealtime()
        current?.let { if (currentAllowed) leftAt[it] = now }
        current = pkg
        currentAllowed = true

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

        val left = leftAt[pkg]
        if (left != null && now - left < REENTRY_GRACE_MS) {
            Log.d(TAG, "back to $pkg after ${now - left}ms: same session")
            return
        }
        val used = store.countOf(pkg)
        if (used >= limit) {
            Log.d(TAG, "BLOCK $pkg ($used/$limit)")
            currentAllowed = false
            block(pkg, limit)
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
        private const val REENTRY_GRACE_MS = 3_000L
    }
}
