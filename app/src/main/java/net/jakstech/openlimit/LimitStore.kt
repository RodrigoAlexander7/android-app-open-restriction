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
 *  - the UI process writes `limits.properties` (package -> max opens per hour)
 *  - the watcher process (accessibility service) writes `counts.properties`
 *    (opens per package for the current clock hour, tagged with that hour)
 *
 * Files are re-read on every access, so each process always sees the other's latest
 * writes (SharedPreferences caches in memory and would go stale across processes).
 * Writes are atomic (temp file + rename).
 */
class LimitStore(context: Context) {
    private val dir = context.applicationContext.filesDir
    private val limitsFile = File(dir, "limits.properties")
    private val countsFile = File(dir, "counts.properties")

    fun limits(): Map<String, Int> = read(limitsFile).toIntMap()

    fun limitOf(pkg: String): Int? = limits()[pkg]

    /** Counts for the current hour; counts saved during a previous hour read as empty. */
    fun counts(): Map<String, Int> {
        val p = read(countsFile)
        if (p.getProperty(KEY_HOUR) != currentHourKey()) return emptyMap()
        return p.toIntMap()
    }

    fun countOf(pkg: String): Int = counts()[pkg] ?: 0

    fun increment(pkg: String) = synchronized(LOCK) {
        val now = currentHourKey()
        val p = read(countsFile)
        if (p.getProperty(KEY_HOUR) != now) {
            p.clear()
            p.setProperty(KEY_HOUR, now)
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
     * rejected (it would bypass the block) until the hour resets. Returns false if rejected.
     */
    fun setLimit(pkg: String, limit: Int?): Boolean = synchronized(LOCK) {
        val current = limitOf(pkg)
        if (current != null && isExhausted(pkg) && (limit == null || limit > current)) {
            return false
        }
        val p = read(limitsFile)
        if (limit == null) p.remove(pkg) else p.setProperty(pkg, limit.toString())
        write(limitsFile, p)
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
        private val LOCK = Any()

        fun currentHourKey(): String =
            SimpleDateFormat("yyyyMMddHH", Locale.US).format(Date())

        /** Millis of the next full hour, when counters reset. */
        fun nextResetMillis(): Long = Calendar.getInstance().apply {
            add(Calendar.HOUR_OF_DAY, 1)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
