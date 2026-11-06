package net.jakstech.openlimit

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.Properties

/**
 * Persistent storage (plain files, survives reboots) shared by two processes:
 *  - the UI process writes `limits.properties` (package -> max opens per hour) and
 *    `settings.properties` (minimum pause)
 *  - the watcher process (accessibility service) writes `counts.properties`
 *    (opens per package for the current clock hour, tagged with that hour)
 *
 * Files are re-read on every access, so each process always sees the other's latest
 * writes (SharedPreferences caches in memory and would go stale across processes).
 * Writes are atomic (temp file + rename).
 *
 * [now] (wall-clock millis) is injectable so tests can move across hours.
 */
class LimitStore(
    dir: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(context.applicationContext.filesDir)

    private val limitsFile = File(dir, "limits.properties")
    private val countsFile = File(dir, "counts.properties")
    private val settingsFile = File(dir, "settings.properties")

    fun limits(): Map<String, Int> = read(limitsFile).toIntMap()

    fun limitOf(pkg: String): Int? = limits()[pkg]

    /** Counts for the current hour; counts saved during a previous hour read as empty. */
    fun counts(): Map<String, Int> {
        val p = read(countsFile)
        if (p.getProperty(KEY_HOUR) != hourKey(now())) return emptyMap()
        return p.toIntMap()
    }

    fun countOf(pkg: String): Int = counts()[pkg] ?: 0

    fun increment(pkg: String) = synchronized(LOCK) {
        val hour = hourKey(now())
        val p = read(countsFile)
        if (p.getProperty(KEY_HOUR) != hour) {
            p.clear()
            p.setProperty(KEY_HOUR, hour)
        }
        p.setProperty(pkg, ((p.getProperty(pkg)?.toIntOrNull() ?: 0) + 1).toString())
        write(countsFile, p)
    }

    /** True when the app used up its opens this hour: its limit can then only get stricter. */
    fun isExhausted(pkg: String): Boolean {
        val current = limitOf(pkg) ?: return false
        return countOf(pkg) >= current
    }

    /**
     * Changes the limit. While the app is exhausted, raising or removing the limit is
     * rejected (it would bypass the block) until the hour resets. Negative limits are
     * invalid. Returns false if rejected.
     */
    fun setLimit(pkg: String, limit: Int?): Boolean = synchronized(LOCK) {
        if (limit != null && limit < 0) return false
        val current = limitOf(pkg)
        if (current != null && isExhausted(pkg) && (limit == null || limit > current)) {
            return false
        }
        val p = read(limitsFile)
        if (limit == null) p.remove(pkg) else p.setProperty(pkg, limit.toString())
        write(limitsFile, p)
        true
    }

    /**
     * Minimum pause: coming back to an app (e.g. after minimizing it) less than this many
     * seconds after leaving it is the same session, not a new open.
     */
    fun pauseSeconds(): Int =
        read(settingsFile).getProperty(KEY_PAUSE)?.toIntOrNull() ?: DEFAULT_PAUSE_S

    /** True if any limited app used up its opens this hour. */
    fun anyExhausted(): Boolean {
        val counts = counts()
        return limits().any { (pkg, limit) -> (counts[pkg] ?: 0) >= limit }
    }

    /**
     * Changes the minimum pause (0..[MAX_PAUSE_S] seconds). A longer pause makes the limits
     * more lenient, so raising it is rejected while any app is exhausted. Returns false if
     * rejected.
     */
    fun setPauseSeconds(seconds: Int): Boolean = synchronized(LOCK) {
        val s = seconds.coerceIn(0, MAX_PAUSE_S)
        if (s > pauseSeconds() && anyExhausted()) return false
        val p = read(settingsFile)
        p.remove(OLD_KEY_PAUSE_MIN)
        p.setProperty(KEY_PAUSE, s.toString())
        write(settingsFile, p)
        true
    }

    /** One-time import of limits saved by v1 (SharedPreferences). Call from the UI process. */
    fun migrateFromPrefs(context: Context) {
        val old = context.getSharedPreferences("limits", Context.MODE_PRIVATE)
        if (old.all.isEmpty() || limitsFile.exists()) return
        val p = Properties()
        old.all.forEach { (k, v) -> if (v is Int) p.setProperty(k, v.toString()) }
        write(limitsFile, p)
        old.edit().clear().apply()
    }

    private fun read(f: File) = Properties().apply {
        runCatching { f.inputStream().use { load(it) } }
    }

    private fun write(f: File, p: Properties) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.outputStream().use { p.store(it, null) }
        tmp.renameTo(f)
    }

    private fun Properties.toIntMap(): Map<String, Int> =
        stringPropertyNames().filter { it != KEY_HOUR }
            .mapNotNull { k -> getProperty(k).toIntOrNull()?.let { k to it } }.toMap()

    companion object {
        private const val KEY_HOUR = "__hour"
        private const val KEY_PAUSE = "pause_seconds"
        private const val OLD_KEY_PAUSE_MIN = "pause_minutes" // v4, ignored from now on
        const val DEFAULT_PAUSE_S = 45
        const val MAX_PAUSE_S = 300
        const val PAUSE_STEP_S = 15
        private val LOCK = Any()

        /** Local clock hour the given instant belongs to, e.g. "2026110609". */
        fun hourKey(millis: Long): String =
            SimpleDateFormat("yyyyMMddHH", Locale.US).format(Date(millis))

        /** Millis of the next full hour, when counters reset. */
        fun nextResetMillis(): Long = Calendar.getInstance().apply {
            add(Calendar.HOUR_OF_DAY, 1)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
