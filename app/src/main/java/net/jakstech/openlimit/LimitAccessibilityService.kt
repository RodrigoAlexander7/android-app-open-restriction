package net.jakstech.openlimit

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
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
    private var lastPkg: String? = null
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

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Overlays (status bar, keyboard, share sheet...) don't mean we left the current app.
        if (pkg in ignored) return
        if (pkg == lastPkg) return
        lastPkg = pkg

        if (pkg == packageName) return
        val limit = store.limitOf(pkg) ?: return

        if (store.countOf(pkg) >= limit) {
            block(pkg, limit)
        } else {
            store.increment(pkg)
        }
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

    companion object {
        private const val CHANNEL = "watcher"
    }
}
