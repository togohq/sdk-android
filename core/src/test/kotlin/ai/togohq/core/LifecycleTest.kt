package ai.togohq.core

import ai.togohq.TogoConsent
import ai.togohq.TogoErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app's lifecycle, decided in the core: the launch, the open, the background, and what a locked device holds. */
class LifecycleTest {
  private val minute = 60_000L
  private val day = 86_400_000L

  private fun event(h: Harness, name: String): JsonValue.Obj? = h.network.events.firstOrNull { it.string("eventName") == name }
  private fun count(h: Harness, name: String) = h.network.names.count { it == name }
  private fun fromBackground(h: Harness) =
    h.network.events.filter { it.string("eventName") == "\$app_opened" }.map { it.obj("properties")["from_background"] }

  // Installed, updated, opened.

  /** The first launch: `$app_installed` at the launch with no session, then the first foreground opens one with `$app_opened`. */
  @Test fun theFirstLaunchSendsAppInstalledThenTheFirstForegroundOpensTheApp() {
    val h = Harness(lifecycle = true)
    h.foreground()
    h.flushAll()

    assertEquals(listOf("\$app_installed", "\$session_started", "\$app_opened"), h.network.names)
    val installed = event(h, "\$app_installed")!!
    assertEquals(obj(), installed["properties"])
    assertNull("a launch is not a person: it never opens a session", installed["sessionId"])
    val opened = event(h, "\$app_opened")!!
    assertEquals(obj("from_background" to JsonValue.Bool(false)), opened["properties"])
    assertNotNull(opened["sessionId"])
    assertEquals("1.2.0", h.identity.stored?.appVersion)
    assertEquals("12", h.identity.stored?.appBuild)
  }

  /** A relaunch of the same build sends neither; the first launch above is the positive half. */
  @Test fun aSecondLaunchOfTheSameBuildSendsNoInstallAndNoUpdate() {
    val h = Harness(lifecycle = true)
    h.flushAll()
    assertEquals(listOf("\$app_installed"), h.network.names)

    h.start()
    h.engine.track("screen_viewed")
    h.flushAll()
    assertEquals(listOf("\$app_installed", "screen_viewed"), h.network.names)
  }

  @Test fun aVersionBumpSendsOneAppUpdatedWithThePreviousValues() {
    val h = Harness(lifecycle = true)
    h.relaunch("1.3.0", "13")
    h.relaunch("1.3.0", "13")
    h.flushAll()

    assertEquals(listOf("\$app_installed", "\$app_updated"), h.network.names)
    assertEquals(
      obj("previous_app_version" to str("1.2.0"), "previous_app_build" to str("12")),
      event(h, "\$app_updated")!!["properties"],
    )
    assertEquals(obj("version" to str("1.3.0"), "build" to str("13")), h.network.sent.last()["app"])
    assertEquals("1.3.0", h.identity.stored?.appVersion)
  }

  /** A build-only change is an update too: internal test tracks often share a version name. */
  @Test fun aBuildOnlyChangeIsAnUpdate() {
    val h = Harness(lifecycle = true)
    h.relaunch("1.2.0", "13")
    h.flushAll()
    assertEquals(listOf("\$app_installed", "\$app_updated"), h.network.names)
    assertEquals(str("12"), event(h, "\$app_updated")!!.obj("properties")["previous_app_build"])
  }

  /**
   * A process started by a broadcast, a job, a push or a content provider
   * starts no activity: no `$app_opened`, and its events open no session. The
   * positive half: when the user does open the app, it is the process's first
   * foreground, `from_background: false`.
   */
  @Test fun aProcessStartedWithNoActivitySendsNoOpenUntilAnActivityStarts() {
    val h = Harness(lifecycle = true)
    h.engine.track("push_received")
    h.flushAll()
    assertEquals(listOf("\$app_installed", "push_received"), h.network.names)
    assertNull("no person: no session", event(h, "push_received")!!["sessionId"])

    h.clock.advance(5 * minute)
    h.foreground()
    h.flushAll()
    assertEquals(listOf("\$app_installed", "push_received", "\$session_started", "\$app_opened"), h.network.names)
    assertEquals(listOf<JsonValue?>(JsonValue.Bool(false)), fromBackground(h))
  }

  @Test fun aReturnFromTheBackgroundIsAnOpenFromBackgroundInTheSameSession() {
    val h = Harness(lifecycle = true)
    h.foreground()
    h.background()
    h.clock.advance(10 * minute)
    h.foreground()
    h.flushAll()

    assertEquals(listOf<JsonValue?>(JsonValue.Bool(false), JsonValue.Bool(true)), fromBackground(h))
    val opens = h.network.events.filter { it.string("eventName") == "\$app_opened" }
    assertEquals(opens[0]["sessionId"], opens[1]["sessionId"])
    assertEquals(1, count(h, "\$session_started"))
  }

  /**
   * Background time is idle time, from the move to the background — not from
   * the last event. Twenty quiet minutes in front and fifteen in the
   * background is fifteen idle minutes; forty in the background is a new session.
   */
  @Test fun backgroundTimeIsIdleTimeFromTheMoveToTheBackground() {
    val h = Harness(lifecycle = true)
    h.foreground()
    h.clock.advance(20 * minute)
    h.background()
    h.clock.advance(15 * minute)
    h.foreground()
    h.flushAll()
    assertEquals("35 minutes since the last event, 15 idle", 1, count(h, "\$session_started"))

    h.background()
    h.clock.advance(40 * minute)
    h.foreground()
    h.flushAll()
    assertEquals(2, count(h, "\$session_started"))
  }

  /** The move to the background writes `lastActivityAt`, persists and flushes; a relaunch after a kill measures idle from it. */
  @Test fun theMoveToTheBackgroundPersistsTheSessionAndFlushes() {
    var persisted = 0
    val identity = object : IdentityStore {
      val inner = MemoryIdentityStore()
      override fun load() = inner.load()
      override fun save(record: IdentityRecord) = inner.save(record)
      override fun persistNow() {
        persisted++
      }
      override fun erase() = inner.erase()
    }
    val other = Harness()
    val engine = Engine(
      other.options, identity, generatedDeviceIds, other.queueStore, other.network, other.executor, other.executor,
      other.clock, Logger {},
    )
    engine.start()
    engine.appBackgrounded()
    assertEquals(1, persisted)

    val h = Harness(lifecycle = true)
    h.foreground()
    val started = h.identity.stored!!.session!!
    h.clock.advance(20 * minute)
    h.background()
    assertEquals(started.lastActivityAtMs + 20 * minute, h.identity.stored?.session?.lastActivityAtMs)
    assertEquals("flushed", listOf("\$app_installed", "\$session_started", "\$app_opened"), h.network.names)

    // Killed in the background; opened again 25 minutes later.
    h.clock.wall += 25 * minute
    h.clock.monotonic = 1_000
    h.start()
    h.foreground()
    h.flushAll()
    assertEquals("25 idle minutes, not 45", 1, count(h, "\$session_started"))
  }

  /**
   * FR test case 8: killed mid-session in front — no background, no callback —
   * and relaunched inside the window: the same session. Outside it: a new one.
   */
  @Test fun aKillMidSessionKeepsTheSessionInsideTheWindowAndNotOutside() {
    val h = Harness(lifecycle = true)
    h.foreground()
    h.clock.advance(5 * minute)
    h.engine.track("screen_viewed")
    h.flushAll()
    val session = event(h, "screen_viewed")!!["sessionId"]

    h.clock.wall += 10 * minute
    h.clock.monotonic = 1_000
    h.start()
    h.foreground()
    h.flushAll()
    assertEquals(session, h.network.events.last()["sessionId"])
    assertEquals(1, count(h, "\$session_started"))

    h.clock.wall += 31 * minute
    h.clock.monotonic = 2_000
    h.start()
    h.foreground()
    h.flushAll()
    assertNotEquals(session, h.network.events.last()["sessionId"])
    assertEquals(2, count(h, "\$session_started"))
  }

  @Test fun aBackgroundEventJoinsTheOpenSessionWithoutExtendingIt() {
    val h = Harness(lifecycle = true)
    h.foreground()
    h.background()
    val last = h.identity.stored!!.session!!.lastActivityAtMs
    h.clock.advance(5 * minute)
    h.engine.track("push_received")
    h.flushAll()
    assertEquals(event(h, "\$app_opened")!!["sessionId"], event(h, "push_received")!!["sessionId"])
    assertEquals(last, h.identity.stored?.session?.lastActivityAtMs)
  }

  // The activity counter: FR test case 4.

  /**
   * A rotation stops the activity and starts its replacement: no background,
   * no flush, no session cut, no second open. The positive half: a real
   * background, past the delay, is one of each on return.
   */
  @Test fun aRotationIsNotABackgroundAndARealOneIs() {
    val h = Harness(lifecycle = true)
    h.foreground("main#1")
    h.background("main#1", settle = false)
    h.mainThread.advance(300)
    h.foreground("main#2")
    h.mainThread.advance(5_000)
    assertTrue("no background flush", h.network.sent.isEmpty())
    assertEquals("no second open", 3, h.engine.queuedCount)

    h.background("main#2", settle = false)
    h.mainThread.advance(699)
    assertTrue("not yet", h.network.sent.isEmpty())
    h.mainThread.advance(1)
    assertEquals("the background flush", 1, h.network.sent.size)
    h.clock.advance(40 * minute)
    h.foreground("main#3")
    h.flushAll()
    assertEquals(listOf<JsonValue?>(JsonValue.Bool(false), JsonValue.Bool(true)), fromBackground(h))
    assertEquals(2, count(h, "\$session_started"))
  }

  /** Activity A starting B: B starts before A stops, so nothing was ever not in front. */
  @Test fun navigatingBetweenActivitiesIsNotABackground() {
    val h = Harness(lifecycle = true)
    h.foreground("list")
    h.foreground("detail")
    h.background("list")
    h.background("detail", settle = false)
    h.foreground("list")
    h.mainThread.advance(5_000)
    assertTrue(h.network.sent.isEmpty())
    assertEquals("one open, nothing else", 3, h.engine.queuedCount)
  }

  /**
   * Split-screen: one activity stopping while another is visible starts no
   * idle time, flushes nothing and sends no open when it returns. The positive
   * half: when the LAST one goes, the background rules apply.
   */
  @Test fun aSecondActivityStoppingInMultiWindowChangesNothing() {
    val h = Harness(lifecycle = true)
    h.foreground("left")
    h.foreground("right")
    val session = h.identity.stored!!.session!!
    h.clock.advance(10 * minute)
    h.background("left")
    assertTrue("no background flush while one is in front", h.network.sent.isEmpty())
    assertEquals("no idle time started", session, h.identity.stored?.session)
    h.foreground("left")
    assertEquals("no second \$app_opened", 3, h.engine.queuedCount)

    h.background("left")
    h.background("right")
    assertEquals(listOf("\$app_installed", "\$session_started", "\$app_opened"), h.network.names)
    assertEquals(session.lastActivityAtMs + 10 * minute, h.identity.stored?.session?.lastActivityAtMs)
    h.foreground("right")
    h.flushAll()
    assertEquals(2, count(h, "\$app_opened"))
  }

  /** A stop twice, or for an activity never seen starting (the SDK was set up after it started), changes nothing. */
  @Test fun aStopForAnUnknownActivityIsIgnored() {
    val h = Harness(lifecycle = true)
    h.foreground("a")
    h.background("b")
    h.background("b")
    assertTrue(h.network.sent.isEmpty())
    h.background("a")
    assertTrue(h.network.sent.isNotEmpty())
  }

  /**
   * Set up after the first activity started, the SDK sees that activity stop
   * but never start. The stop schedules nothing, so the next start, however
   * soon, is the foreground.
   */
  @Test fun aStopForAnActivityNeverSeenStartingSchedulesNoBackground() {
    val h = Harness(lifecycle = true)
    h.background("started-before-setup", settle = false)
    h.mainThread.advance(100)
    h.foreground("next")
    h.flushAll()
    assertEquals(1, count(h, "\$app_opened"))
  }

  /** Set up after an activity started, the SDK sees it resume: that is its start, the foreground, and its stop is the background. */
  @Test fun aResumeOfAnActivityNeverSeenStartingIsTheForeground() {
    val h = Harness(lifecycle = true)
    h.resume("main")
    h.background("main")
    assertEquals(listOf("\$app_installed", "\$session_started", "\$app_opened"), h.network.names)
    assertEquals(0, h.unknownStops)
  }

  /** The positive half: a resume of an activity already seen starting is nothing at all. */
  @Test fun aResumeOfAStartedActivityChangesNothing() {
    val h = Harness(lifecycle = true)
    h.foreground("main")
    h.resume("main")
    h.background("other", settle = false)
    h.resume("main")
    assertEquals("one open", 3, h.engine.queuedCount)
    assertTrue(h.network.sent.isEmpty())
  }

  /** A stop for an activity never seen starting or resuming is reported, so the adapter can say setup ran late; a known one is not. */
  @Test fun aStopNeverSeenStartingIsReported() {
    val h = Harness(lifecycle = true)
    h.foreground("main")
    h.background("main")
    assertEquals(0, h.unknownStops)
    h.background("before-setup")
    assertEquals(1, h.unknownStops)
  }

  /**
   * Set up after its activity resumed, the seed is that activity's start: the
   * foreground, before the first event. Its stop — one the counter never saw
   * start — is then the background, and is not reported.
   */
  @Test fun aSeededActivityIsTheForegroundAndItsStopIsTheBackground() {
    val h = Harness(lifecycle = true)
    assertTrue(h.seed())
    h.engine.track("level_complete")
    h.flushAll()
    assertEquals(listOf("\$app_installed", "\$session_started", "\$app_opened", "level_complete"), h.network.names)
    assertEquals(JsonValue.Bool(false), fromBackground(h).single())
    h.engine.track("paused")
    h.background("the-seeded-one")
    assertEquals(0, h.unknownStops)
    assertEquals("the background flushed", "paused", h.network.names.last())
    h.foreground("main")
    h.flushAll()
    assertEquals(listOf<JsonValue?>(JsonValue.Bool(false), JsonValue.Bool(true)), fromBackground(h))
  }

  /** After a seed, a stop for an activity never seen is not reported: the seed already answered the late setup. */
  @Test fun afterASeedNoUnseenStopIsReported() {
    val h = Harness(lifecycle = true)
    h.seed()
    h.background("the-seeded-one")
    h.background("another-never-seen")
    assertEquals(0, h.unknownStops)
  }

  /**
   * The seeded activity seen at last, resuming after a dialog or a permission
   * prompt, takes the placeholder's place: its stop is the background, and the
   * placeholder never keeps the app in front.
   */
  @Test fun theSeededActivityResumingTakesThePlaceholdersPlace() {
    val h = Harness(lifecycle = true)
    h.seed()
    h.resume("corona")
    h.background("corona")
    h.foreground("corona")
    h.flushAll()
    assertEquals("a real background and a return", listOf<JsonValue?>(JsonValue.Bool(false), JsonValue.Bool(true)), fromBackground(h))
  }

  /**
   * A seed that ran before the launch's activity started — `setup()` on a
   * worker thread from `Application.onCreate()`, with the process already in
   * front: that activity takes the placeholder's place, with no second open.
   */
  @Test fun aSeedBeforeTheLaunchActivityStartsIsReplacedByIt() {
    val h = Harness(lifecycle = true)
    h.seed()
    h.foreground("launcher")
    h.flushAll()
    assertEquals(1, count(h, "\$app_opened"))
    h.background("launcher")
    h.foreground("launcher")
    h.flushAll()
    assertEquals(listOf<JsonValue?>(JsonValue.Bool(false), JsonValue.Bool(true)), fromBackground(h))
  }

  /** A seed while an activity is counted, or while a background is pending, is nothing: the counter already knows. */
  @Test fun aSeedAfterAnActivityWasSeenChangesNothing() {
    val h = Harness(lifecycle = true)
    h.foreground("main")
    assertFalse(h.seed())
    h.background("main", settle = false)
    assertFalse("a background is pending", h.seed())
    h.mainThread.advance(700)
    h.flushAll()
    assertEquals(1, count(h, "\$app_opened"))
  }

  /**
   * A translucent activity over the seeded one (an ad, a billing sheet): it
   * takes the placeholder's place, the seeded one resumes before it stops, and
   * the app stays in front throughout.
   */
  @Test fun aTranslucentActivityOverASeededOneIsNotABackground() {
    val h = Harness(lifecycle = true)
    h.seed()
    h.foreground("ad")
    h.resume("corona")
    h.background("ad")
    h.flushAll()
    assertEquals(1, count(h, "\$app_opened"))
    val sent = h.network.sent.size
    h.mainThread.advance(5_000)
    assertEquals("no background flush", sent, h.network.sent.size)
    h.background("corona")
    h.foreground("corona")
    h.flushAll()
    assertEquals(listOf<JsonValue?>(JsonValue.Bool(false), JsonValue.Bool(true)), fromBackground(h))
  }

  /** An opaque one: the seeded one stops under it, unreported, and starts again before it stops. */
  @Test fun anOpaqueActivityOverASeededOneIsNotABackground() {
    val h = Harness(lifecycle = true)
    h.seed()
    h.foreground("ad")
    h.background("corona", settle = false)
    h.foreground("corona")
    h.background("ad")
    h.mainThread.advance(5_000)
    h.flushAll()
    assertEquals(1, count(h, "\$app_opened"))
    assertEquals(0, h.unknownStops)
    h.background("corona")
    h.foreground("corona")
    h.flushAll()
    assertEquals(listOf<JsonValue?>(JsonValue.Bool(false), JsonValue.Bool(true)), fromBackground(h))
  }

  /** Between the last stop and the background, a person is still present: an event then opens a session. */
  @Test fun aPersonIsPresentUntilTheBackgroundDelayRunsOut() {
    val h = Harness(lifecycle = true, sessionTimeoutSeconds = 60.0)
    h.foreground()
    h.clock.advance(2 * minute)
    h.background(settle = false)
    h.engine.track("tapped")
    assertNotNull("opens a new session: someone is there", h.engine.sessionId)
    h.mainThread.advance(700)
    h.clock.advance(2 * minute)
    h.engine.track("push_received")
    h.flushAll()
    assertNull("after the background, an event opens none", h.network.events.last()["sessionId"])
  }

  // The install rule: FR test case 5.

  /** No record and an install two days old — including one Play updated before it was first opened — is an install. */
  @Test fun noRecordAndARecentInstallIsAnInstall() {
    val h = Harness(lifecycle = true, firstInstallMs = FakeClock().wall - 2 * day)
    h.flushAll()
    assertEquals(listOf("\$app_installed"), h.network.names)
  }

  /**
   * No record and an install thirty days old is an existing user's first
   * launch with the SDK, or cleared data: neither event. The version is
   * recorded, so the next update is detected.
   */
  @Test fun noRecordAndAnOldInstallSendsNeitherAndTheNextUpdateIsDetected() {
    val h = Harness(lifecycle = true, firstInstallMs = FakeClock().wall - 30 * day)
    h.foreground()
    h.flushAll()
    assertEquals(listOf("\$session_started", "\$app_opened"), h.network.names)
    assertEquals("1.2.0", h.identity.stored?.appVersion)

    h.start()
    h.flushAll()
    assertEquals("the same build again: nothing", 2, h.network.names.size)
    h.relaunch("1.3.0", "13")
    h.flushAll()
    assertEquals(listOf("\$session_started", "\$app_opened", "\$app_updated"), h.network.names)
  }

  /** An old install's first process may track nothing at all (a broadcast); its version is still recorded, so the update after it is one. */
  @Test fun anOldInstallRecordsItsVersionEvenWhenNothingIsTracked() {
    val h = Harness(lifecycle = true, firstInstallMs = FakeClock().wall - 30 * day)
    assertEquals("1.2.0", h.identity.stored?.appVersion)
    h.relaunch("1.3.0", "13")
    h.flushAll()
    assertEquals(listOf("\$app_updated"), h.network.names)
  }

  /** A new install that mints nothing writes no record holding only a version: the next launch is still the install. */
  @Test fun aNewInstallWritesNoVersionOnlyRecord() {
    val h = Harness(lifecycle = true, deviceIds = { null })
    assertNull(h.identity.stored)
  }

  @Test fun theWindowIsSevenDaysInclusive() {
    val now = FakeClock().wall
    val within = Harness(lifecycle = true, firstInstallMs = now - 7 * day)
    within.flushAll()
    assertEquals(listOf("\$app_installed"), within.network.names)

    val past = Harness(lifecycle = true, firstInstallMs = now - 7 * day - 1)
    past.engine.track("a")
    past.flushAll()
    assertEquals(listOf("a"), past.network.names)
  }

  /** A stored record is never an install, however old the package: the rule is for a launch with no record. */
  @Test fun aStoredRecordWithAnOldInstallIsAnUpdateNotSilence() {
    val h = Harness(lifecycle = true)
    h.options = h.options.copy(firstInstallMs = h.clock.wall - 400 * day)
    h.relaunch("1.3.0", "13")
    h.flushAll()
    assertEquals(listOf("\$app_installed", "\$app_updated"), h.network.names)
  }

  /** `reset()` keeps the install. A withdrawal deletes the record, so a grant is decided as a launch with no record. */
  @Test fun resetIsNotAReinstallButAWithdrawalIsDecidedAgain() {
    val h = Harness(lifecycle = true)
    h.engine.reset()
    h.start()
    h.flushAll()
    assertEquals(listOf("\$app_installed"), h.network.names)

    h.engine.setConsent(TogoConsent.DENIED)
    h.engine.setConsent(TogoConsent.GRANTED)
    h.flushAll()
    assertEquals(listOf("\$app_installed", "\$app_installed"), h.network.names)
    val ids = h.network.events.map { it["anonymousId"] }
    assertNotEquals("nothing is stitched", ids[0], ids[1])
  }

  /** The other half: after a withdrawal on an old install, the grant sends no install. */
  @Test fun aWithdrawalOnAnOldInstallIsNotAnInstallAtTheGrant() {
    val h = Harness(lifecycle = true, firstInstallMs = FakeClock().wall - 30 * day)
    h.engine.track("a")
    h.flushAll()
    h.engine.setConsent(TogoConsent.DENIED)
    h.engine.setConsent(TogoConsent.GRANTED)
    h.engine.track("b")
    h.flushAll()
    assertEquals(listOf("a", "b"), h.network.names)
  }

  /** A withdrawal and then a grant by way of pending is decided as a launch with no record too, and records the version. */
  @Test fun aGrantByWayOfPendingAfterAWithdrawalIsDecidedAgain() {
    val h = Harness(lifecycle = true)
    h.flushAll()
    h.engine.setConsent(TogoConsent.DENIED)
    h.engine.setConsent(TogoConsent.PENDING)
    h.engine.setConsent(TogoConsent.GRANTED)
    h.flushAll()
    assertEquals(listOf("\$app_installed", "\$app_installed"), h.network.names)
    assertEquals("1.2.0", h.identity.stored?.appVersion)
  }

  /** Under pending nothing is read or stored, so the decision waits; the grant makes it, at the launch's time and first. */
  @Test fun underPendingConsentTheInstallWaitsForTheGrantAndGoesFirst() {
    val h = Harness(consent = TogoConsent.PENDING, lifecycle = true)
    val launchedAt = h.clock.wall
    h.foreground()
    h.clock.advance(minute)
    h.engine.track("onboarding_started")
    assertNull(h.identity.stored)

    h.engine.setConsent(TogoConsent.GRANTED)
    h.flushAll()
    assertEquals(listOf("\$session_started", "\$app_installed", "\$app_opened", "onboarding_started"), h.network.names)
    assertEquals(str(IsoTime.format(launchedAt)), event(h, "\$app_installed")!!["clientEventTs"])
  }

  /** A client that never starts sends no lifecycle event: the rest of the surface, and the browser-parity scenarios. */
  @Test fun aClientThatNeverStartsSendsNoLifecycleEvent() {
    val h = Harness()
    h.engine.track("a")
    h.flushAll()
    h.engine.setConsent(TogoConsent.DENIED)
    h.engine.setConsent(TogoConsent.GRANTED)
    h.engine.track("b")
    h.flushAll()
    assertEquals(listOf("\$session_started", "a", "\$session_started", "b"), h.network.names)
  }

  // A locked device (Direct Boot): FR test case 3.

  /**
   * An existing install started before the first unlock: nothing is minted,
   * no `$app_installed` (the record is unreadable, not absent), events are
   * held, and after the unlock they go under the stored ids.
   */
  @Test fun beforeTheFirstUnlockNothingIsMintedAndTheStoredIdsAreUsedAfter() {
    val h = Harness(lifecycle = true)
    h.flushAll()
    val stored = h.identity.stored!!
    val sentBefore = h.network.sent.size

    h.identity.locked = true
    h.start()
    h.engine.track("push_received")
    h.flushAll()
    assertEquals("held, not sent", sentBefore, h.network.sent.size)
    assertEquals(1, h.engine.heldCount)
    assertEquals("nothing minted or written", stored, h.identity.stored)

    h.identity.locked = false
    h.engine.identityBecameAvailable()
    h.flushAll()
    assertEquals(listOf("\$app_installed", "push_received"), h.network.names)
    assertEquals(str(stored.deviceId!!), h.network.events.last()["deviceId"])
    assertEquals(str(stored.anonymousId!!), h.network.events.last()["anonymousId"])
  }

  /** The positive half: a locked first launch is held too, and is an install once the device unlocks. */
  @Test fun aLockedFirstLaunchInstallsOnceTheDeviceUnlocks() {
    val identity = MemoryIdentityStore().apply { locked = true }
    val h = Harness(identity = identity, lifecycle = true)
    h.engine.track("push_received")
    h.flushAll()
    assertTrue(h.network.sent.isEmpty())
    assertNull(identity.stored)

    identity.locked = false
    h.engine.identityBecameAvailable()
    h.flushAll()
    assertEquals(listOf("\$app_installed", "push_received"), h.network.names)
  }

  /**
   * The record becomes readable with no signal — a read that failed for
   * another reason than the lock, or an event that beats the unlock signal to
   * the SDK's thread. The next event sends the launch and what was held first,
   * in order, rather than leaving them for a signal that may never come.
   */
  @Test fun theNextEventAfterTheRecordReadsReleasesWhatWasHeldFirst() {
    val identity = MemoryIdentityStore().apply { locked = true }
    val h = Harness(identity = identity, lifecycle = true)
    h.engine.track("held")
    identity.locked = false
    h.engine.track("next")
    h.flushAll()
    assertEquals(listOf("\$app_installed", "held", "next"), h.network.names)
    assertEquals(0, h.engine.heldCount)
  }

  /** The same through a flush: what was held goes out, without waiting for an event. */
  @Test fun aFlushAfterTheRecordReadsReleasesWhatWasHeld() {
    val identity = MemoryIdentityStore().apply { locked = true }
    val h = Harness(identity = identity, lifecycle = true)
    h.engine.track("held")
    identity.locked = false
    h.flush()
    assertEquals(listOf("\$app_installed", "held"), h.network.names)
  }

  /** Held while locked is bounded: at most 100 by default, the oldest dropped and reported. */
  @Test fun whatALockedDeviceHoldsIsBounded() {
    val identity = MemoryIdentityStore().apply { locked = true }
    val h = Harness(identity = identity, lifecycle = true)
    repeat(101) { h.engine.track("e$it") }
    assertEquals(100, h.engine.heldCount)
    assertEquals(TogoErrorCode.QUEUE_FULL, h.errors.single().code)
    identity.locked = false
    h.engine.identityBecameAvailable()
    h.flushAll()
    assertEquals("e1", h.network.names[1])
    assertEquals(101, h.network.names.size)
  }

  /** While the record cannot be read neither can the queue: reading it as empty would let the next write replace it. */
  @Test fun theQueueIsNotReadWhileTheIdentityIsUnreadable() {
    val h = Harness(lifecycle = true)
    h.network.answers.add(TransportResponse(null))
    h.flush()
    val leftOnDisk = h.queueStore.read()
    assertNotNull(leftOnDisk)

    h.identity.locked = true
    h.start()
    h.flush()
    assertEquals("only the first, failed, attempt", 1, h.network.sent.size)
    assertTrue(leftOnDisk!!.contentEquals(h.queueStore.read()!!))

    h.identity.locked = false
    h.engine.identityBecameAvailable()
    h.flush()
    assertEquals("the retry, after the unlock", listOf("\$app_installed", "\$app_installed"), h.network.names)
  }

  /** The background flush cut off by a kill: the next launch sends the same eventIds again, which the edge dedupes. */
  @Test fun aBackgroundFlushCutOffByAKillIsSentAgainAtTheNextLaunch() {
    val h = Harness(lifecycle = true)
    h.foreground()
    h.network.holds = true
    h.background()
    val sent = h.network.sent.single().ids()

    h.network.holds = false
    h.start()
    h.flushAll()
    assertEquals(sent, h.network.sent[1].ids())
  }
}
