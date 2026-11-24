package ai.togohq.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTest {
  private val minute = 60_000L

  private fun sessionIds(h: Harness) = h.network.events.map { it.string("sessionId") }

  /**
   * The system kills a backgrounded app with no callback. A session held only
   * in memory would end there and the relaunch would start a new one —
   * inflating session counts. The record is persisted and restored while
   * inside both windows.
   */
  @Test fun aProcessKilledMidSessionAndRelaunchedInsideTheWindowKeepsItsSession() {
    val h = Harness()
    h.engine.track("screen_viewed")
    val before = h.engine.sessionId
    assertNotNull(before)

    // Killed. Ten minutes later, a new process over the same stores, with a
    // monotonic clock that restarted from a new baseline.
    h.clock.wall += 10 * minute
    h.clock.monotonic = 1_000
    h.start()
    assertEquals(before, h.engine.sessionId)
    h.engine.track("screen_viewed")
    h.flush()

    assertEquals(listOf("\$session_started", "screen_viewed", "screen_viewed"), h.network.names)
    assertEquals(setOf(before), sessionIds(h).toSet())
  }

  /** The positive half: past the idle window, the relaunch does start a new one. */
  @Test fun aRelaunchPastTheIdleWindowStartsANewSession() {
    val h = Harness()
    h.engine.track("screen_viewed")
    val before = h.engine.sessionId

    h.clock.wall += 31 * minute
    h.clock.monotonic = 1_000
    h.start()
    assertNull("a read never reports a session that has ended", h.engine.sessionId)
    h.engine.track("screen_viewed")
    h.flush()

    assertEquals(listOf("\$session_started", "screen_viewed", "\$session_started", "screen_viewed"), h.network.names)
    val ids = sessionIds(h)
    assertEquals(before, ids[0])
    assertNotEquals(before, ids[2])
    assertEquals(ids[2], ids[3])
  }

  /** The queue survives the kill too, and the relaunch sends what was left. */
  @Test fun eventsQueuedBeforeAKillAreSentAfterTheRelaunch() {
    val h = Harness()
    h.network.answers.add(TransportResponse(null))
    h.engine.track("checkout_started")
    h.flush()
    assertEquals(1, h.network.sent.size)

    h.start()
    h.flush()
    assertEquals(2, h.network.sent.size)
    val second = (h.network.sent[1]["events"] as JsonValue.Arr).values.map { (it as JsonValue.Obj).string("eventName") }
    assertEquals(listOf("\$session_started", "checkout_started"), second)
  }

  @Test fun thirtyMinutesIdleCutsAndActivityExtends() {
    val h = Harness()
    h.engine.track("a")
    h.clock.advance(29 * minute)
    h.engine.track("b")
    h.clock.advance(29 * minute)
    h.engine.track("c") // 58 minutes after the start, 29 after the last event
    h.clock.advance(31 * minute)
    h.engine.track("d")
    h.flush()
    assertEquals(listOf("\$session_started", "a", "b", "c", "\$session_started", "d"), h.network.names)
  }

  /** The 24-hour cap is not configurable: a session kept alive by activity still ends. */
  @Test fun theTwentyFourHourCapEndsASessionThatNeverIdles() {
    val h = Harness()
    h.engine.track("a")
    repeat(50) { // 50 × 29 min = 24 h 10 min
      h.clock.advance(29 * minute)
      h.engine.track("tick")
    }
    h.flush()
    while (h.engine.queuedCount > 0) h.flush()
    assertEquals(2, h.network.names.count { it == "\$session_started" })
  }

  /** `<= 0` DISABLES sessions — no sessionId at all — and is never read as instant expiry. */
  @Test fun aTimeoutOfZeroOrLessSendsNoSessionAtAll() {
    for (timeout in listOf(0.0, -1.0)) {
      val h = Harness(sessionTimeoutSeconds = timeout)
      h.engine.track("a")
      h.engine.track("b")
      h.flush()
      assertEquals(listOf("a", "b"), h.network.names)
      assertEquals(listOf(null, null), sessionIds(h))
      assertEquals(str("disabled"), h.network.sent[0]["sessionIdMode"])
      assertNull(h.engine.sessionId)
    }
  }

  /**
   * A mis-read setting must neither crash setup() nor flip the rule: a huge or
   * infinite value behaves as the 24-hour cap, minus infinity disables, and NaN
   * gets the default.
   */
  @Test fun extremeTimeoutsNeitherCrashNorTurnSessionsOn() {
    for (timeout in listOf(1e16, Double.MAX_VALUE, Double.POSITIVE_INFINITY, Long.MAX_VALUE.toDouble())) {
      val h = Harness(sessionTimeoutSeconds = timeout)
      h.engine.track("a")
      h.clock.advance(23 * 60 * minute)
      h.engine.track("b")
      h.flush()
      assertEquals("$timeout", listOf("\$session_started", "a", "b"), h.network.names)
      assertTrue(h.logs.contains("sessionTimeout is seconds, max 86400"))
    }
    val off = Harness(sessionTimeoutSeconds = Double.NEGATIVE_INFINITY)
    off.engine.track("a")
    off.flush()
    assertEquals(str("disabled"), off.network.sent[0]["sessionIdMode"])
    assertEquals(listOf("a"), off.network.names)

    val nan = Harness(sessionTimeoutSeconds = Double.NaN)
    nan.engine.track("a")
    nan.clock.advance(29 * minute)
    nan.engine.track("b")
    nan.flush()
    assertEquals(listOf("\$session_started", "a", "b"), nan.network.names)

    // Under a millisecond is still a timeout, never "off".
    val tiny = Harness(sessionTimeoutSeconds = 0.0001)
    tiny.engine.track("a")
    tiny.flush()
    assertEquals(str("sdk"), tiny.network.sent[0]["sessionIdMode"])
  }

  /** Within one process idle is measured on the monotonic clock, so a wall clock moved forward does not cut it… */
  @Test fun aWallClockJumpInsideOneProcessDoesNotCutTheSession() {
    val h = Harness()
    h.engine.track("a")
    h.clock.wall += 2 * 60 * minute // the wall jumped two hours
    h.clock.monotonic += minute // one real minute passed
    h.engine.track("b")
    h.flush()
    assertEquals(listOf("\$session_started", "a", "b"), h.network.names)
  }

  /** …and a clock that moved BACKWARDS is never trusted to extend one. */
  @Test fun aWallClockThatMovedBackwardsCutsTheSession() {
    val h = Harness()
    h.engine.track("a")
    h.clock.wall -= 5 * minute
    h.clock.monotonic += minute
    h.engine.track("b")
    h.flush()
    assertEquals(listOf("\$session_started", "a", "\$session_started", "b"), h.network.names)
  }

  @Test fun resetEndsTheSessionAndRotatesTheAnonymousIdButKeepsTheDevice() {
    val h = Harness()
    h.engine.track("a")
    val anonymous = h.engine.anonymousId!!
    val device = h.engine.deviceId!!
    h.engine.reset()
    assertNull("reset mints nothing: the next event opens the session", h.engine.sessionId)
    h.engine.track("b")
    h.flush()
    assertEquals(listOf("\$session_started", "a", "\$session_started", "b"), h.network.names)
    val events = h.network.events
    assertNotEquals(str(anonymous), events[3]["anonymousId"])
    assertEquals(str(device), events[3]["deviceId"])
    assertNotEquals(events[1]["sessionId"], events[3]["sessionId"])
  }

  /** No human present (a background start): an event joins an open session and never opens one. */
  @Test fun anEventWithNobodyPresentNeverOpensASession() {
    var present = false
    val h = Harness()
    val engine = Engine(
      h.options, h.identity, generatedDeviceIds, h.queueStore, h.network, h.executor, h.executor, h.clock,
      Logger { }, humanPresent = { present },
    )
    engine.track("push_received")
    present = true
    engine.track("screen_viewed")
    present = false
    engine.track("sync_finished")
    engine.flush()
    assertEquals(listOf("push_received", "\$session_started", "screen_viewed", "sync_finished"), h.network.names)
    val ids = sessionIds(h)
    assertNull(ids[0])
    assertEquals(ids[1], ids[3])
  }
}
