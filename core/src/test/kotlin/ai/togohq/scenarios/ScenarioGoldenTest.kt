package ai.togohq.scenarios

import ai.togohq.core.IsoTime
import ai.togohq.core.JsonValue
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Every scenario in the shared scenarios re-records through the Kotlin core to
 * its `expected/` batches. The goldens are the browser's; `Projection` says
 * exactly what a phone is allowed to differ in.
 */
class ScenarioGoldenTest {
  private fun golden(name: String) = ScenarioFormat.readJson(File(ScenarioFormat.defaultRoot, "expected/$name.json"))

  @Test fun everyScenarioReRecordsToItsExpectedBatches() {
    val names = ScenarioFormat.scenarioNames()
    // Guard the guard: an empty or moved directory must fail, not pass vacuously.
    assertTrue("no scenarios found under ${ScenarioFormat.defaultRoot}", names.size >= 17)
    var withDevice = 0
    val failures = ArrayList<String>()
    for (name in names) {
      val recorded = Projection.recorded(ScenarioDriver.record(name))
      // Three scenarios are server-only: they compare as empty on both sides,
      // which is the honest answer.
      val expected = Projection.expected(golden(name))
      if (expected.isNotEmpty()) withDevice++
      Projection.difference(expected, recorded)?.let { failures += "$name: $it" }
    }
    assertEquals(emptyList<String>(), failures)
    assertTrue("most scenarios have a device to replay", withDevice >= 14)
  }

  /**
   * Every scenario under `native/` re-records to its `expected-native/`
   * batches. The iOS SDK recorded them; this runner is held to them through
   * `Projection.nativeExpected`.
   */
  @Test fun everyNativeScenarioReRecordsToItsExpectedNativeBatches() {
    val names = ScenarioFormat.nativeScenarioNames()
    assertTrue("no native scenarios under ${ScenarioFormat.defaultRoot}", names.size >= 3)
    val failures = ArrayList<String>()
    for (name in names) {
      val expected = Projection.nativeExpected(
        ScenarioFormat.readJson(File(ScenarioFormat.defaultRoot, "expected-native/$name.json")),
      )
      assertTrue(name, expected.isNotEmpty())
      Projection.difference(expected, Projection.nativeRecorded(ScenarioDriver.recordNative(name)))?.let { failures += "$name: $it" }
    }
    assertEquals(emptyList<String>(), failures)
  }

  /** What the native goldens pin, read off this runner's recording, so a rule that broke shows by name and not only as a diff. */
  @Test fun theNativeRecordingCarriesTheLifecycleRules() {
    fun events(name: String) = ScenarioDriver.recordNative(name).flatMap { (it.body["events"] as JsonValue.Arr).values.map { e -> e as JsonValue.Obj } }
    fun names(list: List<JsonValue.Obj>) = list.map { it.string("eventName")!! }

    val installed = events("app_installed_then_opened")
    assertEquals(listOf("\$app_installed", "\$session_started", "\$app_opened", "screen_viewed"), names(installed))
    assertNull(installed[0]["sessionId"])

    val updated = ScenarioDriver.recordNative("app_updated")
    assertEquals(listOf("140", "150"), updated.map { (it.body["app"] as JsonValue.Obj).string("build") })
    val afterUpdate = (updated[1].body["events"] as JsonValue.Arr).values.map { it as JsonValue.Obj }
    assertEquals(listOf("\$app_updated", "\$app_opened", "\$app_opened"), names(afterUpdate).filter { it.startsWith("\$app_") })
    assertEquals(
      JsonValue.Obj(mapOf("previous_app_version" to JsonValue.Str("1.4.0"), "previous_app_build" to JsonValue.Str("140"))),
      afterUpdate[0]["properties"],
    )

    val day = events("app_background_return")
    val opens = day.filter { it.string("eventName") == "\$app_opened" }
    assertEquals(
      "one per move into the foreground; the second window and the push launch send none",
      listOf(false, false, true, true).map { JsonValue.Bool(it) },
      opens.map { (it["properties"] as JsonValue.Obj)["from_background"] },
    )
    assertEquals(3, names(day).count { it == "\$session_started" })
    val pushes = day.filter { it.string("eventName") == "push_received" }
    assertNotNull("inside the window: joins", pushes[0]["sessionId"])
    assertNull("outside it: none, never a new one", pushes[1]["sessionId"])
    assertNull(pushes[2]["sessionId"])
  }

  /** The projection removes only the platform's fields, and the recording does carry them. */
  @Test fun theNativeProjectionRemovesOnlyThePlatformsFields() {
    val raw = ScenarioDriver.recordNative("app_installed_then_opened")[0].body
    assertEquals("install", raw.string("deviceIdMode"))
    assertEquals("samsung", (raw["device"] as JsonValue.Obj).string("manufacturer"))
    val projected = Projection.nativeRecorded(listOf(Upload("x", raw)))[0].body
    assertEquals(raw.values.keys - setOf("device", "deviceIdMode"), projected.values.keys)
    assertNotNull("the build is compared", projected["app"])
  }

  /**
   * A process killed within 700 ms of its last stop never reaches its
   * background: the next launch must not run it. Thirty-one minutes after the
   * open, nothing marked activity at the stop, so the next foreground is a new
   * session; a background left running from the killed process would mark it
   * and keep the old one.
   */
  @Test fun aKilledProcesssPendingBackgroundNeverRuns() {
    val root = Files.createTempDirectory("scenarios").toFile()
    try {
      File(root, "native").mkdirs()
      File(root, "native/x.json").writeText(
        """{"name":"x","steps":[
          {"launch":{"appVersion":"1.0","appBuild":"1"}},
          {"foreground":"main","at":"+1s"},
          {"background":"main","at":"+29m"},
          {"launch":{"appVersion":"1.0","appBuild":"1"},"at":"+100ms"},
          {"launch":{"appVersion":"1.0","appBuild":"1"},"at":"+1m"},
          {"foreground":"main","at":"+1m"}
        ]}""",
      )
      val names = ScenarioDriver.recordNative("x", root).flatMap { upload ->
        (upload.body["events"] as JsonValue.Arr).values.map { (it as JsonValue.Obj).string("eventName") }
      }
      assertEquals(2, names.count { it == "\$session_started" })
    } finally {
      root.deleteRecursively()
    }
  }

  @Test fun aNativeScenarioMustStartWithALaunch() {
    val root = Files.createTempDirectory("scenarios").toFile()
    try {
      File(root, "native").mkdirs()
      File(root, "native/x.json").writeText("""{"name":"x","steps":[{"foreground":"main"}]}""")
      try {
        ScenarioFormat.loadNativeScenario("x", root)
        fail("loaded")
      } catch (e: ScenarioError) {
        assertTrue(e.message!!.contains("starts with a launch"))
      }
    } finally {
      root.deleteRecursively()
    }
  }

  /** The comparison has teeth: a recording that differs by one event, or by one millisecond, is caught. */
  @Test fun theComparisonCatchesADroppedEventAndAMovedTimestamp() {
    val expected = Projection.expected(golden("trial_converts"))
    val recorded = Projection.recorded(ScenarioDriver.record("trial_converts"))
    assertNull(Projection.difference(expected, recorded))

    val first = recorded[0].body
    val events = (first["events"] as JsonValue.Arr).values
    val dropped = listOf(Upload(recorded[0].client, JsonValue.Obj(first.values + ("events" to JsonValue.Arr(events.dropLast(1)))))) + recorded.drop(1)
    assertNotNull(Projection.difference(expected, dropped))

    val event = events[1] as JsonValue.Obj
    val ms = IsoTime.parse(event.string("clientEventTs")!!)!!
    val shifted = JsonValue.Obj(event.values + ("clientEventTs" to JsonValue.Str(IsoTime.format(ms + 1))))
    val moved = listOf(Upload(recorded[0].client, JsonValue.Obj(first.values + ("events" to JsonValue.Arr(events.toMutableList().also { it[1] = shifted }))))) + recorded.drop(1)
    assertNotNull(Projection.difference(expected, moved))
  }

  /** What is removed from the comparison is ONLY what a phone cannot match, and the recording does carry it. */
  @Test fun theRecordingCarriesTheNativeFieldsTheProjectionRemoves() {
    val raw = ScenarioDriver.record("trial_converts")
    val batch = raw[0].body
    assertEquals("install", batch.string("deviceIdMode"))
    assertEquals(ScenarioDriver.APP.json(), batch["app"])
    assertEquals("samsung", (batch["device"] as JsonValue.Obj).string("manufacturer"))
    val event = (batch["events"] as JsonValue.Arr).values[0] as JsonValue.Obj
    assertEquals("sdk-android", (event["sdk"] as JsonValue.Obj).string("name"))
    assertNull(event["page"])
  }

  /** The planner is the browser's, exactly: mulberry32's first draws for seed 1 are fixed numbers. */
  @Test fun thePrngIsMulberry32() {
    val random = ScenarioFormat.prng(1)
    // Computed with the browser runner's prng(1).
    assertEquals(0.6270739405881613, random(), 0.0)
    assertEquals(0.002735721180215478, random(), 0.0)
    assertEquals(0.5274470399599522, random(), 0.0)
  }

  /** A verb this runner cannot perform fails the load rather than being skipped. */
  @Test fun anUnknownVerbFailsTheLoad() {
    val root = Files.createTempDirectory("scenarios").toFile()
    try {
      File(root, "scenarios").mkdirs()
      File(root, "scenarios/x.json").writeText("""{"steps":[{"getExperiment":"a"}]}""")
      try {
        ScenarioFormat.loadScenario("x", root)
        fail("loaded")
      } catch (e: ScenarioError) {
        assertTrue(e.message!!.contains("getExperiment"))
      }
    } finally {
      root.deleteRecursively()
    }
  }
}
