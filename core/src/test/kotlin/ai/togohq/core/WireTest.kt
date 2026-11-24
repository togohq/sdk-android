package ai.togohq.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WireTest {
  /** An integer stays an integer and a boolean never becomes 1. */
  @Test fun propertyValuesKeepTheirTypes() {
    val props = toProps(
      mapOf("amount_minor" to 5999, "price" to 9.99, "trial" to true, "coupon" to null, "plan" to "pro",
        "whole" to 2.0, "ratio" to 0.1f, "big" to 9_000_000_000L),
    ) { key, _ -> fail("refused $key") }
    val text = JsonValue.Obj(props).serialized()
    assertEquals(
      """{"amount_minor":5999,"big":9000000000,"coupon":null,"plan":"pro","price":9.99,"ratio":0.1,"trial":true,"whole":2}""",
      text,
    )
    val parsed = JsonValue.parse(text) as JsonValue.Obj
    assertEquals(JsonValue.Int(5999), parsed["amount_minor"])
    assertEquals(JsonValue.Bool(true), parsed["trial"])
    assertEquals(JsonValue.Dbl(9.99), parsed["price"])
  }

  @Test fun aNestedValueIsRefusedPerKeyNeverSent() {
    val refused = mutableListOf<String>()
    val props = toProps(mapOf("ok" to 1, "nested" to mapOf("a" to 1), "list" to listOf(1), "obj" to Any())) { key, _ ->
      refused += key!!
    }
    assertEquals(setOf("ok"), props.keys)
    assertEquals(listOf("nested", "list", "obj"), refused)
  }

  @Test fun nonFiniteNumbersAreWrittenAsNull() {
    assertEquals("[null,null,null]", JsonValue.Arr(listOf(JsonValue.Dbl(Double.NaN),
      JsonValue.Dbl(Double.POSITIVE_INFINITY), JsonValue.Dbl(Double.NEGATIVE_INFINITY))).serialized())
  }

  @Test fun stringsEscapeAndRoundTrip() {
    val tricky = "quote \" backslash \\ newline \n tab \t bell \u0007 é 🦊"
    val text = JsonValue.Str(tricky).serialized()
    assertTrue(text.contains("\\u0007"))
    assertEquals(JsonValue.Str(tricky), JsonValue.parse(text))
    assertEquals(JsonValue.Str("🦊"), JsonValue.parse("\"🦊\""))
  }

  /**
   * Half a surrogate pair — a value cut mid-emoji by `substring()`, a damaged
   * queue file, a 202 body — is replaced with U+FFFD, written or read, never
   * written as `?` or a crash.
   */
  @Test fun anUnpairedSurrogateIsReplacedNotACrash() {
    val cut = "🦊".substring(0, 1)
    assertEquals("\"a\uFFFDb\"", JsonValue.Str("a${cut}b").serialized())
    assertEquals("\"\uFFFDx\"", JsonValue.Str("\uDC00x").serialized())
    assertEquals(JsonValue.Str("\uFFFDA"), JsonValue.parse("\"\\uD800\\u0041\""))
    assertEquals(JsonValue.Str("\uFFFDx"), JsonValue.parse("\"\\uD800x\""))
    assertEquals(JsonValue.Str("\uFFFD"), JsonValue.parse("\"\\uDC00\""))
    assertEquals(JsonValue.Str("🦊"), JsonValue.parse("\"\\uD83E\\uDD8A\""))
    // What is written is valid UTF-8 that reads back as written.
    val bytes = JsonValue.Str("a$cut").serialized().toByteArray(Charsets.UTF_8)
    assertEquals(JsonValue.Str("a\uFFFD"), JsonValue.parse(bytes))
  }

  @Test fun malformedJsonIsAnErrorNotACrash() {
    for (bad in listOf("", "{", "{\"a\":}", "[1,]", "tru", "\"open", "{} x", "\"\\u12\"")) {
      try {
        JsonValue.parse(bad)
        fail("parsed: $bad")
      } catch (_: JsonParseException) {
      }
    }
  }

  /** UTC ISO-8601 with milliseconds and a `Z`, always. */
  @Test fun timestampsAreUtcWithMilliseconds() {
    assertEquals("2026-10-21T12:00:00.000Z", IsoTime.format(1_792_584_000_000))
    assertEquals("1970-01-01T00:00:00.000Z", IsoTime.format(0))
    assertEquals("2000-02-29T00:00:00.007Z", IsoTime.format(951_782_400_007))
    assertEquals("1969-12-31T23:59:59.999Z", IsoTime.format(-1))
    for (ms in listOf(0L, 1_792_584_000_123L, 951_782_400_007L, 4_102_444_799_999L)) {
      assertEquals(ms, IsoTime.parse(IsoTime.format(ms)))
    }
    assertEquals(1_792_584_000_000L, IsoTime.parse("2026-10-21T12:00:00Z"))
    assertNull(IsoTime.parse("2026-10-21 12:00:00"))
  }

  /**
   * The fixture the server's contract test parses with the SERVER's schema.
   * This half pins that it is what this SDK serialises, so the two halves meet
   * at one file rather than at two descriptions of it.
   */
  @Test fun theWireFixtureIsWhatThisSdkSerialises() {
    val event = RawEvent(
      eventName = "plan_selected", clientEventMs = 1_792_580_400_000,
      eventId = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
      anonymousId = "6a1d0e2c-7b3f-4c5d-9e8f-0a1b2c3d4e5f",
      deviceId = "0b9c8d7e-6f5a-4b3c-8d2e-1f0a9b8c7d6e", userId = "user_9",
      sessionId = "d4c3b2a1-0f9e-4d8c-b7a6-5e4d3c2b1a09",
      properties = toProps(mapOf("plan_id" to "pro", "seats" to 5, "trial" to false, "coupon" to null)) { _, _ -> },
      personProperties = PersonPropertyOps(set = mapOf("\$email" to JsonValue.Str("ada@example.com")), unset = listOf("legacy")),
      timezone = "Europe/Berlin",
      // Pinned, so a release that moves SDK_VERSION does not move the fixture.
      sdkVersion = "0.1.0",
    )
    val batch = RawBatch(
      clientSentMs = 1_792_584_000_000, deviceIdMode = "install", sessionIdMode = "sdk",
      app = AppInfo("1.4.0", "140"),
      device = DeviceInfo(os = "Android", osVersion = "14", model = "SM-S918B", deviceType = "mobile", manufacturer = "samsung"),
      events = listOf(event),
    )
    val stored = JsonValue.parse(javaClass.getResource("/wire-batch.json")!!.readBytes())
    assertEquals(stored, batch.json())
    assertEquals(event, RawEvent.from(event.json()))
  }

  @Test fun theSessionIdThisSdkMintsMatchesTheEdgesPattern() {
    assertTrue(Regex(Wire.sessionIdPattern).matches(randomId()))
  }
}
