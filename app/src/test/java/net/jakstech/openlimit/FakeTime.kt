package net.jakstech.openlimit

import java.util.Calendar

/**
 * Controllable clocks for tests:
 *  - [wall]: wall-clock millis (decides the clock hour for counters)
 *  - [uptime]: SystemClock.uptimeMillis, stops while the phone is in deep sleep
 *  - [elapsed]: SystemClock.elapsedRealtime, keeps running during sleep
 */
class FakeTime(startWall: Long = at(2026, 11, 6, 10, 5)) : OpenTracker.Clock {
    var wall = startWall
    var uptime = 1_000_000L
    var elapsed = 5_000_000L

    override fun uptimeMillis() = uptime
    override fun elapsedRealtime() = elapsed

    /** Phone awake: every clock advances. */
    fun advance(ms: Long) {
        wall += ms; uptime += ms; elapsed += ms
    }

    /** Phone in deep sleep (screen off): uptime is frozen. */
    fun sleep(ms: Long) {
        wall += ms; elapsed += ms
    }

    /** Jump the wall clock to the next full hour (+[plusMinutes]). */
    fun toNextHour(plusMinutes: Int = 1) {
        val next = Calendar.getInstance().apply {
            timeInMillis = wall
            add(Calendar.HOUR_OF_DAY, 1)
            set(Calendar.MINUTE, plusMinutes)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        advance(next - wall)
    }

    companion object {
        /** Local-time instant. */
        fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long = Calendar.getInstance().apply {
            clear()
            set(y, mo - 1, d, h, mi, 0)
        }.timeInMillis
    }
}
