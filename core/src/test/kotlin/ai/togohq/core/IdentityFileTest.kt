package ai.togohq.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The identity file on its own, and an engine over it across a relaunch. */
class IdentityFileTest {
  @get:Rule val folder = TemporaryFolder()

  private val record = IdentityRecord(
    deviceId = "d-1", deviceIdMode = "install", anonymousId = "a-1", userId = "u-1",
    session = SessionRecord("s-1", 1_000, 2_000), appVersion = "1.2.0", appBuild = "12",
  )

  private fun file() = File(folder.root, "togo/identity.json")

  @Test fun theRecordRoundTripsThroughTheFile() {
    val scheduler = ManualScheduler()
    FileIdentityStore(file(), scheduler).apply {
      save(record)
      persistNow()
    }
    // A new process: nothing in memory, only the file.
    assertEquals(IdentityLoad.Found(record), FileIdentityStore(file(), scheduler).load())
    val json = JsonValue.parse(file().readBytes()) as JsonValue.Obj
    assertEquals(JsonValue.Int(1), json["v"])
    assertEquals(str("install"), json["deviceIdMode"])
  }

  @Test fun writesAreCoalescedAndPersistNowWritesAtOnce() {
    val scheduler = ManualScheduler()
    val store = FileIdentityStore(file(), scheduler)
    store.save(record)
    fun stored() = (FileIdentityStore(file(), scheduler).load() as IdentityLoad.Found).record
    fun active(ms: Long) = record.copy(session = record.session!!.copy(lastActivityAtMs = ms))
    store.save(active(3_000))
    store.save(active(4_000))
    assertEquals("an activity time alone waits", 2_000L, stored().session!!.lastActivityAtMs)
    assertEquals("one write is scheduled for both saves", 1, scheduler.pending)
    scheduler.advance(999)
    assertEquals(2_000L, stored().session!!.lastActivityAtMs)
    scheduler.advance(1)
    assertEquals(4_000L, stored().session!!.lastActivityAtMs)

    store.save(active(5_000))
    store.persistNow()
    assertEquals("persistNow cancels the pending write", 0, scheduler.pending)
    assertEquals(5_000L, stored().session!!.lastActivityAtMs)
  }

  /** Who the install is, who is signed in, which session and version: written at once, never on the debounce. */
  @Test fun everyOtherChangeIsWrittenAtOnce() {
    val scheduler = ManualScheduler()
    val store = FileIdentityStore(file(), scheduler)
    fun stored() = (FileIdentityStore(file(), scheduler).load() as IdentityLoad.Found).record
    store.save(record)
    assertEquals("the first save", record, stored())
    for (next in listOf(
      record.copy(userId = null), record.copy(userId = "u-2"), record.copy(anonymousId = "a-2"),
      record.copy(deviceId = "d-2"), record.copy(session = SessionRecord("s-2", 9_000, 9_000)), record.copy(session = null),
      record.copy(appVersion = "1.3.0"), record.copy(appBuild = "13"),
    )) {
      store.save(next)
      assertEquals(next, stored())
    }
    assertEquals(0, scheduler.pending)
  }

  @Test fun aLockedDeviceIsUnavailableWhetherOrNotTheFileIsThere() {
    val scheduler = ManualScheduler()
    var unlocked = false
    assertEquals(
      "no file on a locked device is not a first launch",
      IdentityLoad.Unavailable, FileIdentityStore(file(), scheduler, { unlocked }).load(),
    )
    FileIdentityStore(file(), scheduler).apply {
      save(record)
      persistNow()
    }
    val store = FileIdentityStore(file(), scheduler, { unlocked })
    assertEquals(IdentityLoad.Unavailable, store.load())
    unlocked = true
    assertEquals(IdentityLoad.Found(record), store.load())
  }

  @Test fun noFileOnAnUnlockedDeviceIsAbsent() {
    assertEquals(IdentityLoad.Absent, FileIdentityStore(file(), ManualScheduler()).load())
  }

  @Test fun aFileThatIsThereButDoesNotReadIsUnavailable() {
    // A directory where the file should be: there, and not readable as a file.
    file().mkdirs()
    assertEquals(IdentityLoad.Unavailable, FileIdentityStore(file(), ManualScheduler()).load())
  }

  @Test fun aCorruptFileIsAbsentAndTheNextSaveReplacesIt() {
    file().parentFile.mkdirs()
    file().writeText("{\"v\":")
    val logs = ArrayList<String>()
    val scheduler = ManualScheduler()
    val store = FileIdentityStore(file(), scheduler, log = Logger { logs.add(it) })
    assertEquals(IdentityLoad.Absent, store.load())
    assertTrue(logs.single().contains("starts as new"))
    store.save(record)
    store.persistNow()
    assertEquals(IdentityLoad.Found(record), FileIdentityStore(file(), scheduler).load())
  }

  @Test fun aFileFromALaterFormatIsNotReadAsThisOne() {
    file().parentFile.mkdirs()
    file().writeText("{\"v\":2,\"deviceId\":\"d-9\"}")
    assertEquals(IdentityLoad.Absent, FileIdentityStore(file(), ManualScheduler(), log = Logger {}).load())
  }

  @Test fun nothingIsWrittenWhileLocked() {
    var unlocked = false
    val scheduler = ManualScheduler()
    val store = FileIdentityStore(file(), scheduler, { unlocked })
    store.save(record)
    store.persistNow()
    assertFalse(file().exists())
    unlocked = true
    store.persistNow()
    assertTrue("the record waited in memory and is written once it can be", file().exists())
  }

  @Test fun eraseDeletesTheFileAndAPendingWriteWritesNothing() {
    val scheduler = ManualScheduler()
    val store = FileIdentityStore(file(), scheduler)
    store.save(record)
    store.persistNow()
    store.save(record.copy(userId = "u-2"))
    store.erase()
    scheduler.advance(5_000)
    assertFalse(file().exists())
    assertEquals(IdentityLoad.Absent, store.load())
  }

  /** An engine over the file, and a new one over the same file with nothing scheduled run: the process was killed. */
  private class Killable(private val folder: File) {
    val h = Harness()
    private var scheduler = ManualScheduler()
    var engine = start()
    fun start(): Engine {
      scheduler = ManualScheduler() // A kill drops whatever the last process had scheduled.
      engine = Engine(
        h.options, FileIdentityStore(File(folder, "togo/identity.json"), scheduler), generatedDeviceIds,
        h.queueStore, h.network, h.executor, h.executor, h.clock, Logger {},
      )
      return engine
    }
  }

  /** Killed within a second of its first event: the relaunch has the same install id, and no second identity. */
  @Test fun aKillRightAfterTheFirstEventKeepsTheInstallId() {
    val k = Killable(folder.root)
    k.engine.track("a")
    val device = k.engine.deviceId
    k.start().track("b")
    k.engine.flush()
    assertEquals(setOf(device), k.h.network.events.map { it.string("deviceId") }.toSet())
  }

  /** Killed right after a logout: the next person's events never go out as the last user. */
  @Test fun aKillRightAfterALogoutKeepsTheLogout() {
    val k = Killable(folder.root)
    k.engine.identify("u1")
    val anonymous = k.engine.anonymousId
    k.engine.reset()
    k.start().track("next_person")
    k.engine.flush()
    val event = k.h.network.events.last()
    assertEquals(null, event["userId"])
    assertNotEquals(str(anonymous!!), event["anonymousId"])
  }

  @Test fun aKillRightAfterIdentifyKeepsTheUser() {
    val k = Killable(folder.root)
    k.engine.track("a")
    k.engine.identify("u1")
    k.start().track("b")
    k.engine.flush()
    assertEquals(str("u1"), k.h.network.events.last()["userId"])
  }

  /** The first launch mints one install id; a relaunch over the same file reads it back. */
  @Test fun anEngineOverTheFileKeepsItsIdentityAcrossARelaunch() {
    val scheduler = ManualScheduler()
    val h = Harness()
    fun engine() = Engine(
      h.options, FileIdentityStore(file(), scheduler), generatedDeviceIds, MemoryQueueStore(), h.network,
      h.executor, h.executor, h.clock, Logger {},
    )
    val first = engine()
    first.track("a")
    val device = first.deviceId!!
    val anonymous = first.anonymousId!!
    scheduler.advance(1_000)

    val second = engine()
    second.track("b")
    assertEquals(device, second.deviceId)
    assertEquals(anonymous, second.anonymousId)
    second.flush()
    val modes = h.network.sent.map { it["deviceIdMode"] }.toSet()
    assertEquals(setOf<JsonValue?>(str("install")), modes)

    // A withdrawal deletes the file: the next install id is a new one.
    second.setConsent(ai.togohq.TogoConsent.DENIED)
    assertFalse(file().exists())
    val third = engine()
    third.track("c")
    assertNotEquals(device, third.deviceId)
  }
}
