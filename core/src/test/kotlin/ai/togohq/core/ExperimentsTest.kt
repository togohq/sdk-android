package ai.togohq.core

import ai.togohq.TogoConsent
import ai.togohq.TogoExperiment
import ai.togohq.TogoExperimentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `getExperiment()` on a device: a stored answer offline, the synchronous read, the exposure once, the stamp, the session pin. */
class ExperimentsTest {
  private val inB = mapOf("checkout" to Triple("active", "b" as String?, true))

  private fun get(h: Harness, name: String = "checkout"): TogoExperiment? {
    var result: TogoExperiment? = null
    h.engine.getExperiment(name) { result = it }
    return result
  }

  private fun entered(h: Harness) = h.network.events.filter { it.string("eventName") == "\$experiment_entered" }

  private fun event(h: Harness, name: String) = h.network.events.lastOrNull { it.string("eventName") == name }

  private fun online(h: Harness, answers: Map<String, Triple<String, String?, Boolean>> = inB, maxAgeSeconds: Long = 3600) {
    h.network.assign = { FakeNetwork.assignments(answers, maxAgeSeconds = maxAgeSeconds) }
  }

  private fun offline(h: Harness) {
    h.network.assign = { TransportResponse(null) }
  }

  private val unavailable = TogoExperiment.unavailable("checkout")

  // 1 and 2

  @Test fun aStoredAnswerIsReturnedOfflineAtAnyAgeAndRefreshed() {
    val h = Harness()
    online(h, maxAgeSeconds = 60)
    assertEquals("b", get(h)?.variant)
    offline(h)
    h.clock.advance(30L * 24 * 3_600_000) // A month offline.
    val before = h.network.assignRequests.size
    val result = get(h)
    assertEquals(TogoExperimentStatus.ACTIVE, result?.status)
    assertEquals("b", result?.variant)
    assertEquals("an old answer is refreshed", before + 1, h.network.assignRequests.size)
  }

  @Test fun noStoredAnswerAndNoNetworkIsUnavailableAndStoresNothing() {
    val h = Harness()
    assertEquals(unavailable, get(h))
    h.flush()
    assertTrue(entered(h).isEmpty())
    assertNull(h.engine.cachedExperiment("checkout"))
  }

  @Test fun aSlowNetworkAnswersUnavailableOnceAndStillFillsTheStore() {
    val h = Harness()
    online(h)
    h.network.holdsAssign = true
    val results = ArrayList<TogoExperiment>()
    h.engine.getExperiment("checkout") { results.add(it) }
    assertTrue(results.isEmpty())
    h.executor.fire(0.8)
    h.network.answerAssign()
    assertEquals(listOf(unavailable), results)
    assertEquals("b", h.engine.cachedExperiment("checkout")?.variant)
  }

  // 3

  @Test fun theExposureIsSentOncePerUnitAcrossAKillAndRelaunch() {
    val h = Harness()
    online(h)
    get(h)
    get(h)
    h.start() // Killed and relaunched over the same stores.
    get(h)
    h.engine.cachedExperiment("checkout")
    h.flushAll()
    val exposures = entered(h)
    assertEquals(1, exposures.size)
    val properties = exposures.single().obj("properties")
    assertEquals(str("checkout"), properties["experiment"])
    assertEquals(str("b"), properties["variant"])
    assertNull("a \$ event carries no stamp", exposures.single()["experiments"])
  }

  @Test fun aNonParticipantIsNotEnteredOrStamped() {
    val h = Harness()
    online(h, mapOf("checkout" to Triple("active", null, false)))
    val result = get(h)
    assertEquals(TogoExperimentStatus.ACTIVE, result?.status)
    assertNull(result?.variant)
    assertFalse(result!!.participating)
    h.engine.track("paid")
    h.flushAll()
    assertTrue(entered(h).isEmpty())
    assertNull(event(h, "paid")?.get("experiments"))
  }

  // 4

  @Test fun aLaterEventCarriesTheStampAndAQueuedEventKeepsIt() {
    val h = Harness()
    online(h)
    get(h)
    h.network.answers.add(TransportResponse(null)) // The upload fails: queued offline.
    h.engine.track("paid")
    h.flush()
    // The experiment finishes before the queued event goes; it keeps the stamp it was tracked with.
    online(h, mapOf("checkout" to Triple("finished", null, false)))
    h.clock.advance(3_600_001)
    h.engine.preloadExperiments(listOf("checkout"))
    h.start()
    h.flushAll()
    assertEquals(obj("checkout" to str("b")), event(h, "paid")?.get("experiments"))
  }

  // 5

  @Test fun identifyDropsTheAnswersAndAsksForTheSignedInUser() {
    val h = Harness()
    online(h)
    get(h)
    h.engine.identify("u_1")
    val requests = h.network.assignRequests.size
    online(h, mapOf("checkout" to Triple("active", "a", true)))
    assertEquals("a", get(h)?.variant)
    assertEquals(requests + 1, h.network.assignRequests.size)
    assertEquals(str("u_1"), h.network.assignRequests.last()["userId"])
  }

  @Test fun resetStartsAnEmptyStoreAndNeverCountsTheSameUnitTwice() {
    val h = Harness()
    h.network.assign = { FakeNetwork.assignments(inB, unit = "device_id") }
    get(h)
    h.engine.reset()
    assertNull("the answers went with the visitor", h.engine.cachedExperiment("checkout"))
    h.engine.track("after_reset")
    get(h)
    h.flushAll()
    assertEquals("the device is the same unit after reset()", 1, entered(h).size)
    assertNull("the stamps went with the visitor", event(h, "after_reset")?.get("experiments"))
  }

  @Test fun aResetWhileTheFileCannotBeReadKeepsTheOnceRecord() {
    val h = Harness()
    h.network.assign = { FakeNetwork.assignments(inB, unit = "device_id") }
    get(h)
    h.start() // A new process: nothing of the file in memory.
    h.experimentStore.unreadable = true // Direct Boot, before the unlock.
    h.engine.reset()
    h.experimentStore.unreadable = false
    get(h)
    h.flushAll()
    assertEquals("the device is the same unit, entered once", 1, entered(h).size)
  }

  // 6

  @Test fun aRefreshMidSessionChangesNothingUntilTheNextSession() {
    val h = Harness()
    online(h, maxAgeSeconds = 60)
    h.engine.track("opened")
    assertEquals("b", get(h)?.variant)
    online(h, mapOf("checkout" to Triple("active", "c", true)), maxAgeSeconds = 60)
    h.clock.advance(61_000)
    h.engine.track("still_here")
    assertEquals("the stored one, refreshed behind it", "b", get(h)?.variant)
    assertEquals("pinned for the session", "b", get(h)?.variant)
    assertEquals("b", h.engine.cachedExperiment("checkout")?.variant)
    h.clock.advance(31 * 60_000L)
    h.engine.track("next_visit")
    assertEquals("c", get(h)?.variant)
  }

  // 7

  @Test fun finishedFromARefreshEndsTheStamp() {
    val h = Harness(lifecycle = true)
    h.foreground()
    online(h)
    get(h)
    online(h, mapOf("checkout" to Triple("finished", null, false)))
    h.background()
    h.foreground() // `$app_opened` refreshes what is stored, inside the same session.
    assertEquals("the session's pinned answer still", "b", get(h)?.variant)
    h.engine.cachedExperiment("checkout")
    h.engine.track("later")
    h.flushAll()
    val later = event(h, "later")
    assertNotNull(later)
    assertNull(later!!["experiments"])
  }

  @Test fun appOpenedFetchesTheConfiguredNamesWithoutAnExposure() {
    val h = Harness(lifecycle = true)
    h.options = h.options.copy(experiments = listOf("onboarding"))
    h.start()
    online(h, mapOf("onboarding" to Triple("active", "short", true)))
    h.foreground()
    assertEquals(JsonValue.Arr(listOf(str("onboarding"))), h.network.assignRequests.last()["experiments"])
    h.flushAll()
    assertTrue("a preload is not an exposure", entered(h).isEmpty())
    offline(h)
    assertEquals("short", h.engine.cachedExperiment("onboarding")?.variant)
  }

  // 8

  @Test fun theSynchronousReadNeverAsksAndRecordsTheExposure() {
    val h = Harness()
    online(h)
    assertNull(h.engine.cachedExperiment("checkout"))
    assertTrue("never the network", h.network.assignRequests.isEmpty())
    h.engine.preloadExperiments(listOf("checkout"))
    val requests = h.network.assignRequests.size
    assertEquals("b", h.engine.cachedExperiment("checkout")?.variant)
    assertEquals(requests, h.network.assignRequests.size)
    h.engine.track("paid")
    h.flushAll()
    assertEquals(1, entered(h).size)
    assertEquals(obj("checkout" to str("b")), event(h, "paid")?.get("experiments"))
  }

  // 9

  @Test fun consentPendingOrDeniedIsUnavailableAndWithdrawalDeletesTheFile() {
    val pending = Harness(consent = TogoConsent.PENDING)
    online(pending)
    assertEquals(unavailable, get(pending))
    assertTrue(pending.network.assignRequests.isEmpty())
    assertNull(pending.experimentStore.read())

    val h = Harness()
    online(h)
    get(h)
    assertNotNull(h.experimentStore.read())
    h.engine.setConsent(TogoConsent.DENIED)
    assertNull(h.experimentStore.read())
    assertEquals(unavailable, get(h))
    assertNull(h.engine.cachedExperiment("checkout"))
  }

  @Test fun aLockedDeviceAnswersUnavailableAndAsksNothing() {
    val identity = MemoryIdentityStore().also { it.locked = true }
    val locked = Harness(identity = identity)
    online(locked)
    assertEquals(unavailable, get(locked))
    assertTrue(locked.network.assignRequests.isEmpty())
    assertNull(locked.engine.cachedExperiment("checkout"))
  }

  /** A launch choosing its first screen: nothing in front yet, the stored answer at once. */
  private fun storedThenRelaunched(): Harness {
    val h = Harness(lifecycle = true)
    h.foreground()
    online(h)
    h.engine.preloadExperiments(listOf("checkout")) // Stored, nobody entered.
    h.background()
    offline(h)
    h.start() // Killed; launched again, no activity started yet.
    return h
  }

  @Test fun beforeTheFirstScreenTheStoredAnswerIsReadAndEnteredAtTheForeground() {
    val h = storedThenRelaunched()
    assertEquals("for the first screen", "b", h.engine.cachedExperiment("checkout")?.variant)
    assertEquals("b", get(h)?.variant)
    h.engine.track("launch_screen")
    h.foreground()
    h.engine.track("home")
    h.flushAll()
    assertEquals("entered once, at the foreground", 1, entered(h).size)
    val names = h.network.names
    assertTrue("after the open", names.indexOf("\$experiment_entered") > names.indexOf("\$app_opened"))
    assertNull("no stamp before the exposure", event(h, "launch_screen")?.get("experiments"))
    assertEquals(obj("checkout" to str("b")), event(h, "home")?.get("experiments"))
  }

  @Test fun aLaunchThatNeverComesToTheFrontEntersNobody() {
    val h = storedThenRelaunched() // A push start, say.
    assertEquals("b", get(h)?.variant)
    h.engine.track("push_received")
    h.flushAll()
    assertTrue(entered(h).isEmpty())
    h.start()
    h.foreground() // The next process opening does not inherit the last one's.
    h.flushAll()
    assertTrue(entered(h).isEmpty())
  }

  @Test fun aResetBeforeTheForegroundDropsWhatWaited() {
    val h = storedThenRelaunched()
    h.engine.cachedExperiment("checkout")
    h.engine.reset()
    h.foreground()
    h.flushAll()
    assertTrue("the answer was the last visitor's", entered(h).isEmpty())
  }

  // 10

  @Test fun aTestKeyAsksEveryTimeAndEntersPerVariantPerProcess() {
    val h = Harness(writeKey = "togo_pkt_test0000000000")
    online(h)
    get(h)
    get(h)
    assertEquals("a test key caches nothing", 2, h.network.assignRequests.size)
    online(h, mapOf("checkout" to Triple("active", "c", true)))
    get(h)
    h.flushAll()
    assertEquals(listOf(str("b"), str("c")), entered(h).map { it.obj("properties")["variant"] })
    assertNull("nothing stored", h.experimentStore.read())
  }

  // The request and the edges

  @Test fun theRequestCarriesTheUnits() {
    val h = Harness()
    online(h)
    h.engine.identify("u_9")
    get(h)
    val body = h.network.assignRequests.last()
    assertNotNull(body.string("anonymousId"))
    assertNotNull(body.string("deviceId"))
    assertEquals(str("u_9"), body["userId"])
    assertEquals(JsonValue.Arr(listOf(str("checkout"))), body["experiments"])
  }

  @Test fun aNameTheServerCannotHoldIsNotFoundWithNoRequest() {
    val h = Harness()
    online(h)
    assertEquals(TogoExperimentStatus.NOT_FOUND, get(h, "")?.status)
    assertEquals(TogoExperimentStatus.NOT_FOUND, get(h, "x".repeat(65))?.status)
    assertTrue(h.network.assignRequests.isEmpty())
  }

  @Test fun anAssignEndpointThatIsNotAUrlIsSaidOnce() {
    val h = Harness()
    h.options = h.options.copy(assignEndpoint = "assign.example.test")
    h.start()
    online(h)
    assertEquals(unavailable, get(h))
    assertEquals(1, h.logs.count { it.startsWith("assignEndpoint is not a URL") })
  }

  @Test fun aCorruptFileIsARefetchNotACrash() {
    val h = Harness(experimentStore = MemoryExperimentStore("{not json".toByteArray()))
    online(h)
    assertEquals("b", get(h)?.variant)
  }

  @Test fun theUnitHashMatchesTheBrowsers() {
    // The browser's `hashUnit("abc")`: djb2 over UTF-16, base 36.
    assertEquals(193_485_963L.toString(36), Experiments.hashUnit("abc"))
    assertEquals(5381L.toString(36), Experiments.hashUnit(""))
  }
}
