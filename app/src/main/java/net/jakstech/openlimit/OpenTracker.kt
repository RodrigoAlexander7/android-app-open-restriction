package net.jakstech.openlimit

/**
 * Pure decision logic for "is this a new open of a limited app?" (no Android APIs, so it
 * can be unit-tested on the JVM). The accessibility service filters raw events (ignored
 * packages, non-Activity windows, pane changes) and feeds the rest here.
 *
 * Window events are noisy, so we count *sessions*, not events:
 *  1. the foreground app is decided once it's stable for [settleMs], measured with the
 *     events' own timestamps: delivery can be delayed (e.g. the OS pauses our process)
 *     and then events arrive in a burst, which must be replayed in order;
 *  2. the first stable app after the service (re)connects is not counted;
 *  3. coming back to an app less than the "minimum pause" after leaving an allowed
 *     session (e.g. minimize and return) resumes that session instead of opening anew;
 *  4. a block ends any earlier session, so a blocked app can't be "resumed".
 */
class OpenTracker(
    private val store: LimitStore,
    private val ownPackage: String,
    private val clock: Clock,
    private val settleMs: Long = SETTLE_MS,
) {
    /** Both clocks Android gives us: uptime (event timestamps) and elapsed (includes sleep). */
    interface Clock {
        fun uptimeMillis(): Long
        fun elapsedRealtime(): Long
    }

    sealed interface Decision {
        val pkg: String
        data class Open(override val pkg: String, val used: Int, val limit: Int) : Decision
        data class Resume(override val pkg: String, val awayMs: Long) : Decision
        /** [live] = the app is on screen now; false when learnt from a late burst. */
        data class Block(
            override val pkg: String, val used: Int, val limit: Int, val live: Boolean,
        ) : Decision
    }

    private var pendingPkg: String? = null
    private var pendingAt = 0L // uptime of the pending candidate's event

    private var current: String? = null
    private var currentAllowed = false
    private var initialized = false
    /** When each app last left the foreground after an allowed session (elapsedRealtime). */
    private val leftAt = HashMap<String, Long>()

    /**
     * A real Activity of [pkg] came to the front at [eventUptime] (the event's own time).
     * Returns a decision when this event proves the previous candidate was stable (late
     * burst replay); the caller must then call [settle] at [settleDeadline].
     */
    fun onActivityEvent(pkg: String, eventUptime: Long): Decision? {
        var replayed: Decision? = null
        pendingPkg?.let {
            if (eventUptime - pendingAt >= settleMs) {
                replayed = decide(it, live = false, at = toElapsed(pendingAt))
            }
        }
        pendingPkg = pkg
        pendingAt = eventUptime
        return replayed
    }

    /** Uptime at which [settle] should run if no newer event arrives. */
    fun settleDeadline(): Long = pendingAt + settleMs

    /** No newer event arrived for [settleMs]: the candidate is what's on screen now. */
    fun settle(): Decision? = pendingPkg?.let { decide(it, live = true, at = toElapsed(pendingAt)) }

    /** Uptime stops during deep sleep; the pause must include it, so use elapsed time. */
    private fun toElapsed(uptime: Long) =
        clock.elapsedRealtime() - (clock.uptimeMillis() - uptime)

    private fun decide(pkg: String, live: Boolean, at: Long): Decision? {
        if (pkg == current) return null
        current?.let { if (currentAllowed) leftAt[it] = at }
        current = pkg
        currentAllowed = true

        if (!initialized) {
            initialized = true
            return null
        }
        if (pkg == ownPackage) return null
        val limit = store.limitOf(pkg) ?: return null

        val away = leftAt[pkg]?.let { at - it }
        if (away != null && away < store.pauseSeconds() * 1_000L) {
            return Decision.Resume(pkg, away)
        }
        val used = store.countOf(pkg)
        return if (used >= limit) {
            currentAllowed = false
            leftAt.remove(pkg) // a block ends any earlier session: nothing left to resume
            Decision.Block(pkg, used, limit, live)
        } else {
            store.increment(pkg)
            Decision.Open(pkg, used + 1, limit)
        }
    }

    companion object {
        const val SETTLE_MS = 400L
    }
}
