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

/** Consent: four states, `setup()` never delayed, nothing stored or sent while pending, and withdrawal deletes. */
class ConsentTest {
  @Test fun pendingHoldsInMemoryStoresNothingAndSendsNothing() {
    val h = Harness(consent = TogoConsent.PENDING)
    assertNotNull("the caller gets the id the released event will carry", h.engine.track("screen_viewed"))
    h.engine.identify("user_1")
    h.flush()

    assertTrue(h.network.sent.isEmpty())
    assertNull("no identifier minted or stored while pending", h.identity.stored)
    assertNull("no queue file while pending", h.queueStore.read())
    assertNull(h.engine.anonymousId)
    assertNull(h.engine.sessionId)
    assertEquals(2, h.engine.heldCount)
  }

  /** Granting releases what was held, under ids minted NOW, in one session whose start takes the first held event's time. */
  @Test fun grantingReleasesTheBufferUnderFreshIdsInOneSession() {
    val h = Harness(consent = TogoConsent.PENDING)
    val first = h.engine.track("screen_viewed")
    val heldAt = h.clock.wall
    h.clock.advance(30_000)
    h.engine.track("paywall_viewed")
    h.clock.advance(10_000)
    h.engine.setConsent(TogoConsent.GRANTED)
    h.flush()

    assertEquals(listOf("\$session_started", "screen_viewed", "paywall_viewed"), h.network.names)
    val events = h.network.events
    assertEquals(str(first!!), events[1]["eventId"])
    assertEquals(str(IsoTime.format(heldAt)), events[0]["clientEventTs"])
    assertEquals(1, events.map { it["sessionId"] }.toSet().size)
    assertNotNull(h.identity.stored?.anonymousId)
  }

  /** Withdrawal DELETES: the identity record and the queue, not merely stops. */
  @Test fun denyingDeletesTheStoredIdentityAndTheQueue() {
    val h = Harness()
    h.network.answers.add(TransportResponse(null))
    h.engine.track("screen_viewed")
    h.flush() // offline: the event stays queued
    assertNotNull(h.identity.stored)
    assertNotNull(h.queueStore.read())

    h.engine.setConsent(TogoConsent.DENIED)
    assertNull(h.identity.stored)
    assertNull(h.queueStore.read())
    assertNull(h.engine.track("after_denial"))
    assertNull(h.engine.anonymousId)
    h.flush()
    assertEquals("nothing is sent after a denial", 1, h.network.sent.size)
  }

  /** Ids written while consent was permitted are deleted by a denial that comes through pending. */
  @Test fun denyingAfterPendingStillDeletesTheStoredIdentity() {
    val h = Harness()
    h.engine.track("screen_viewed")
    assertNotNull(h.identity.stored?.anonymousId)
    h.engine.setConsent(TogoConsent.PENDING)
    h.engine.setConsent(TogoConsent.DENIED)
    assertNull(h.identity.stored)
    h.engine.setConsent(TogoConsent.GRANTED)
    assertNull("nothing restored from memory either", h.engine.anonymousId)
  }

  /**
   * A withdrawal forgets the signed-in user in memory as well as on the device:
   * a grant after it in the same process starts a stranger, never the person
   * from before the refusal.
   */
  @Test fun aGrantAfterAWithdrawalNeverBringsBackTheUser() {
    val h = Harness()
    h.engine.identify("u1")
    h.engine.track("before")
    val anonymous = h.engine.anonymousId
    h.engine.setConsent(TogoConsent.DENIED)
    h.engine.setConsent(TogoConsent.GRANTED)
    h.engine.track("after")
    h.flush()

    val after = h.network.events.last()
    assertEquals(str("after"), after["eventName"])
    assertNull("no userId after the withdrawal", after["userId"])
    assertTrue(after["anonymousId"] != str(anonymous!!))
    assertNull("not written back to the device", h.identity.stored?.userId)
    assertNotNull("identify() after the grant still works", h.engine.identify("u1"))
  }

  /** The same through pending: deny, then pending, then grant. */
  @Test fun aWithdrawalThroughPendingAlsoForgetsTheUser() {
    val h = Harness()
    h.engine.identify("u1")
    h.engine.setConsent(TogoConsent.DENIED)
    h.engine.setConsent(TogoConsent.PENDING)
    h.engine.track("held")
    h.engine.setConsent(TogoConsent.GRANTED)
    h.flush()
    assertEquals(listOf("\$session_started", "held"), h.network.names)
    assertTrue(h.network.events.all { it["userId"] == null })
  }

  /** A client started with `denied` deletes a queue an earlier process left, rather than uploading it. */
  @Test fun aQueueLeftFromBeforeADenialIsDeletedAtStartUp() {
    val h = Harness()
    h.network.answers.add(TransportResponse(null))
    h.engine.track("screen_viewed")
    h.flush()
    assertNotNull(h.queueStore.read())

    h.options = h.options.copy(consent = TogoConsent.DENIED)
    h.start()
    assertNull(h.queueStore.read())
  }

  @Test fun denyingWhilePendingDiscardsTheBuffer() {
    val h = Harness(consent = TogoConsent.PENDING)
    h.engine.track("screen_viewed")
    h.engine.setConsent(TogoConsent.DENIED)
    h.engine.setConsent(TogoConsent.GRANTED)
    h.flush()
    assertTrue(h.network.sent.isEmpty())
  }

  /** The buffer is bounded; the OLDEST is dropped and reported. */
  @Test fun thePendingBufferIsBoundedOldestFirst() {
    val h = Harness(consent = TogoConsent.PENDING, consentBufferMaxEvents = 2)
    val first = h.engine.track("one")
    h.engine.track("two")
    h.engine.track("three")
    assertEquals(2, h.engine.heldCount)
    assertEquals(listOf(TogoErrorCode.QUEUE_FULL), h.errors.map { it.code })
    assertEquals(listOf(first), h.errors.first().eventIds)
    h.engine.setConsent(TogoConsent.GRANTED)
    h.flush()
    assertEquals(listOf("\$session_started", "two", "three"), h.network.names)
  }

  /** A userId asserted under pending rides the events held with it, and is stored at the first permitted write. */
  @Test fun aUserIdAssertedWhilePendingIsKeptAndStoredOnGrant() {
    val h = Harness(consent = TogoConsent.PENDING)
    h.engine.identify("user_1")
    h.engine.track("screen_viewed")
    h.engine.setConsent(TogoConsent.GRANTED)
    h.flush()
    assertEquals(listOf("\$session_started", "\$identify", "screen_viewed"), h.network.names)
    assertEquals(setOf(str("user_1")), h.network.events.map { it["userId"] }.toSet())
    assertEquals("user_1", h.identity.stored?.userId)
  }

  /**
   * A relaunch under pending consent with a user signed in from the last
   * launch: what was held carries that user once the grant reads the record,
   * rather than going out anonymous.
   */
  @Test fun eventsHeldBeforeTheRecordIsReadCarryTheStoredUser() {
    val identity = MemoryIdentityStore(IdentityRecord(deviceId = "dev-1", deviceIdMode = "install", anonymousId = "anon-1", userId = "u1"))
    val h = Harness(consent = TogoConsent.PENDING, identity = identity)
    h.engine.track("screen_viewed")
    h.engine.setConsent(TogoConsent.GRANTED)
    h.engine.track("after_grant")
    h.flush()
    assertEquals(listOf("\$session_started", "screen_viewed", "after_grant"), h.network.names)
    assertEquals(listOf(str("u1"), str("u1"), str("u1")), h.network.events.map { it["userId"] })
  }

  /** …but an event held after a logout in this process is the stranger's, and one held before it the user's. */
  @Test fun aLogoutWhileHeldSplitsTheBufferAtTheLogout() {
    val identity = MemoryIdentityStore(IdentityRecord(deviceId = "dev-1", deviceIdMode = "install", anonymousId = "anon-1", userId = "u1"))
    val h = Harness(consent = TogoConsent.PENDING, identity = identity)
    h.engine.track("before_logout")
    h.engine.reset()
    h.engine.track("after_logout")
    h.engine.setConsent(TogoConsent.GRANTED)
    h.flush()
    val byName = h.network.events.associate { it.string("eventName") to it["userId"] }
    assertEquals(str("u1"), byName["before_logout"])
    assertNull(byName["after_logout"])
    assertNull("the logout reached the device", identity.stored?.userId)
    assertTheTwoPeopleAreNotLinked(h, previous = "before_logout", next = listOf("after_logout"))
  }

  /** …and a different user identified while held: u2's `$identify` and the event from before it are on different anonymous ids. */
  @Test fun anEventHeldBeforeAnIdentifyOfAnotherUserKeepsTheStoredIds() {
    val identity = MemoryIdentityStore(IdentityRecord(deviceId = "dev-1", deviceIdMode = "install", anonymousId = "anon-1", userId = "u1"))
    val h = Harness(consent = TogoConsent.PENDING, identity = identity)
    h.engine.track("before_identify")
    h.engine.identify("u2")
    h.engine.setConsent(TogoConsent.GRANTED)
    h.flush()
    assertEquals(str("u2"), h.network.events.first { it.string("eventName") == "\$identify" }["userId"])
    assertTheTwoPeopleAreNotLinked(h, previous = "before_identify", next = listOf("\$identify"))
  }

  /** The same on a locked device, logged out before the unlock. */
  @Test fun anEventHeldBeforeALockedLogoutKeepsTheStoredIds() {
    val identity = MemoryIdentityStore(IdentityRecord(deviceId = "dev-1", deviceIdMode = "install", anonymousId = "anon-1", userId = "u1"))
    identity.locked = true
    val h = Harness(identity = identity)
    h.engine.track("before_logout")
    h.engine.reset()
    h.engine.track("after_logout")
    identity.locked = false
    h.engine.identityBecameAvailable()
    h.flush()
    assertNull(h.network.events.first { it.string("eventName") == "after_logout" }["userId"])
    assertTheTwoPeopleAreNotLinked(h, previous = "before_logout", next = listOf("after_logout"))
  }

  /** …and a different user identified on a locked device. */
  @Test fun anEventHeldBeforeALockedIdentifyOfAnotherUserKeepsTheStoredIds() {
    val identity = MemoryIdentityStore(IdentityRecord(deviceId = "dev-1", deviceIdMode = "install", anonymousId = "anon-1", userId = "u1"))
    identity.locked = true
    val h = Harness(identity = identity)
    h.engine.track("before_identify")
    h.engine.identify("u2")
    identity.locked = false
    h.engine.identityBecameAvailable()
    h.flush()
    assertEquals(str("u2"), h.network.events.first { it.string("eventName") == "\$identify" }["userId"])
    assertTheTwoPeopleAreNotLinked(h, previous = "before_identify", next = listOf("\$identify"))
  }

  /**
   * The two people either side of a logout share no id but the device's: the
   * event from before it keeps the stored anonymous id and no session, and no
   * event pairs the last user with the next anonymous id.
   */
  private fun assertTheTwoPeopleAreNotLinked(h: Harness, previous: String, next: List<String>) {
    val events = h.network.events
    val before = events.firstOrNull { it.string("eventName") == previous }
    assertNotNull("no $previous", before)
    assertEquals(str("u1"), before!!["userId"])
    assertEquals("the last user's own anonymous id", str("anon-1"), before["anonymousId"])
    assertNull("neither the last user's session nor the next one", before["sessionId"])
    for (name in next) {
      val after = events.firstOrNull { it.string("eventName") == name }
      assertNotNull("no $name", after)
      assertTrue("$name is on a new anonymous id", after!!["anonymousId"] != str("anon-1"))
    }
    val fresh = events.mapNotNull { it["anonymousId"] }.toSet() - str("anon-1")
    assertTrue(
      "u1 never rides the next anonymous id: $events",
      events.none { it["userId"] == str("u1") && it["anonymousId"] in fresh },
    )
  }

  /** The same on a locked device: held until the unlock, then sent as the stored user. */
  @Test fun eventsHeldWhileTheStoreIsUnreadableCarryTheStoredUser() {
    val identity = MemoryIdentityStore(IdentityRecord(deviceId = "dev-1", deviceIdMode = "install", anonymousId = "anon-1", userId = "u1"))
    identity.locked = true
    val h = Harness(identity = identity)
    h.engine.track("push_received")
    identity.locked = false
    h.engine.identityBecameAvailable()
    h.flush()
    assertEquals(str("u1"), h.network.events.last()["userId"])
  }

  /** Not-required to granted is a no-op change: nothing held, nothing re-sent. */
  @Test fun movingBetweenTwoPermittedStatesChangesNothing() {
    val h = Harness()
    h.engine.track("a")
    h.engine.setConsent(TogoConsent.GRANTED)
    h.flush()
    assertEquals(listOf("\$session_started", "a"), h.network.names)
  }

  // A withdrawal on a device that cannot delete yet (before the first unlock, or an I/O failure).

  private fun identityFile(folder: java.io.File) = java.io.File(folder, "togo/identity.json")

  /** The identity file a withdrawal could not delete is deleted at the unlock, and never read back. */
  @Test fun aWithdrawalWhileLockedDeletesTheIdentityFileAtTheUnlock() {
    val folder = java.nio.file.Files.createTempDirectory("togo").toFile()
    try {
      var unlocked = true
      val scheduler = ManualScheduler()
      val h = Harness()
      fun engine(consent: TogoConsent) = Engine(
        h.options.copy(consent = consent), FileIdentityStore(identityFile(folder), scheduler, { unlocked }),
        generatedDeviceIds, h.queueStore, h.network, h.executor, h.executor, h.clock, Logger {},
      )
      val first = engine(TogoConsent.NOT_REQUIRED)
      first.track("a")
      val device = first.deviceId
      unlocked = false
      first.setConsent(TogoConsent.DENIED)
      assertTrue("locked: still there", identityFile(folder).exists())
      unlocked = true
      first.identityBecameAvailable()
      assertFalse("deleted at the unlock", identityFile(folder).exists())

      val next = engine(TogoConsent.GRANTED)
      next.track("b")
      assertNotEquals(device, next.deviceId)
    } finally {
      folder.deleteRecursively()
    }
  }

  /** A grant while still locked mints new ids; the unlock writes them over the file, never brings the old ones back. */
  @Test fun aGrantWhileLockedAfterAWithdrawalNeverReadsTheOldIdsBack() {
    val folder = java.nio.file.Files.createTempDirectory("togo").toFile()
    try {
      var unlocked = true
      val scheduler = ManualScheduler()
      val h = Harness()
      fun store() = FileIdentityStore(identityFile(folder), scheduler, { unlocked })
      val first = Engine(h.options, store(), generatedDeviceIds, h.queueStore, h.network, h.executor, h.executor, h.clock, Logger {})
      first.track("a")
      val device = first.deviceId
      unlocked = false
      first.setConsent(TogoConsent.DENIED)
      first.setConsent(TogoConsent.GRANTED)
      first.track("b")
      val minted = first.deviceId
      assertNotEquals(device, minted)
      unlocked = true
      first.identityBecameAvailable()
      first.appBackgrounded()
      val read = (store().load() as IdentityLoad.Found).record
      assertEquals(minted, read.deviceId)
    } finally {
      folder.deleteRecursively()
    }
  }

  /** A client set up with consent denied deletes a stored identity, as it deletes the stored queue. */
  @Test fun setupWithConsentDeniedDeletesTheStoredIdentity() {
    val identity = MemoryIdentityStore(IdentityRecord(deviceId = "dev-1", deviceIdMode = "install", anonymousId = "anon-1", userId = "u1"))
    Harness(consent = TogoConsent.DENIED, identity = identity)
    assertNull(identity.stored)
  }

  /**
   * The queue file a withdrawal could not delete is never appended to: an
   * event queued after the grant rewrites it with only the new events, at the
   * unlock or at the next change, so nothing from before the refusal is read
   * back at the next launch.
   */
  @Test fun aWithdrawalWhileLockedNeverLeavesTheOldEventsInTheQueueFile() {
    for (path in listOf("unlock", "next event")) {
      val h = Harness()
      h.network.answers.add(TransportResponse(null))
      h.engine.track("before")
      h.flush() // Offline: kept in the file.
      h.queueStore.stuck = true
      h.engine.setConsent(TogoConsent.DENIED)
      h.engine.setConsent(TogoConsent.GRANTED)
      h.engine.track("after")
      h.queueStore.stuck = false
      if (path == "unlock") h.engine.identityBecameAvailable() else h.engine.track("later")
      assertEquals(path, false, h.storedNames().contains("before"))

      // The next launch reads only what came after the grant. (The first,
      // offline attempt at "before" is in the fake network's record; forget it.)
      h.network.sent.clear()
      h.start()
      h.flush()
      assertFalse(path, h.network.names.contains("before"))
      assertTrue(path, h.network.names.contains("after"))
    }
  }
}
