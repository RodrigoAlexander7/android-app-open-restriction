package net.jakstech.openlimit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LimitStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var time: FakeTime
    private lateinit var store: LimitStore

    @Before fun setUp() {
        dir = tmp.newFolder()
        time = FakeTime()
        store = LimitStore(dir) { time.wall }
    }

    private fun use(pkg: String, times: Int) = repeat(times) { store.increment(pkg) }

    // ---------- limits ----------

    @Test fun `empty store has no limits and zero counts`() {
        assertTrue(store.limits().isEmpty())
        assertNull(store.limitOf(WA))
        assertEquals(0, store.countOf(WA))
        assertFalse(store.isExhausted(WA))
    }

    @Test fun `set, change and remove a limit`() {
        assertTrue(store.setLimit(WA, 3))
        assertEquals(3, store.limitOf(WA))
        assertTrue(store.setLimit(WA, 5))
        assertEquals(5, store.limitOf(WA))
        assertTrue(store.setLimit(WA, null))
        assertNull(store.limitOf(WA))
    }

    @Test fun `negative limit is rejected`() {
        assertFalse(store.setLimit(WA, -1))
        assertNull(store.limitOf(WA))
    }

    @Test fun `limit 0 means always exhausted`() {
        store.setLimit(WA, 0)
        assertTrue(store.isExhausted(WA))
    }

    @Test fun `limits are independent per app`() {
        store.setLimit(WA, 1)
        store.setLimit(YT, 3)
        use(WA, 1)
        assertTrue(store.isExhausted(WA))
        assertFalse(store.isExhausted(YT))
        assertEquals(0, store.countOf(YT))
    }

    // ---------- anti-bypass: exhausted limits ----------

    @Test fun `exhausted limit cannot be raised or removed`() {
        store.setLimit(WA, 3)
        use(WA, 3)
        assertFalse("raise", store.setLimit(WA, 4))
        assertFalse("remove", store.setLimit(WA, null))
        assertEquals(3, store.limitOf(WA))
    }

    @Test fun `exhausted limit can still be lowered or kept`() {
        store.setLimit(WA, 3)
        use(WA, 3)
        assertTrue(store.setLimit(WA, 3))
        assertTrue(store.setLimit(WA, 1))
        assertEquals(1, store.limitOf(WA))
    }

    @Test fun `limit not yet exhausted can be raised or removed`() {
        store.setLimit(WA, 3)
        use(WA, 2)
        assertTrue(store.setLimit(WA, 10))
        assertTrue(store.setLimit(WA, null))
    }

    @Test fun `exhausted lock lifts when the hour changes`() {
        store.setLimit(WA, 3)
        use(WA, 3)
        time.toNextHour()
        assertFalse(store.isExhausted(WA))
        assertTrue(store.setLimit(WA, null))
    }

    // ---------- hourly counters ----------

    @Test fun `counts accumulate within the hour`() {
        use(WA, 2)
        time.advance(50 * MIN) // 10:05 -> 10:55, same hour
        use(WA, 1)
        assertEquals(3, store.countOf(WA))
    }

    @Test fun `counts reset at the next full hour, not 60 minutes later`() {
        time = FakeTime(FakeTime.at(2026, 11, 6, 10, 59))
        store = LimitStore(dir) { time.wall }
        use(WA, 3)
        time.advance(2 * MIN) // 11:01
        assertEquals(0, store.countOf(WA))
        assertTrue(store.counts().isEmpty())
    }

    @Test fun `first increment of a new hour starts from 1 and drops old apps`() {
        use(WA, 3)
        use(YT, 2)
        time.toNextHour()
        store.increment(WA)
        assertEquals(1, store.countOf(WA))
        assertEquals(0, store.countOf(YT))
    }

    @Test fun `counts survive midnight correctly`() {
        time = FakeTime(FakeTime.at(2026, 11, 6, 23, 30))
        store = LimitStore(dir) { time.wall }
        use(WA, 2)
        time.advance(40 * MIN) // 00:10 next day
        assertEquals(0, store.countOf(WA))
    }

    // ---------- persistence / multi-process ----------

    @Test fun `data survives a new instance (reboot or other process)`() {
        store.setLimit(WA, 3)
        use(WA, 2)
        store.setPauseSeconds(30)
        val other = LimitStore(dir) { time.wall }
        assertEquals(3, other.limitOf(WA))
        assertEquals(2, other.countOf(WA))
        assertEquals(30, other.pauseSeconds())
    }

    @Test fun `a write from one instance is seen by another immediately`() {
        val ui = LimitStore(dir) { time.wall }
        val watcher = LimitStore(dir) { time.wall }
        ui.setLimit(WA, 2)
        assertEquals(2, watcher.limitOf(WA))
        watcher.increment(WA)
        assertEquals(1, ui.countOf(WA))
    }

    @Test fun `corrupt files are tolerated`() {
        File(dir, "limits.properties").writeText("com.whatsapp=abc\n\u0000garbage\n")
        File(dir, "counts.properties").writeText("not a properties file at all")
        assertNull(store.limitOf(WA))
        assertEquals(0, store.countOf(WA))
        assertTrue(store.setLimit(WA, 2))
        assertEquals(2, store.limitOf(WA))
    }

    // ---------- minimum pause ----------

    @Test fun `pause defaults to 45 s`() {
        assertEquals(45, store.pauseSeconds())
    }

    @Test fun `old pause in minutes is ignored`() {
        File(dir, "settings.properties").writeText("pause_minutes=2\n")
        assertEquals(LimitStore.DEFAULT_PAUSE_S, store.pauseSeconds())
    }

    @Test fun `pause is clamped to 0-300 s`() {
        store.setPauseSeconds(-15)
        assertEquals(0, store.pauseSeconds())
        store.setPauseSeconds(10_000)
        assertEquals(300, store.pauseSeconds())
    }

    @Test fun `pause cannot be raised while any app is exhausted, but can be lowered`() {
        store.setLimit(WA, 1)
        store.setLimit(YT, 5)
        use(WA, 1)
        assertTrue(store.anyExhausted())
        assertFalse(store.setPauseSeconds(60))
        assertEquals(45, store.pauseSeconds())
        assertTrue(store.setPauseSeconds(15))
        assertEquals(15, store.pauseSeconds())
    }

    @Test fun `pause can be raised again next hour`() {
        store.setLimit(WA, 1)
        use(WA, 1)
        time.toNextHour()
        assertFalse(store.anyExhausted())
        assertTrue(store.setPauseSeconds(120))
    }

    companion object {
        const val WA = "com.whatsapp"
        const val YT = "com.google.android.youtube"
        const val MIN = 60_000L
    }
}
