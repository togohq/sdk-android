package ai.togohq.core

import ai.togohq.TogoConsent
import ai.togohq.TogoErrorCode
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The durable queue, batching and the transport. */
class DeliveryTest {
  @Test fun aBatchCarriesTheAppTheDeviceAndBothModes() {
    val h = Harness()
    h.engine.track("screen_viewed", mapOf("plan" to "pro"))
    h.flush()
    val batch = h.network.sent[0]
    assertEquals("https://in.example.test/v1/events", h.network.urls[0].toString())
    assertEquals("togo_pkl_live0000000000", h.network.keys[0])
    assertEquals(JsonValue.Int(2), batch["envelopeVersion"])
    assertEquals(obj("version" to str("1.2.0"), "build" to str("12")), batch["app"])
    assertEquals(
      obj("os" to str("Android"), "osVersion" to str("14"), "model" to str("Pixel 8"),
        "deviceType" to str("mobile"), "manufacturer" to str("Google")),
      batch["device"],
    )
    assertEquals(str("install"), batch["deviceIdMode"])
    assertEquals(str("sdk"), batch["sessionIdMode"])
    assertEquals(str(IsoTime.format(h.clock.wall)), batch["clientSentTs"])
    val event = (batch["events"] as JsonValue.Arr).values[1] as JsonValue.Obj
    assertEquals(str("sdk-android"), event.obj("sdk")["name"])
    assertEquals(str("Europe/Berlin"), event["timezone"])
    assertNull(event["page"])
    assertNull("project_id is never held or sent", event["projectId"])
  }

  /** An endpoint with a trailing slash still posts to `/v1/events`. */
  @Test fun anEndpointWithATrailingSlashPostsToTheSamePath() {
    val h = Harness()
    h.options = h.options.copy(endpoint = "https://in.example.test/")
    h.start()
    h.engine.track("a")
    h.flush()
    assertEquals("https://in.example.test/v1/events", h.network.urls[0].toString())
  }

  /**
   * Events recorded on 1.2 and flushed after an update to 1.3 keep 1.2. The
   * uploader never puts two app versions — or two OS versions — in one batch.
   */
  @Test fun aBatchIsCutWhereTheAppOrOsVersionChanges() {
    val queue = MemoryQueueStore()
    val identity = MemoryIdentityStore()
    val old = Harness(identity = identity, queueStore = queue)
    old.network.answers.add(TransportResponse(null))
    old.engine.track("on_1_2")
    old.flush() // offline

    // The app updated, and the OS with it.
    val new = Harness(
      app = AppInfo("1.3.0", "13"), device = DeviceInfo("Android", "15", "Pixel 8", "mobile", "Google"),
      identity = identity, queueStore = queue,
    )
    new.engine.track("on_1_3")
    new.flush()
    new.flush()

    assertEquals(2, new.network.sent.size)
    val (first, second) = new.network.sent
    assertEquals(str("1.2.0"), first.obj("app")["version"])
    assertEquals(str("14"), first.obj("device")["osVersion"])
    assertEquals(listOf("\$session_started", "on_1_2"), names(first))
    assertEquals(str("1.3.0"), second.obj("app")["version"])
    assertEquals(str("15"), second.obj("device")["osVersion"])
    assertEquals(listOf("on_1_3"), names(second))
  }

  /**
   * A relaunch that uploads before its first event still says where the queued
   * events' device id came from: every queued event keeps the mode it was
   * tracked under.
   */
  @Test fun aStartUpFlushCarriesTheQueuedEventsDeviceIdMode() {
    val h = Harness()
    h.network.answers.add(TransportResponse(null))
    h.engine.track("a")
    h.flush() // offline: queued
    h.start() // a new launch, nothing tracked yet
    h.flush()
    assertEquals(2, h.network.sent.size)
    assertEquals(str("install"), h.network.sent[1]["deviceIdMode"])
  }

  /** Events of two device-id modes never share a batch. */
  @Test fun aBatchNeverMixesTwoDeviceIdModes() {
    val queue = MemoryQueueStore()
    val h = Harness(queueStore = queue)
    h.network.answers.add(TransportResponse(null))
    h.engine.track("a")
    h.flush()
    // The same queue, read by a client whose stored identity says another mode.
    val other = Harness(
      queueStore = queue,
      identity = MemoryIdentityStore(IdentityRecord(deviceId = "d2", deviceIdMode = "custom", anonymousId = "a2")),
    )
    other.engine.track("b")
    other.flush()
    other.flush()
    assertEquals(listOf(str("install"), str("custom")), other.network.sent.map { it["deviceIdMode"] })
  }

  /** Every 5xx — not only 503 — is retried with the SAME eventIds; backoff is scheduled, never a spin. */
  @Test fun everyServerErrorIsRetriedWithTheSameEventIds() {
    for (status in listOf(500, 502, 503, 504)) {
      val h = Harness()
      h.network.answers.add(TransportResponse(status))
      h.engine.track("checkout_started")
      h.flush()
      assertEquals("$status: back in the queue", 2, h.engine.queuedCount)
      assertTrue("$status: a retry is scheduled", (h.executor.timers.lastOrNull() ?: 0.0) > 0)
      h.flush()
      assertEquals("$status", 2, h.network.sent.size)
      assertEquals("$status", h.network.sent[0].ids(), h.network.sent[1].ids())
      assertEquals(0, h.engine.queuedCount)
      assertTrue(h.errors.isEmpty())
    }
  }

  /** Offline is normal operation: re-queued, and stamped afresh at the upload that finally reaches the edge. */
  @Test fun anOfflineBatchIsDeliveredLaterWithClientSentTsOfThatUpload() {
    val h = Harness()
    h.network.answers.add(TransportResponse(null))
    h.engine.track("a")
    h.flush()
    h.clock.advance(3_600_000)
    h.flush()
    assertEquals(str(IsoTime.format(h.clock.wall)), h.network.sent[1]["clientSentTs"])
    assertEquals(h.network.sent[0].ids(), h.network.sent[1].ids())
  }

  @Test fun retryAfterIsHonouredOnA429() {
    val h = Harness()
    h.network.answers.add(TransportResponse(429, retryAfterSeconds = 42.0))
    h.engine.track("a")
    h.flush()
    assertEquals(42.0, h.executor.timers.last(), 0.0)
    assertEquals(2, h.engine.queuedCount)
  }

  /** A 4xx is never retried: it would fail identically forever. */
  @Test fun a400IsDroppedAndReportedNotRetried() {
    val h = Harness()
    h.network.answers.add(TransportResponse(400))
    val id = h.engine.track("a")!!
    h.flush()
    assertEquals(0, h.engine.queuedCount)
    assertEquals(listOf(TogoErrorCode.BATCH_REJECTED), h.errors.map { it.code })
    assertTrue(h.errors[0].eventIds.contains(id))
    assertNull("a refusal for good is out of the file", h.queueStore.read())
  }

  /**
   * A redirect, a captive portal's 200, or any status the edge never sends is
   * not a delivery: the batch stays in the queue and the file, and is retried.
   */
  @Test fun aRedirectIsNeverReadAsDelivered() {
    for (status in listOf(301, 302, 307, 308, 304, 100, 200, 201, 204)) {
      val h = Harness()
      h.network.answers.add(TransportResponse(status))
      h.network.answers.add(TransportResponse(status))
      h.engine.track("a")
      h.flush()
      assertEquals("$status: kept", 2, h.engine.queuedCount)
      assertEquals("$status: still in the file", listOf("\$session_started", "a"), h.storedNames())
      assertTrue("$status: a retry is scheduled", (h.executor.timers.lastOrNull() ?: 0.0) > 0)
      assertTrue("$status: no error, since nothing was lost", h.errors.isEmpty())
      h.flush()
      assertEquals("$status: said once", 1, h.logs.count { it.contains("answered $status, not 202") })
      h.flush()
      assertEquals("$status: delivered once the endpoint answers", 3, h.network.sent.size)
      assertEquals(h.network.sent[0].ids(), h.network.sent[2].ids())
      assertEquals(0, h.engine.queuedCount)
    }
  }

  @Test fun a413IsReportedAsTooLarge() {
    val h = Harness()
    h.network.answers.add(TransportResponse(413))
    h.engine.track("a")
    h.flush()
    assertEquals(listOf(TogoErrorCode.BATCH_TOO_LARGE), h.errors.map { it.code })
    assertEquals(0, h.engine.queuedCount)
  }

  /**
   * A batch refused for good — a 413 or a 400 — is dropped, and the rest of the
   * backlog behind it is sent next, not left on disk until the app's next call.
   */
  @Test fun theBacklogBehindARefusedBatchIsSentNext() {
    for (status in listOf(413, 400, 422)) {
      val h = Harness()
      h.network.holds = true
      repeat(119) { h.engine.track("e$it") } // + $session_started = 120: a batch went out at 50
      assertEquals(1, h.network.held.size)
      h.flush() // the flush timer fires while the upload is out, and finds it in flight
      h.executor.timers.clear()
      h.network.answer(TransportResponse(status))
      assertEquals("$status: the rest is scheduled at once", listOf(0.0), h.executor.timers)
      h.network.holds = false
      h.flush() // the timer firing
      h.flush()
      assertEquals("$status", 3, h.network.sent.size)
      assertEquals("$status: everything after the refused batch", 70, h.network.sent.drop(1).sumOf { it.ids().size })
      assertEquals(0, h.engine.queuedCount)
    }
  }

  /** A timer that fired while an upload was out is not lost: the answer schedules the next batch. */
  @Test fun aTimerThatFiredDuringAnUploadIsNotLost() {
    val h = Harness()
    h.network.holds = true
    repeat(49) { h.engine.track("e$it") } // the batch of 50 is out
    h.engine.track("late") // its timer fires while the upload is in flight
    h.flush()
    assertEquals(1, h.network.sent.size)
    h.executor.timers.clear()
    h.network.answer()
    assertEquals(listOf(0.0), h.executor.timers)
  }

  /** A 401 disables the client and KEEPS the queue for the next launch. */
  @Test fun a401DisablesTheClientAndKeepsTheQueue() {
    val h = Harness()
    h.network.answers.add(TransportResponse(401))
    h.engine.track("a")
    h.flush()
    assertTrue(h.engine.disabled)
    assertEquals(listOf(TogoErrorCode.WRITE_KEY_REJECTED), h.errors.map { it.code })
    assertNull(h.engine.track("b"))
    assertEquals(2, h.engine.queuedCount)
  }

  /** The edge rejects events older than seven days, so the queue drops them locally and says how many. */
  @Test fun eventsOlderThanSevenDaysAreDroppedLocallyWithTheirCount() {
    val h = Harness()
    h.network.answers.add(TransportResponse(null))
    h.engine.track("old")
    h.flush()
    h.clock.advance(Wire.maxEventAgeMs + 1)
    h.flush()
    assertEquals("nothing left to upload", 1, h.network.sent.size)
    assertEquals(listOf(TogoErrorCode.TIMESTAMP_TOO_OLD), h.errors.map { it.code })
    assertEquals(2, h.errors[0].eventIds.size)
    assertTrue(h.logs.contains("2 event(s) dropped: older than 7 days"))
  }

  /** Per-event refusals in a 202 are the only notice anyone gets. */
  @Test fun rejectionsInAnAcceptedBatchAreReported() {
    val h = Harness()
    val id = h.engine.track("page_viewed")!!
    h.network.answers.add(
      TransportResponse(202, body = """{"rejected":[{"eventId":"$id","reason":"timestamp_too_far_future"}]}""".toByteArray()),
    )
    h.flush()
    assertEquals(listOf("timestamp_too_far_future"), h.errors.map { it.code.value })
    assertEquals("page_viewed", h.errors[0].eventName)
  }

  /** Fifty events is a batch: the fiftieth uploads without waiting. */
  @Test fun fiftyEventsFlushWithoutWaitingForTheTimer() {
    val h = Harness()
    repeat(49) { h.engine.track("e$it") } // + $session_started = 50
    assertEquals(1, h.network.sent.size)
    assertEquals(50, h.network.events.size)
  }

  /** A backlog during an outage waits for the backoff: tracking more events does not turn each one into a request. */
  @Test fun aBacklogWaitsForTheBackoffInsteadOfFlushingOnEveryTrack() {
    val h = Harness()
    h.network.answers.add(TransportResponse(503))
    repeat(49) { h.engine.track("e$it") }
    assertEquals("the full batch went, and was refused", 1, h.network.sent.size)
    repeat(20) { h.engine.track("more$it") }
    assertEquals("no request per track() while backing off", 1, h.network.sent.size)
    h.flush() // the backoff timer firing
    assertEquals(2, h.network.sent.size)
  }

  /** One upload at a time: a flush while one is in flight sends nothing new. */
  @Test fun onlyOneUploadIsInFlight() {
    val h = Harness()
    h.network.holds = true
    repeat(60) { h.engine.track("e$it") }
    h.flush()
    assertEquals(1, h.network.held.size)
    h.network.answer()
    h.flush()
    assertEquals("the next upload once the first was answered", 2, h.network.sent.size)
  }

  /** The queue file: one JSON array, written atomically, re-read by a new process. */
  @Test fun theQueueSurvivesAsAFileOnDisk() {
    val dir = Files.createTempDirectory("togo").toFile()
    try {
      val file = File(dir, "togo/queue.jsonl")
      val event = RawEvent("a", 1, "e1", "an", "d", null, null, mapOf("n" to JsonValue.Int(1)), null, null)
      EventQueue(FileQueueStore(file)) { _, _ -> }.add(EventQueue.Entry(event, AppInfo("1"), null))
      assertFalse("no temporary file left behind", File(dir, "togo/queue.jsonl.tmp").exists())
      val reread = EventQueue(FileQueueStore(file)) { _, _ -> }
      reread.hydrate()
      assertEquals(listOf(EventQueue.Entry(event, AppInfo("1"), null)), reread.entries)

      file.writeText("not json")
      val garbled = EventQueue(FileQueueStore(file)) { _, _ -> }
      garbled.hydrate()
      assertEquals(0, garbled.count)
      assertFalse("an unreadable queue is removed", file.exists())
    } finally {
      dir.deleteRecursively()
    }
  }

  /** The byte bound evicts the OLDEST event, like the count: an eviction by size drops the same kinds of event every time. */
  @Test fun theByteBoundEvictsTheOldestEvent() {
    val dropped = ArrayList<String>()
    val queue = EventQueue(MemoryQueueStore()) { events, _ -> dropped += events.map { it.eventName } }
    fun entry(name: String, size: Int) = EventQueue.Entry(
      RawEvent(name, 1, name, null, null, null, null, mapOf("v" to str("x".repeat(size))), null, null), null, null,
    )
    queue.add(entry("old", EventQueue.MAX_BYTES / 2))
    queue.add(entry("middle", EventQueue.MAX_BYTES / 2))
    queue.add(entry("new", 10))
    assertEquals(listOf("old"), dropped)
    assertEquals(listOf("middle", "new"), queue.entries.map { it.event.eventName })
  }

  /** An event over 256 KiB is dropped at `track()`, never sent, and reported: an event that large is a payload. */
  @Test fun anEventOver256KiBIsDroppedAtTrackAndReported() {
    val h = Harness()
    val properties = (0 until 256).associate { "p$it" to "x".repeat(1_024) }
    assertNull(h.engine.track("report_exported", properties))
    assertNotNull(h.engine.track("order_paid"))
    assertEquals(listOf(TogoErrorCode.EVENT_TOO_LARGE), h.errors.map { it.code })
  }

  /**
   * A process killed mid-upload — the system reclaiming a backgrounded app —
   * gives no callback. Taken events stay in the file until the upload is
   * answered, so the next launch sends them again under the same eventIds,
   * which the edge dedupes if the first upload did land.
   */
  @Test fun eventsInAnUploadCutOffByAKillAreSentAgainAtTheNextLaunch() {
    val h = Harness()
    h.network.holds = true
    h.engine.track("checkout_started")
    h.engine.track("checkout_completed")
    h.flush()
    assertEquals("out, and never answered", 1, h.network.sent.size)
    val firstIds = h.network.sent[0].ids()

    // Killed. A new process over the same files; the network answers now.
    h.network.holds = false
    h.start()
    h.flush()
    assertEquals(2, h.network.sent.size)
    assertEquals(listOf("\$session_started", "checkout_started", "checkout_completed"), names(h.network.sent[1]))
    assertEquals("the same eventIds, for the edge to dedupe", firstIds, h.network.sent[1].ids())
  }

  /** The positive half: an answered upload is out of the file, and events tracked while it was out stay, in order. */
  @Test fun anAnsweredUploadLeavesTheFileAndWhatCameAfterItStays() {
    val h = Harness()
    h.network.holds = true
    h.engine.track("first")
    h.flush()
    h.engine.track("second")
    assertEquals("in flight, still on disk", listOf("\$session_started", "first", "second"), h.storedNames())
    h.network.answer()
    assertEquals(listOf("second"), h.storedNames())

    h.network.holds = false
    h.start()
    h.flush()
    assertEquals("nothing sent twice", listOf("\$session_started", "first", "second"), h.network.names)
  }

  /** Consent moving to pending while an upload is out, then the upload failing: the events go back in the queue. */
  @Test fun aFailedUploadUnderPendingConsentKeepsItsEvents() {
    val h = Harness()
    h.network.holds = true
    h.engine.track("first")
    h.flush()
    h.engine.setConsent(TogoConsent.PENDING)
    h.network.answer(TransportResponse(503))
    assertEquals(2, h.engine.queuedCount)

    h.network.holds = false
    h.engine.setConsent(TogoConsent.GRANTED)
    h.flush()
    assertEquals(listOf("\$session_started", "first", "\$session_started", "first"), h.network.names)
  }

  /** …and a withdrawal while it is out deletes them, answered or not. */
  @Test fun aWithdrawalWhileAnUploadIsOutDeletesItsEvents() {
    val h = Harness()
    h.network.holds = true
    h.engine.track("first")
    h.flush()
    h.engine.setConsent(TogoConsent.DENIED)
    h.network.answer(TransportResponse(503))
    assertNull(h.queueStore.read())
    assertEquals(0, h.engine.queuedCount)
  }

  /**
   * Cleartext blocked by the platform — an http:// endpoint on a modern
   * Android — is said once, with the fix, and the client stops with the queue
   * kept, rather than retrying in silence until the events age out.
   */
  @Test fun aRefusalNoRetryCanChangeIsReportedAndTheQueueKept() {
    val h = Harness()
    h.network.answers.add(TransportResponse(null, failure = "the platform refused the connection: CLEARTEXT communication not permitted"))
    h.engine.track("a")
    h.executor.timers.clear()
    h.flush()
    assertTrue(h.engine.disabled)
    assertEquals(listOf(TogoErrorCode.ENDPOINT_INVALID), h.errors.map { it.code })
    assertTrue(h.errors[0].message.contains("CLEARTEXT"))
    assertTrue(h.errors[0].message.contains("network security config"))
    assertEquals("kept for a build that can reach the endpoint", listOf("\$session_started", "a"), h.storedNames())
    assertEquals("no silent retry scheduled", emptyList<Double>(), h.executor.timers)
  }

  /** A malformed endpoint disables the client with one line instead of throwing, and keeps the queue. */
  @Test fun aMalformedEndpointDisablesInsteadOfThrowing() {
    val h = Harness()
    h.options = h.options.copy(endpoint = "not a url")
    h.start()
    h.engine.track("a")
    h.flush()
    assertTrue(h.engine.disabled)
    assertTrue(h.network.sent.isEmpty())
    assertNotEquals(0, h.storedNames().size)
    assertEquals(listOf(TogoErrorCode.ENDPOINT_INVALID), h.errors.map { it.code })
  }

  private fun names(batch: JsonValue.Obj) =
    (batch["events"] as JsonValue.Arr).values.map { (it as JsonValue.Obj).string("eventName")!! }
}
