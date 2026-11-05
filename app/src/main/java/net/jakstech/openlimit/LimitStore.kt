package net.jakstech.openlimit

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Persistent storage (SharedPreferences, survives reboots) for:
 *  - per-app hourly limits ("limits" file: package -> max opens per hour, absent = no limit)
 *  - per-app open counts for the current clock hour ("counts" file, wiped when the hour changes)
 */
class LimitStore(context: Context) {
    private val app = context.applicationContext
    val limitsPrefs: SharedPreferences = app.getSharedPreferences("limits", Context.MODE_PRIVATE)
    val countsPrefs: SharedPreferences = app.getSharedPreferences("counts", Context.MODE_PRIVATE)

    fun limits(): Map<String, Int> =
        limitsPrefs.all.mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.toMap()

    fun limitOf(pkg: String): Int? =
        if (limitsPrefs.contains(pkg)) limitsPrefs.getInt(pkg, 0) else null

    /** True when the app used up its opens this hour: its limit can then only get stricter. */
    fun isExhausted(pkg: String): Boolean {
        val current = limitOf(pkg) ?: return false
        return countOf(pkg) >= current
    }

    /**
     * Changes the limit. While the app is exhausted, raising or removing the limit is
     * rejected (it would bypass the block) until the hour resets. Returns false if rejected.
     */
    fun setLimit(pkg: String, limit: Int?): Boolean {
        val current = limitOf(pkg)
        if (current != null && isExhausted(pkg) && (limit == null || limit > current)) return false
        limitsPrefs.edit().apply {
            if (limit == null) remove(pkg) else putInt(pkg, limit)
        }.apply()
        return true
    }

    fun counts(): Map<String, Int> {
        rollHourIfNeeded()
        return countsPrefs.all.filterKeys { it != KEY_HOUR }
            .mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.toMap()
    }

    fun countOf(pkg: String): Int {
        rollHourIfNeeded()
        return countsPrefs.getInt(pkg, 0)
    }

    fun increment(pkg: String) {
        rollHourIfNeeded()
        countsPrefs.edit().putInt(pkg, countsPrefs.getInt(pkg, 0) + 1).apply()
    }

    /** Wipes counts as soon as we observe a new clock hour (e.g. 14:xx -> 15:00). */
    private fun rollHourIfNeeded() {
        val now = currentHourKey()
        if (countsPrefs.getString(KEY_HOUR, null) != now) {
            countsPrefs.edit().clear().putString(KEY_HOUR, now).commit()
        }
    }

    companion object {
        private const val KEY_HOUR = "__hour"

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
