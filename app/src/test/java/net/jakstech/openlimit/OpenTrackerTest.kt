package net.jakstech.openlimit

import net.jakstech.openlimit.OpenTracker.Decision
import net.jakstech.openlimit.OpenTracker.Decision.Block
import net.jakstech.openlimit.OpenTracker.Decision.Open
import net.jakstech.openlimit.OpenTracker.Decision.Resume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Drives [OpenTracker] like the accessibility service does: Activity events with their
 * own timestamps, plus the settle timer. Each scenario mirrors something seen on a real
 * phone (MIUI) while building the app.
 */
class OpenTrackerTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var time: FakeTime
    private lateinit var store: LimitStore
    private lateinit var tracker: OpenTracker
    private val decisions = mutableListOf<Decision>()

    @Before fun setUp() {
        time = FakeTime()
        store = LimitStore(tmp.newFolder()) { time.wall }
        store.setLimit(WA, 3)
        store.setPauseSeconds(0) // most scenarios test raw opens; pause has its own tests
        tracker = OpenTracker(store, OWN, time)
        show(HOME) // first app after (re)connect: initializes, never counted
    }

    // ---------- helpers ----------

    /** An Activity event of [pkg] delivered right now (no delay). */
    private fun event(pkg: String) {
        tracker.onActivityEvent(pkg, time.uptime)?.let(decisions::add)
    }

    /** Settle timer fires if nothing newer arrived. */
    private fun settleTimer() {
        val wait = tracker.settleDeadline() - time.uptime
        if (wait > 0) time.advance(wait)
        tracker.settle()?.let(decisions::add)
    }

    /** [pkg] comes to the front and stays [stayMs] (live, events delivered on time). */
    private fun show(pkg: String, stayMs: Long = 2_000) {
        event(pkg)
        settleTimer()
        time.advance(stayMs - OpenTracker.SETTLE_MS)
    }

    /** Open [pkg] from the home screen and go back home. */
    private fun openAndLeave(pkg: String = WA, stayMs: Long = 2_000) {
        show(pkg, stayMs)
        show(HOME)
    }

    private fun opens(pkg: String = WA) = decisions.filterIsInstance<Open>().filter { it.pkg == pkg }
    private fun blocks(pkg: String = WA) = decisions.filterIsInstance<Block>().filter { it.pkg == pkg }
    private fun resumes(pkg: String = WA) = decisions.filterIsInstance<Resume>().filter { it.pkg == pkg }

    // ---------- basic counting ----------

    @Test fun `first app after service start is not counted`() {
        tracker = OpenTracker(store, OWN, time)
        show(WA)
        assertTrue(decisions.isEmpty())
        assertEquals(0, store.countOf(WA))
    }

    @Test fun `limit 3 - three opens allowed, fourth blocked`() {
        repeat(4) { openAndLeave() }
        assertEquals(listOf(1, 2, 3), opens().map { it.used })
        assertEquals(1, blocks().size)
        assertTrue(blocks().single().live)
        assertEquals(3, store.countOf(WA))
    }

    @Test fun `every further open after the limit is blocked, never counted`() {
        repeat(6) { openAndLeave() }
        assertEquals(3, opens().size)
        assertEquals(3, blocks().size)
        assertEquals(3, store.countOf(WA))
    }

    @Test fun `limit 0 blocks the very first open`() {
        store.setLimit(YT, 0)
        openAndLeave(YT)
        assertTrue(opens(YT).isEmpty())
        assertEquals(1, blocks(YT).size)
    }

    @Test fun `apps without a limit are never counted or blocked`() {
        repeat(5) { openAndLeave(OTHER) }
        assertTrue(decisions.isEmpty())
        assertEquals(0, store.countOf(OTHER))
    }

    @Test fun `our own app is never counted`() {
        store.setLimit(OWN, 0)
        openAndLeave(OWN)
        assertTrue(decisions.isEmpty())
    }

    @Test fun `switching app to app directly counts each limited app`() {
        store.setLimit(YT, 3)
        show(WA); show(YT); show(WA)
        assertEquals(2, opens(WA).size)
        assertEquals(1, opens(YT).size)
    }

    @Test fun `staying in the app (internal screens, dialogs) is one open`() {
        show(WA)
        repeat(5) { show(WA, stayMs = 500) } // more Activity events of the same app
        show(HOME)
        assertEquals(1, opens().size)
    }

    // ---------- noisy events ----------

    @Test fun `launch flicker app-home-app within settle time counts once`() {
        event(WA); time.advance(100)
        event(HOME); time.advance(100) // launcher event during the launch animation
        event(WA)
        settleTimer()
        time.advance(2_000)
        show(HOME)
        assertEquals(1, opens().size)
    }

    @Test fun `late event from a closing app does not count as an open`() {
        openAndLeave()
        // home, then a stray event from WA 100 ms later, then home again (close animation)
        event(HOME); time.advance(100)
        event(WA); time.advance(100)
        event(HOME)
        settleTimer()
        assertEquals(1, opens().size)
    }

    @Test fun `duplicate events of the same app count once`() {
        event(WA); event(WA); time.advance(50); event(WA)
        settleTimer()
        show(HOME)
        assertEquals(1, opens().size)
    }

    // ---------- delayed delivery (process paused by the OS) ----------

    @Test fun `late burst is replayed in order - opens still count`() {
        // Real timeline: WA 2 s, home 2 s, WA 2 s, home. Our process was paused, so all
        // four events are delivered together afterwards, with their original timestamps.
        val t0 = time.uptime
        time.advance(8_000)
        listOf(WA to 0L, HOME to 2_000L, WA to 4_000L, HOME to 6_000L).forEach { (pkg, at) ->
            tracker.onActivityEvent(pkg, t0 + at)?.let(decisions::add)
        }
        tracker.settle()?.let(decisions::add)
        assertEquals(2, opens().size)
        assertEquals(2, store.countOf(WA))
    }

    @Test fun `block learnt from a late burst is not shown (app already left)`() {
        repeat(3) { openAndLeave() }
        val t0 = time.uptime
        time.advance(5_000)
        tracker.onActivityEvent(WA, t0)?.let(decisions::add)
        tracker.onActivityEvent(HOME, t0 + 2_000)?.let(decisions::add)
        tracker.settle()?.let(decisions::add)
        assertEquals(1, blocks().size)
        assertEquals(false, blocks().single().live)
    }

    @Test fun `burst with only flicker still counts nothing extra`() {
        val t0 = time.uptime
        time.advance(3_000)
        // WA shown 100 ms (less than settle) then home: not a real foreground
        tracker.onActivityEvent(WA, t0)?.let(decisions::add)
        tracker.onActivityEvent(HOME, t0 + 100)?.let(decisions::add)
        tracker.settle()?.let(decisions::add)
        assertTrue(opens().isEmpty())
    }

    // ---------- minimum pause ----------

    @Test fun `returning within the pause is the same session`() {
        store.setPauseSeconds(45)
        openAndLeave()
        time.advance(10_000)
        openAndLeave()
        assertEquals(1, opens().size)
        assertEquals(1, resumes().size)
    }

    @Test fun `returning after the pause is a new open`() {
        store.setPauseSeconds(45)
        openAndLeave()
        time.advance(50_000)
        openAndLeave()
        assertEquals(2, opens().size)
        assertTrue(resumes().isEmpty())
    }

    @Test fun `pause is measured from leaving, not from opening`() {
        store.setPauseSeconds(45)
        openAndLeave(stayMs = 10 * 60_000) // used 10 min, then left
        time.advance(20_000)
        openAndLeave()
        assertEquals(1, opens().size)
    }

    @Test fun `pause exactly reached counts as a new open`() {
        store.setPauseSeconds(15)
        show(WA)
        event(HOME)
        val leftAtUptime = time.uptime
        settleTimer()
        time.advance(15_000 - (time.uptime - leftAtUptime))
        event(WA) // exactly 15 s after leaving
        settleTimer()
        assertEquals(2, opens().size)
    }

    @Test fun `pause 0 - every return counts`() {
        repeat(3) { openAndLeave() }
        assertEquals(3, opens().size)
    }

    @Test fun `screen-off time counts toward the pause`() {
        store.setPauseSeconds(45)
        openAndLeave()
        time.sleep(30 * 60_000) // phone asleep 30 min: uptime frozen, elapsed runs
        openAndLeave()
        assertEquals(2, opens().size)
    }

    @Test fun `resumes do not consume opens - limit still applies after the pause`() {
        store.setPauseSeconds(45)
        repeat(3) {
            openAndLeave()
            time.advance(5_000)
            openAndLeave() // resume
            time.advance(60_000)
        }
        openAndLeave()
        assertEquals(3, opens().size)
        assertEquals(3, resumes().size)
        assertEquals(1, blocks().size)
    }

    @Test fun `a blocked app cannot be resumed within the pause`() {
        store.setPauseSeconds(45)
        repeat(3) { openAndLeave(); time.advance(60_000) }
        openAndLeave() // 4th: BLOCK
        time.advance(5_000)
        openAndLeave() // within 45 s of the block: still blocked, not a resume
        assertEquals(2, blocks().size)
        assertTrue(resumes().isEmpty())
    }

    @Test fun `last allowed session can be resumed even when the app is exhausted`() {
        store.setPauseSeconds(45)
        repeat(2) { openAndLeave(); time.advance(60_000) }
        openAndLeave() // 3rd open: 3/3
        time.advance(5_000)
        openAndLeave() // back within the pause: same (3rd) session, not a block
        assertEquals(1, resumes().size)
        assertTrue(blocks().isEmpty())
    }

    @Test fun `pause is tracked per app`() {
        store.setPauseSeconds(45)
        store.setLimit(YT, 3)
        openAndLeave(WA)
        openAndLeave(YT) // YT never left before: a real open
        time.advance(5_000)
        openAndLeave(WA)
        assertEquals(1, opens(WA).size)
        assertEquals(1, opens(YT).size)
        assertEquals(1, resumes(WA).size)
    }

    // ---------- hourly reset ----------

    @Test fun `blocked app is allowed again in the next hour`() {
        repeat(4) { openAndLeave() }
        assertEquals(1, blocks().size)
        time.toNextHour()
        openAndLeave()
        assertEquals(1, opens().last().used)
    }

    @Test fun `limit changed mid-hour applies to the next open`() {
        repeat(2) { openAndLeave() }
        store.setLimit(WA, 2) // lowered: now exhausted
        openAndLeave()
        assertEquals(1, blocks().size)
    }

    companion object {
        const val WA = "com.whatsapp"
        const val YT = "com.google.android.youtube"
        const val OTHER = "com.android.settings"
        const val HOME = "com.miui.home"
        const val OWN = "net.jakstech.openlimit"
    }
}
