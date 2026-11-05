package net.jakstech.openlimit

import android.accessibilityservice.AccessibilityService
import android.content.Intent
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

    override fun onServiceConnected() {
        store = LimitStore(this)
        ignored = buildIgnoredSet()
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
        startActivity(
            Intent(this, BlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(BlockActivity.EXTRA_PKG, pkg)
                .putExtra(BlockActivity.EXTRA_LIMIT, limit)
        )
        lastPkg = packageName
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
}
