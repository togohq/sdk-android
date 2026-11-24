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

/** The public calls' rules, shared with every SDK. */
class SurfaceTest {
  private val testKey = "togo_pkt_test0000000000"

  @Test fun identifySendsOnceAndADifferentUserResetsFirst() {
    val h = Harness()
    h.engine.track("a")
    val anonymous = h.engine.anonymousId!!
    assertNotNull(h.engine.identify("user_1"))
    assertNull("the same user again sends nothing", h.engine.identify("user_1"))
    h.engine.identify("user_2")
    h.flush()

    assertEquals(listOf("\$session_started", "a", "\$identify", "\$session_started", "\$identify"), h.network.names)
    val events = h.network.events
    assertEquals(str("user_1"), events[2]["userId"])
    assertEquals(str(anonymous), events[2]["anonymousId"])
    assertNull("\$identify is a claim, not an event: no device context", events[2]["timezone"])
    assertEquals(str("user_2"), events[4]["userId"])
    assertNotEquals("a different user is a missed logout", str(anonymous), events[4]["anonymousId"])
  }

  /**
   * A logout while consent is pending is still a logout: the grant after it
   * starts a new visitor, and nothing of the last user is written back — in
   * memory either.
   */
  @Test fun aResetWhilePendingIsAFullLogoutOnTheNextGrant() {
    val h = Harness()
    h.engine.identify("user_1")
    h.engine.track("a")
    val anonymous = h.engine.anonymousId!!
    val session = h.engine.sessionId!!
    h.engine.setConsent(TogoConsent.PENDING)
    h.engine.reset()
    assertNull(h.identity.stored?.userId)
    assertNull(h.identity.stored?.anonymousId)
    h.engine.track("b")
    h.engine.setConsent(TogoConsent.GRANTED)
    h.flush()

    val b = h.network.events.last()
    assertEquals(str("b"), b["eventName"])
    assertNull(b["userId"])
    assertNotEquals(str(anonymous), b["anonymousId"])
    assertNotEquals(str(session), b["sessionId"])
    assertNull("the last user is not written back", h.identity.stored?.userId)
    assertNotEquals(anonymous, h.identity.stored?.anonymousId)
  }

  /** An empty or over-long userId would fail every later batch at the edge (1–128), so identify() refuses it. */
  @Test fun identifyRefusesAUserIdTheEdgeWouldRefuse() {
    val h = Harness()
    assertNull(h.engine.identify(""))
    assertNull(h.engine.identify("u".repeat(Wire.maxUserIdLength + 1)))
    assertEquals(listOf(TogoErrorCode.USER_ID_INVALID, TogoErrorCode.USER_ID_INVALID), h.errors.map { it.code })
    h.engine.track("a")
    assertNotNull(h.engine.identify("u".repeat(Wire.maxUserIdLength)))
    h.flush()
    assertNull("the refused ids never rode an event", h.network.events[1]["userId"])
    assertEquals(listOf("\$session_started", "a", "\$identify"), h.network.names)
  }

  @Test fun theUserIdIsRememberedAcrossARelaunch() {
    val h = Harness()
    h.engine.identify("user_1")
    h.start()
    h.engine.track("a")
    h.flush()
    assertEquals(str("user_1"), h.network.events.last()["userId"])
    assertNull(h.engine.identify("user_1"))
  }

  /** `email` and `name` are typed and written as `$email` and `$name`; removing one writes null. */
  @Test fun personPropertiesWriteTheTypedFieldsAsReservedKeys() {
    val h = Harness()
    h.engine.setPersonProperties(email = "ada@example.com", removeName = true, set = mapOf("plan" to "pro"), unset = listOf("legacy"))
    assertNull("an empty update sends nothing", h.engine.setPersonProperties())
    h.flush()
    val carrier = h.network.events.last()
    assertEquals(str("\$set_person_properties"), carrier["eventName"])
    assertEquals(
      obj(
        "set" to obj("\$email" to str("ada@example.com"), "\$name" to JsonValue.Null, "plan" to str("pro")),
        "unset" to JsonValue.Arr(listOf(str("legacy"))),
      ),
      carrier["personProperties"],
    )
    assertEquals(obj(), carrier["properties"])
  }

  /** A live key: a `$` key is dropped with one log per key, and the event is still sent. */
  @Test fun reservedPropertyKeysAreDroppedUnderALiveKey() {
    val h = Harness(defaultProperties = mapOf("\$app" to "x", "source" to "android"))
    h.engine.track("x", mapOf("\$os" to "y", "plan" to "pro"))
    h.engine.track("x", mapOf("\$os" to "y"))
    h.engine.setPersonProperties(set = mapOf("\$email" to "a@b.co", "plan" to "pro"))
    h.flush()
    assertEquals(obj("plan" to str("pro"), "source" to str("android")), h.network.events[1]["properties"])
    assertEquals(obj("set" to obj("plan" to str("pro"))), h.network.events.last()["personProperties"])
    assertEquals(1, h.logs.count { it.contains("\"\$os\" was not sent") })
    assertEquals(
      setOf(TogoErrorCode.PROPERTY_RESERVED, TogoErrorCode.PERSON_PROPERTY_RESERVED),
      h.errors.map { it.code }.toSet(),
    )
  }

  /** …and under a test key the same call SENDS `$os`, so the server's `property_reserved` reaches verify_tracking. */
  @Test fun reservedPropertyKeysAreSentUnderATestKey() {
    val h = Harness(writeKey = testKey)
    h.engine.track("x", mapOf("\$os" to "y", "plan" to "pro"))
    h.flush()
    assertEquals(obj("\$os" to str("y"), "plan" to str("pro")), h.network.events[1]["properties"])
    assertEquals(listOf(TogoErrorCode.PROPERTY_RESERVED), h.errors.map { it.code })
    assertEquals("onError names the property", "\$os", h.errors.first().property)
  }

  /**
   * Conformance 13: a name the registry cannot hold is reported under every key,
   * dropped under a live key, sent under a test key; any other name is kept
   * exactly as sent.
   */
  @Test fun propertyNamesTheRegistryCannotHold() {
    val long = "p".repeat(256)
    val bad = mapOf(long to 1, "tab\there" to 2, "ada@customer.io" to 3, "" to 4, "Plan Type" to "pro")
    val live = Harness()
    live.engine.track("Plan Selected", bad)
    live.engine.track("Plan Selected", bad)
    live.engine.setPersonProperties(set = mapOf(long to 1, "Tier" to "gold"))
    live.flush()
    assertEquals(obj("Plan Type" to str("pro")), live.network.events[1]["properties"])
    assertEquals(obj("set" to obj("Tier" to str("gold"))), live.network.events.last()["personProperties"])
    assertEquals(
      List(4) { TogoErrorCode.PROPERTY_NAME_INVALID } + TogoErrorCode.PERSON_PROPERTY_NAME_INVALID,
      live.errors.map { it.code },
    )
    assertFalse(live.logs.joinToString().contains("ada@customer.io"))
    assertTrue(
      live.logs.any {
        it.contains(
          "\"${"p".repeat(64)}\" was not sent: a property name is 1 to 255 characters, with no control character or email address (property_name_invalid).",
        )
      },
    )

    val test = Harness(writeKey = testKey)
    test.engine.track("Plan Selected", bad)
    test.flush()
    assertEquals(5, (test.network.events[1]["properties"] as JsonValue.Obj).values.size)
    assertEquals(List(4) { TogoErrorCode.PROPERTY_NAME_INVALID }, test.errors.map { it.code })
  }

  @Test fun dollarEventNamesAreRefused() {
    val live = Harness()
    assertNull(live.engine.track("\$pageview"))
    assertEquals(listOf(TogoErrorCode.EVENT_NAME_RESERVED), live.errors.map { it.code })

    val test = Harness(writeKey = testKey)
    assertNotNull("sent for the edge to refuse into the inspector", test.engine.track("\$pageview"))
    // A name the edge accepts is ours, and dropped under BOTH key classes.
    for (name in listOf("\$identify", "\$session_started", "\$app_opened")) assertNull(name, test.engine.track(name))
  }

  @Test fun anEmptyOrOverlongNameIsRefusedPerEvent() {
    val h = Harness()
    assertNull(h.engine.track(""))
    assertNull(h.engine.track("a".repeat(Wire.maxEventNameLength + 1)))
    assertNotNull(h.engine.track("a".repeat(Wire.maxEventNameLength)))
    assertEquals(listOf(TogoErrorCode.EVENT_NAME_EMPTY, TogoErrorCode.EVENT_NAME_TOO_LONG), h.errors.map { it.code })
  }

  /**
   * An over-long value is reported through `onError` once per event and key,
   * cut to the cap under a live key, and the event sent: a long value is
   * usually a real user's, which no test run shows the developer.
   */
  @Test fun anOverLongValueIsReportedAndCutUnderALiveKey() {
    val h = Harness()
    val limit = Wire.maxEnvelopePropertyValueLength
    val long = "x".repeat(limit + 1)
    val eventId = h.engine.track("x", mapOf("note" to long, "plan" to "pro"))
    h.engine.track("x", mapOf("note" to long))
    val writeId = h.engine.setPersonProperties(set = mapOf("bio" to long))
    h.flush()
    assertEquals(str(long.take(limit)), h.network.events[1].obj("properties")["note"])
    assertEquals(str("pro"), h.network.events[1].obj("properties")["plan"])
    assertEquals(str(long.take(limit)), h.network.events.last().obj("personProperties").obj("set")["bio"])
    // Once for the event's key, once for the profile write's: never per call.
    assertEquals(
      listOf(TogoErrorCode.ENVELOPE_PROPERTY_VALUE_TOO_LONG, TogoErrorCode.ENVELOPE_PROPERTY_VALUE_TOO_LONG),
      h.errors.map { it.code },
    )
    // The kept event's id, so the report leads to it in Live View.
    assertEquals(listOf(listOf(eventId), listOf(writeId)), h.errors.map { it.eventIds })
    assertEquals(listOf("note", "bio"), h.errors.map { it.property })
    // The length, never the value.
    assertTrue(h.errors[0].message.contains("is ${limit + 1} characters"))
    assertFalse(h.errors[0].message.contains("x".repeat(50)))
  }

  /** Under a test key the value is sent whole, so the server's cut reaches `verify_tracking`. */
  @Test fun anOverLongValueIsSentWholeUnderATestKey() {
    val h = Harness(writeKey = testKey)
    val long = "x".repeat(Wire.maxEnvelopePropertyValueLength + 1)
    h.engine.track("x", mapOf("note" to long))
    h.flush()
    assertEquals(str(long), h.network.events[1].obj("properties")["note"])
    assertEquals(listOf(TogoErrorCode.ENVELOPE_PROPERTY_VALUE_TOO_LONG), h.errors.map { it.code })
  }

  /** A cut on an event consent denied is not counted as reported, so the first one sent after a grant is (code-review on PR #231). */
  @Test fun aCutOnADeniedEventDoesNotUseUpItsReport() {
    val h = Harness(consent = TogoConsent.DENIED)
    val long = "x".repeat(Wire.maxEnvelopePropertyValueLength + 1)
    assertNull(h.engine.track("x", mapOf("note" to long)))
    h.engine.setConsent(TogoConsent.GRANTED)
    val id = h.engine.track("x", mapOf("note" to long))
    h.flush()
    assertEquals(listOf(TogoErrorCode.ENVELOPE_PROPERTY_VALUE_TOO_LONG), h.errors.map { it.code })
    assertEquals(listOf(id), h.errors.first().eventIds)
  }

  /** `n` properties named `p000`, `p001`, … so sort order is numeric order. */
  private fun wide(n: Int): Map<String, Any?> = (0 until n).associate { "p" + it.toString().padStart(3, '0') to it }

  /**
   * Past 256 properties the first 256 in sort order are kept under a live key and the event is sent;
   * reported once per event name, with the kept event's id and the first ten dropped names.
   */
  @Test fun propertiesPast256AreDroppedAndReportedUnderALiveKey() {
    val h = Harness()
    val eventId = h.engine.track("x", wide(300))
    h.engine.track("x", wide(300))
    h.flush()
    assertEquals(wide(256).keys, h.network.events[1].obj("properties").values.keys)
    assertEquals(listOf(TogoErrorCode.ENVELOPE_PROPERTIES_TOO_MANY), h.errors.map { it.code })
    assertEquals(listOf(eventId), h.errors.first().eventIds)
    assertNull("about many properties, so no one property", h.errors.first().property)
    val message = h.errors.first().message
    assertTrue(message, message.contains("\"x\" has 300 properties: kept the first 256 in sort order and dropped p256, p257"))
    assertTrue(message, message.contains("p265 and 34 more (envelope_properties_too_many)."))
  }

  /** Under a test key all are sent, so the edge drops the same keys where `verify_tracking` shows it. */
  @Test fun propertiesPast256AreSentWholeUnderATestKey() {
    val h = Harness(writeKey = testKey)
    h.engine.track("x", wide(300))
    h.flush()
    assertEquals(300, h.network.events[1].obj("properties").values.size)
    assertEquals(listOf(TogoErrorCode.ENVELOPE_PROPERTIES_TOO_MANY), h.errors.map { it.code })
  }

  /** Counted before the name checks, as the edge counts: a `$` key sorts first, takes a slot, and is then dropped. */
  @Test fun thePropertyCountIsTakenBeforeTheNameChecks() {
    val h = Harness()
    h.engine.track("x", wide(256) + ("\$a" to 1))
    h.flush()
    val sent = h.network.events[1].obj("properties").values
    assertEquals(255, sent.size)
    assertNull(sent["p255"])
    assertEquals(
      setOf(TogoErrorCode.ENVELOPE_PROPERTIES_TOO_MANY, TogoErrorCode.PROPERTY_RESERVED),
      h.errors.map { it.code }.toSet(),
    )
  }

  /** Each profile map is cut on its own; `set` is counted with the `$email` the SDK adds. */
  @Test fun aProfileWriteCutsEachMapCountingTheTypedFields() {
    val h = Harness()
    val names = (0 until 300).map { "p" + it.toString().padStart(3, '0') }
    val writeId = h.engine.setPersonProperties(email = "a@b.co", set = wide(256), unset = names)
    h.flush()
    val ops = h.network.events.last().obj("personProperties")
    val set = ops.obj("set").values
    assertEquals(256, set.size)
    assertEquals(str("a@b.co"), set["\$email"])
    assertNull(set["p255"])
    assertEquals(names.take(256), (ops["unset"] as JsonValue.Arr).values.map { (it as JsonValue.Str).value })
    assertEquals(
      listOf(TogoErrorCode.ENVELOPE_PROPERTIES_TOO_MANY, TogoErrorCode.ENVELOPE_PROPERTIES_TOO_MANY),
      h.errors.map { it.code },
    )
    assertEquals(listOf(listOf(writeId), listOf(writeId)), h.errors.map { it.eventIds })
  }

  /** The email and name a profile write sets are kept, however many names sort before them. */
  @Test fun theTypedFieldsSurviveThePropertyCap() {
    val h = Harness()
    val set = (0 until 300).associate { "#" + it.toString().padStart(4, '0') to it }
    h.engine.setPersonProperties(email = "a@b.co", name = "Ada", set = set)
    h.flush()
    val kept = h.network.events.last().obj("personProperties").obj("set").values
    assertEquals(256, kept.size)
    assertEquals(str("a@b.co"), kept["\$email"])
    assertEquals(str("Ada"), kept["\$name"])
    assertNull(kept["#0254"])
  }

  /**
   * A long value on a key the cap drops is reported as dropped, never also as cut, under either key;
   * a long value on a kept key is still cut.
   */
  @Test fun aLongValueOnADroppedKeyIsNotAlsoReportedAsCut() {
    for (key in listOf(testKey, "togo_pkl_live0000000000")) {
      val h = Harness(writeKey = key)
      val long = "x".repeat(Wire.maxEnvelopePropertyValueLength + 1)
      h.engine.track("x", wide(256) + mapOf("a_url" to long, "zzz_url" to long))
      h.flush()
      assertEquals(
        key,
        setOf("envelope_properties_too_many|", "envelope_property_value_too_long|a_url"),
        h.errors.map { "${it.code.value}|${it.property ?: ""}" }.toSet(),
      )
    }
  }

  /** A drop on an event consent denied is not counted as reported. */
  @Test fun aDropOnADeniedEventDoesNotUseUpItsReport() {
    val h = Harness(consent = TogoConsent.DENIED)
    assertNull(h.engine.track("x", wide(257)))
    h.engine.setConsent(TogoConsent.GRANTED)
    val id = h.engine.track("x", wide(257))
    h.flush()
    assertEquals(listOf(TogoErrorCode.ENVELOPE_PROPERTIES_TOO_MANY), h.errors.map { it.code })
    assertEquals(listOf(id), h.errors.first().eventIds)
  }

  /** The shared key sets every SDK and the edge are held to: the same keys kept, by UTF-16 code unit. */
  @Test fun theCapKeepsTheSameKeysAsEverySdkOnTheSharedKeySets() {
    // `scenarios/` inside the library where it is its own repository, else beside it.
    val library = java.io.File(System.getProperty("togo.library") ?: java.io.File("..").absolutePath).absoluteFile
    val own = java.io.File(library, "scenarios/fixtures/property-cap.json")
    val file = if (own.exists()) own else java.io.File(library.parentFile, "scenarios/fixtures/property-cap.json")
    val fixture = JsonValue.parse(file.readBytes()) as JsonValue.Obj
    assertEquals(JsonValue.Int(Wire.maxEnvelopePropertiesPerEvent.toLong()), fixture["max"])
    // The names the cap keeps first are the ones every SDK and the edge keep first.
    assertEquals(Wire.platformPropertyNames, (fixture["platform"] as JsonValue.Arr).values.map { (it as JsonValue.Str).value })
    val cases = (fixture["cases"] as JsonValue.Arr).values.map { it as JsonValue.Obj }
    assertTrue(cases.isNotEmpty())
    for (case in cases) {
      val name = case.string("name")!!
      val pad = case.obj("pad")
      val prefix = pad.string("prefix")!!
      val count = (pad["count"] as JsonValue.Int).value.toInt()
      val width = (pad["width"] as JsonValue.Int).value.toInt()
      val keys = (0 until count).map { prefix + it.toString().padStart(width, '0') } +
        (case["keys"] as JsonValue.Arr).values.map { (it as JsonValue.Str).value }
      val dropped = Limits.keep(keys)?.second ?: emptyList()
      assertEquals(name, (case["droppedCount"] as JsonValue.Int).value.toInt(), dropped.size)
      assertEquals(name, (case["droppedFirst"] as JsonValue.Arr).values.map { (it as JsonValue.Str).value }, dropped.take(10))
    }
  }

  /** The cut counts UTF-16 code units, as the server does, and never splits a surrogate pair. */
  @Test fun truncationCountsCodeUnitsAndKeepsSurrogatePairsWhole() {
    val limit = Wire.maxEnvelopePropertyValueLength
    assertEquals("a".repeat(limit - 1), Limits.truncate("a".repeat(limit - 1) + "\uD83D\uDE00tail"))
    assertEquals("a".repeat(limit - 2) + "\uD83D\uDE00", Limits.truncate("a".repeat(limit - 2) + "\uD83D\uDE00tail"))
    assertEquals("a".repeat(limit), Limits.truncate("a".repeat(limit)))
  }

  /** A nested value is refused per key, the rest of the event is sent, and onError names the key. */
  @Test fun aNestedPropertyValueIsLeftOutAndReported() {
    val h = Harness()
    h.engine.track("x", mapOf("cart" to mapOf("sku" to "a"), "items" to 3))
    h.flush()
    assertEquals(obj("items" to JsonValue.Int(3)), h.network.events[1]["properties"])
    assertEquals(listOf(TogoErrorCode.PROPERTY_VALUE_INVALID), h.errors.map { it.code })
    assertTrue(h.errors[0].message.contains("\"cart\""))
  }

  /** A Java HashMap with a null key: the key is refused, the event is sent, and nothing throws. */
  @Test fun aNullPropertyKeyFromJavaIsRefusedNotAThrow() {
    val h = Harness()
    val properties = java.util.HashMap<String?, Any?>()
    properties[null] = "x"
    properties["plan"] = "pro"
    @Suppress("UNCHECKED_CAST")
    h.engine.track("x", properties as Map<String, Any?>)
    h.engine.setPersonProperties(set = properties)
    h.flush()
    assertEquals(obj("plan" to str("pro")), h.network.events[1]["properties"])
    assertEquals(listOf(TogoErrorCode.PROPERTY_VALUE_INVALID, TogoErrorCode.PROPERTY_VALUE_INVALID), h.errors.map { it.code })
    assertTrue(h.errors[0].message.contains("null key"))
  }

  /** Defaults on every track() event, never on `$identify` or the carrier; the event's value wins, logged once. */
  @Test fun defaultPropertiesAreOnTrackEventsOnly() {
    val h = Harness(defaultProperties = mapOf("source" to "android_app"))
    h.engine.track("a", mapOf("source" to "onboarding"))
    h.engine.track("a", mapOf("source" to "onboarding"))
    h.engine.identify("user_1")
    h.engine.setPersonProperties(set = mapOf("plan" to "pro"))
    h.engine.track("b")
    h.flush()
    val props = h.network.events.map { it["properties"] }
    assertEquals(obj("source" to str("onboarding")), props[1])
    assertEquals("\$identify", obj(), props[3])
    assertEquals("\$set_person_properties", obj(), props[4])
    assertEquals(obj("source" to str("android_app")), props[5])
    assertEquals(1, h.logs.count { it.contains("overrides default property") })
  }

  /** The getters never mint. */
  @Test fun gettersNeverMint() {
    val h = Harness()
    assertNull(h.engine.anonymousId)
    assertNull(h.engine.deviceId)
    assertNull(h.engine.sessionId)
    assertNull(h.identity.stored)
    h.engine.track("a")
    assertNotNull(h.engine.anonymousId)
    assertEquals(h.identity.stored?.deviceId, h.engine.deviceId)
    assertEquals("install", h.identity.stored?.deviceIdMode)
  }

  /** An app or device string the edge would drop is never sent. */
  @Test fun anInvalidAppVersionIsLeftOutAndLogged() {
    val h = Harness(app = AppInfo("", "9".repeat(65)), device = DeviceInfo(manufacturer = "x".repeat(65)))
    h.engine.track("a")
    h.flush()
    assertNull(h.network.sent[0]["app"])
    assertNull(h.network.sent[0]["device"])
    assertEquals(2, h.logs.count { it.contains("must be a string of 1–64") })
  }

  /**
   * While the identity store cannot be read (a locked device), nothing is
   * minted and events are held in memory; once it can, they are sent under the
   * STORED ids.
   */
  @Test fun anUnreadableIdentityStoreHoldsEventsAndMintsNothing() {
    val identity = MemoryIdentityStore(IdentityRecord(deviceId = "dev-1", deviceIdMode = "install", anonymousId = "anon-1"))
    identity.locked = true
    val h = Harness(identity = identity)
    h.engine.track("push_received")
    h.flush()
    assertTrue(h.network.sent.isEmpty())
    assertEquals(1, h.engine.heldCount)
    assertEquals("no id minted over an unreadable record", "anon-1", identity.stored?.anonymousId)

    identity.locked = false
    h.engine.identityBecameAvailable()
    h.flush()
    assertEquals(listOf("\$session_started", "push_received"), h.network.names)
    assertEquals(str("anon-1"), h.network.events[1]["anonymousId"])
    assertEquals(str("dev-1"), h.network.events[1]["deviceId"])
  }

  /** identify() on a locked device must not save a blank record over the stored one. */
  @Test fun identifyWhileTheStoreIsUnreadableKeepsTheStoredIds() {
    val identity = MemoryIdentityStore(IdentityRecord(deviceId = "dev-1", deviceIdMode = "install", anonymousId = "anon-1"))
    identity.locked = true
    val h = Harness(identity = identity)
    h.engine.identify("user_1")
    h.engine.reset()
    identity.locked = false
    assertEquals("the stored ids survive", "dev-1", identity.stored?.deviceId)

    h.engine.identify("user_1")
    h.engine.identityBecameAvailable()
    h.flush()
    val event = h.network.events.last()
    assertEquals(str("\$identify"), event["eventName"])
    assertEquals(str("dev-1"), event["deviceId"])
    assertEquals(str("user_1"), event["userId"])
    assertEquals("user_1", identity.stored?.userId)
  }

  /**
   * A logout on a locked device is a logout: at the unlock the stored user,
   * its anonymous id and its session are gone, and the next person's events
   * never go out under them.
   */
  @Test fun aResetWhileTheStoreIsUnreadableStaysALogoutAfterTheUnlock() {
    val identity = MemoryIdentityStore(
      IdentityRecord(deviceId = "dev-1", deviceIdMode = "install", anonymousId = "anon-A", userId = "A",
        session = SessionRecord("s-A", 1_792_584_000_000, 1_792_584_000_000)),
    )
    identity.locked = true
    val h = Harness(identity = identity)
    h.engine.reset()
    h.engine.track("next_person")
    identity.locked = false
    h.engine.identityBecameAvailable()
    h.flush()

    val event = h.network.events.last()
    assertEquals(str("next_person"), event["eventName"])
    assertNull("the logged-out user is not restored", event["userId"])
    assertNotEquals(str("anon-A"), event["anonymousId"])
    assertNotEquals(str("s-A"), event["sessionId"])
    assertEquals("the device stays", str("dev-1"), event["deviceId"])
    assertNull(identity.stored?.userId)
    assertNotEquals("anon-A", identity.stored?.anonymousId)
  }

  /** identify() of a different user on a locked device is a missed logout at the unlock, never stitched onto the stored one. */
  @Test fun identifyingADifferentUserWhileUnreadableResetsAtTheUnlock() {
    val identity = MemoryIdentityStore(
      IdentityRecord(deviceId = "dev-1", deviceIdMode = "install", anonymousId = "anon-A", userId = "A"),
    )
    identity.locked = true
    val h = Harness(identity = identity)
    h.engine.identify("B")
    identity.locked = false
    h.engine.identityBecameAvailable()
    h.flush()
    val identifyEvent = h.network.events.last()
    assertEquals(str("\$identify"), identifyEvent["eventName"])
    assertEquals(str("B"), identifyEvent["userId"])
    assertNotEquals("B is not stitched onto A's anonymous id", str("anon-A"), identifyEvent["anonymousId"])
    assertEquals("B", identity.stored?.userId)
  }

  /** The positive half: the same user identified while locked keeps the stored ids. */
  @Test fun identifyingTheStoredUserWhileUnreadableKeepsItsIds() {
    val identity = MemoryIdentityStore(
      IdentityRecord(deviceId = "dev-1", deviceIdMode = "install", anonymousId = "anon-A", userId = "A"),
    )
    identity.locked = true
    val h = Harness(identity = identity)
    h.engine.identify("A")
    identity.locked = false
    h.engine.identityBecameAvailable()
    h.engine.track("a")
    h.flush()
    assertEquals(str("anon-A"), h.network.events.last()["anonymousId"])
    assertEquals(str("A"), h.network.events.last()["userId"])
  }

  /** A device-id provider that has no id yet holds the event rather than inventing one. */
  @Test fun aDeviceIdNotAvailableYetHoldsTheEvent() {
    var ready = false
    val h = Harness(deviceIds = DeviceIdProvider { if (ready) DeviceIdentity("dev-9", "install") else null })
    h.engine.track("a")
    assertEquals(1, h.engine.heldCount)
    ready = true
    h.engine.identityBecameAvailable()
    h.flush()
    assertEquals(str("dev-9"), h.network.events[1]["deviceId"])
  }

  @Test fun aWriteKeyThatIsNotOneIsNamed() {
    assertNull(WriteKeys.problem("togo_pkl_abc"))
    assertNull(WriteKeys.problem("togo_pkt_abc"))
    assertTrue(WriteKeys.problem("togo_sk_abc")!!.contains("SECRET"))
    assertTrue(WriteKeys.problem("\${TOGO_KEY}")!!.contains("not a write key"))
  }

  /** A disabled client sends nothing, and says so once. */
  @Test fun aDisabledClientIsInert() {
    val h = Harness()
    h.engine.disable("off", TogoErrorCode.WRITE_KEY_INVALID)
    assertNull(h.engine.track("a"))
    assertNull(h.engine.identify("user_1"))
    assertNull(h.engine.setPersonProperties(set = mapOf("a" to 1)))
    h.flush()
    assertTrue(h.network.sent.isEmpty())
    assertEquals(listOf(TogoErrorCode.WRITE_KEY_INVALID), h.errors.map { it.code })
  }
}
