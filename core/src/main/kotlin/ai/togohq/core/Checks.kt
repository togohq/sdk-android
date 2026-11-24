package ai.togohq.core

import ai.togohq.TogoErrorCode

// The checks every call passes through before anything is queued: the write
// key's class, a `$` name or key, the structural caps, and the default
// properties. Each mirrors the browser and iOS SDKs' rules, because what a
// developer is told must not depend on the platform.

internal object WriteKeys {
  private val writePrefixes = listOf("togo_pkl_", "togo_pkt_", "pk_test_", "pk_live_")
  private val secretPrefixes = listOf("togo_sk", "sk_")

  fun isTest(key: String): Boolean = key.startsWith(Wire.testWriteKeyPrefix)

  /**
   * Why this string cannot be a write key, or `null` if it can be. Checked
   * locally at `setup()`, so a missing build setting or a secret key pasted
   * into an app is named at once rather than as a 401.
   */
  fun problem(key: String): String? {
    if (writePrefixes.any { key.startsWith(it) }) return null
    if (secretPrefixes.any { key.startsWith(it) }) {
      return "writeKey is a SECRET key, not a write key. Never ship it in an app — rotate it if " +
        "this build has shipped — and use the project's write key (togo_pkt_… or togo_pkl_…). " +
        "No events will be captured."
    }
    return "writeKey is not a write key (togo_pkt_… or togo_pkl_…). Check for an unsubstituted " +
      "build setting. No events will be captured."
  }
}

internal class ReservedName(
  /** Whether `track()` sends the event anyway, for the edge to refuse. */
  val send: Boolean,
  val message: String,
)

internal object Reserved {
  /**
   * `$` names are ours. Under a test key a name the edge does not accept is
   * SENT, so its refusal reaches `verify_tracking`; under a live key it is
   * dropped here. A name the edge does accept is dropped under both: it would
   * be read as our own signal.
   */
  fun checkName(name: String, writeKey: String): ReservedName? {
    if (!name.startsWith(Wire.reservedEventPrefix)) return null
    val send = name !in Wire.reservedEventNames && WriteKeys.isTest(writeKey)
    val verb = if (send) "is sent for verify_tracking to report, and refused" else "was not sent"
    return ReservedName(
      send,
      "\"$name\" $verb: \"\$\" names are reserved for Togo (event_name_reserved). If another tool " +
        "forwards its events to Togo, stop it; otherwise rename the event without the \"\$\".",
    )
  }
}

/**
 * The name rule the registry holds a customer's property name to — the
 * server's, copied rather than imported and held equal to it by the monorepo's
 * name-rules contract test: 1 to 255 UTF-16 code units, no control character,
 * no email address.
 */
internal object Names {
  const val MAX_LENGTH: Int = 255

  /** How much of a refused name a message shows: enough to find it in code. */
  const val SHOWN_LENGTH: Int = 64
  const val EMAIL_PATTERN: String = """[^\s@<>()\[\]",;:]+@[^\s@<>()\[\]",;:]+\.[a-z]{2,}"""
  private val email = Regex(EMAIL_PATTERN, RegexOption.IGNORE_CASE)

  fun isControl(c: Char): Boolean = c.code <= 0x1F || c.code in 0x7F..0x9F

  /** Whether the registry can hold this name. `String.length` counts UTF-16 code units. */
  fun isHoldable(name: String): Boolean =
    name.isNotEmpty() && name.length <= MAX_LENGTH && name.none(::isControl) && !email.containsMatchIn(name)

  /** The name as a developer can find it in their code: cut to [SHOWN_LENGTH] code units, never an address in it. */
  fun shown(name: String): String = email.replace(name.take(SHOWN_LENGTH), "<email>")
}

/**
 * A customer's property names the platform cannot use: a `$` name
 * (`property_reserved`) or one the registry cannot hold
 * (`property_name_invalid`), and their person-property twins. **Reported under
 * every key class**, once per name for the life of the client; **dropped under
 * a live key**, **sent unchanged under a test key** so the server's diagnostic
 * reaches `verify_tracking`.
 */
internal class ReservedKeys(private val writeKey: String) {
  private val logged = HashSet<String>()

  fun check(properties: Props?, person: Boolean = false, report: (TogoErrorCode, String, String) -> Unit): Props? {
    if (properties == null) return null
    return properties.filterKeys { keep(it, person, report) }
  }

  fun checkKeys(keys: List<String>?, person: Boolean, report: (TogoErrorCode, String, String) -> Unit): List<String>? {
    if (keys == null) return null
    return keys.filter { keep(it, person, report) }
  }

  private fun keep(key: String, person: Boolean, report: (TogoErrorCode, String, String) -> Unit): Boolean {
    val reserved = key.startsWith(Wire.reservedEventPrefix)
    if (!reserved && Names.isHoldable(key)) return true
    val code = when {
      reserved && person -> TogoErrorCode.PERSON_PROPERTY_RESERVED
      reserved -> TogoErrorCode.PROPERTY_RESERVED
      person -> TogoErrorCode.PERSON_PROPERTY_NAME_INVALID
      else -> TogoErrorCode.PROPERTY_NAME_INVALID
    }
    val test = WriteKeys.isTest(writeKey)
    if (logged.add(code.value + key)) {
      val why = if (reserved) {
        "names starting with \"\$\" are reserved for Togo"
      } else {
        "a property name is 1 to ${Names.MAX_LENGTH} characters, with no control character or email address"
      }
      val verb = if (test) "is sent for verify_tracking to report" else "was not sent"
      report(code, "\"${Names.shown(key)}\" $verb: $why ($code).", Names.shown(key))
    }
    return test
  }

  /**
   * The wire's `personProperties`: names checked, then the typed fields
   * written as their `$` names, after the check so the SDK's own are never
   * dropped. `null` when nothing is left to send.
   */
  fun personOps(update: PersonPropertyUpdate, report: (TogoErrorCode, String, String) -> Unit): PersonPropertyOps? {
    var set = check(update.set, person = true, report = report)
    for ((field, value) in listOf("email" to update.email, "name" to update.name)) {
      if (value == null) continue
      set = (set ?: emptyMap()) + (Wire.reservedEventPrefix + field to value)
    }
    val ops = PersonPropertyOps(
      set = set,
      setOnce = check(update.setOnce, person = true, report = report),
      unset = checkKeys(update.unset, person = true, report = report),
    )
    val nonEmpty = !ops.set.isNullOrEmpty() || !ops.setOnce.isNullOrEmpty() || !ops.unset.isNullOrEmpty()
    return if (nonEmpty) ops else null
  }
}

/**
 * What `setPersonProperties()` takes: `email` and `name` typed — written as the
 * reserved `$email` and `$name` — beside `set` (overwrite), `setOnce` (keep the
 * earliest) and `unset` for the project's own keys. For `email` and `name`,
 * `null` leaves the field alone and [JsonValue.Null] removes it.
 */
internal data class PersonPropertyUpdate(
  val email: JsonValue? = null,
  val name: JsonValue? = null,
  val set: Props? = null,
  val setOnce: Props? = null,
  val unset: List<String>? = null,
)

internal object Limits {
  /**
   * The keys that survive the property cap and the ones dropped, or `null` within it — the
   * server's rule and every SDK's, in two tiers. **First every name in
   * `Wire.platformPropertyNames`**, so a profile write never loses the email or name this SDK wrote
   * for it, whatever sorts before them; **then the rest in ascending UTF-16 code-unit order** —
   * Kotlin's `String.compareTo` — until 256 are kept. Never a map's iteration order, which a
   * `HashMap` does not keep, so the same call would lose different keys from one run to the next.
   */
  fun keep(keys: List<String>): Pair<List<String>, List<String>>? {
    val max = Wire.maxEnvelopePropertiesPerEvent
    if (keys.size <= max) return null
    // Distinct names, in first-seen order: an `unset` list may repeat one, and a repeat is not a key.
    val unique = keys.distinct()
    if (unique.size <= max) return null
    val platform = unique.filter { it in Wire.platformPropertyNames }
    val rest = unique.filter { it !in Wire.platformPropertyNames }.sorted()
    val room = max - platform.size
    return (platform + rest.take(room)) to rest.drop(room)
  }

  /**
   * [value] cut to `maxEnvelopePropertyValueLength` UTF-16 code units — a Kotlin
   * `String.length`, the server's count — never through a surrogate pair, so the
   * result is always well-formed text. No ellipsis: that would store a value
   * nobody sent.
   */
  fun truncate(value: String): String {
    var end = Wire.maxEnvelopePropertyValueLength
    if (value.length <= end) return value
    if (Character.isHighSurrogate(value[end - 1])) end -= 1
    return value.substring(0, end)
  }
}

/**
 * A string value over `maxEnvelopePropertyValueLength` is **truncated, never
 * refused** — the edge cuts it to the same length and keeps the event.
 *
 * Why, because it is not the obvious answer: the value that crosses the cap is
 * nearly always one the developer does not control and will not see in testing
 * — a real user's URL with a long tracking query string, or text they typed. No
 * test run can rule it out, so what matters is what it costs in production with
 * nobody watching. Refusing the event lost the whole event; cutting the value
 * loses its tail, in a known way.
 *
 * **Reported through `onError` under every key class**, once per event name and
 * key for the life of the client, with the kept event's id. **Cut here under a live key**, so a long value
 * never fills the queue; **sent whole under a test key**, so the edge's cut
 * reaches `verify_tracking` as `envelope_property_value_too_long`.
 */
internal class ValueCaps(private val writeKey: String) {
  private val logged = HashSet<String>()

  /**
   * Cuts under a live key and hands each cut to [collect] as its once-only key, the property as
   * the message shows it, and the message — the caller reports it once the event has its id and is
   * really being sent, claiming the key then ([claim]). Claimed at report time, never here, so a
   * cut on an event consent denied is never counted as told. The message names the property and
   * the value's length, never the value: `onError` feeds error trackers, and a long value is the
   * likeliest to hold a token or typed text.
   */
  fun cap(name: String, properties: Props?, collect: (String, String, String) -> Unit): Props? {
    if (properties == null) return null
    val test = WriteKeys.isTest(writeKey)
    val limit = Wire.maxEnvelopePropertyValueLength
    var out: LinkedHashMap<String, JsonValue>? = null
    // A map still over the property cap is a test key's, sent whole: the edge drops these keys
    // before it cuts a value, so none of them is reported as cut — one loss, one report, the same
    // under either key. A live key's map is already within the cap.
    val dropped = Limits.keep(properties.keys.toList())?.second?.toHashSet() ?: emptySet()
    for (key in properties.keys.sorted()) {
      if (key in dropped) continue
      val value = properties.getValue(key)
      if (value !is JsonValue.Str || value.value.length <= limit) continue
      val shown = Names.shown(key)
      collect(
        // `=` here and `#` on a drop: a different first character, so a cut's once-only key can
        // never equal a drop's, whatever the names hold.
        "=" + name + "\n" + key,
        shown,
        "\"$name\" property \"$shown\" is ${value.value.length} characters: " +
          (if (test) "sent whole for verify_tracking; Togo keeps" else "cut to") +
          " its first $limit (envelope_property_value_too_long).",
      )
      if (!test) {
        if (out == null) out = LinkedHashMap(properties)
        out[key] = JsonValue.Str(Limits.truncate(value.value))
      }
    }
    return out ?: properties
  }

  /** Whether this cut is still to be reported — true once per event name and key. */
  fun claim(once: String): Boolean = logged.add(once)

  /**
   * A map over 256 properties **keeps its first 256 in sort order and drops the rest; the event is
   * kept** — the edge's rule, applied here under a live key and left to the edge under a test key,
   * so the drop reaches `verify_tracking`. The event that crosses the cap is nearly always a whole
   * object passed as properties, whose width grows with a real user's data.
   *
   * **Counted on the map as it goes on the wire**, before the reserved and unusable-name checks,
   * which the edge does not make: a test key's map must be cut exactly as a live key's. [extra]
   * names the keys the SDK adds afterwards — a profile write's `$email` and `$name`. Collected like
   * a cut, as the once-only key and the message, and reported once per event name and map with the
   * kept event's id. The message names the first ten dropped names, never a value.
   */
  fun capCount(
    name: String,
    properties: Props?,
    map: String = "properties",
    extra: List<String> = emptyList(),
    collect: (String, String) -> Unit,
  ): Props? {
    if (properties == null) return null
    val kept = keptOrNull(name, (properties.keys + extra).toList(), map, collect) ?: return properties
    return properties.filterKeys { it in kept }
  }

  /** The `unset` list, cut the same way. */
  fun capCount(name: String, keys: List<String>?, map: String, collect: (String, String) -> Unit): List<String>? {
    if (keys == null) return null
    val kept = keptOrNull(name, keys, map, collect) ?: return keys
    return keys.filter { it in kept }
  }

  /** The kept set under a live key; `null` within the cap or under a test key. */
  private fun keptOrNull(name: String, keys: List<String>, map: String, collect: (String, String) -> Unit): Set<String>? {
    val (kept, dropped) = Limits.keep(keys) ?: return null
    val test = WriteKeys.isTest(writeKey)
    val names = dropped.take(Wire.maxReportedDroppedProperties).map(Names::shown)
    val more = dropped.size - names.size
    collect(
      "#$name\n$map",
      "\"$name\"" + (if (map == "properties") "" else " $map") + " has ${kept.size + dropped.size} properties: " +
        (if (test) "sent whole for verify_tracking; Togo keeps" else "kept") +
        " the first ${Wire.maxEnvelopePropertiesPerEvent} in sort order and " +
        (if (test) "drops " else "dropped ") + names.joinToString(", ") +
        (if (more > 0) " and $more more" else "") + " (envelope_properties_too_many).",
    )
    return if (test) null else kept.toHashSet()
  }
}

/**
 * Set once at `setup()`, stamped on every `track()` event, never on `$identify`
 * or `$set_person_properties`. On a collision the event's value wins and the SDK
 * says so, once per key.
 */
internal class DefaultProperties(defaults: Props?, private val log: Logger) {
  private val fixed: Props = defaults ?: emptyMap()
  private val reported = HashSet<String>()

  fun merge(name: String, properties: Props): Props {
    if (fixed.isEmpty()) return properties
    val merged = LinkedHashMap(fixed)
    for (key in properties.keys.sorted()) {
      if (fixed.containsKey(key) && reported.add(key)) log.log("\"$name\" overrides default property \"$key\".")
      merged[key] = properties.getValue(key)
    }
    return merged
  }
}

/** The batch's `app`: each value a 1–64 character string, or left out and logged. `null` when neither survives. */
internal fun validatedApp(app: AppInfo?, log: Logger): AppInfo? {
  if (app == null) return null
  fun valid(name: String, value: String?): String? {
    if (value == null) return null
    if (value.isNotEmpty() && value.length <= Wire.maxAppDeviceFieldLength) return value
    log.log("$name must be a string of 1–${Wire.maxAppDeviceFieldLength} characters; not sent")
    return null
  }
  val checked = AppInfo(valid("appVersion", app.version), valid("appBuild", app.build))
  return if (checked.version == null && checked.build == null) null else checked
}

/** The batch's `device`: a value past the edge's bound is left out, which is what the edge would do anyway. */
internal fun validatedDevice(device: DeviceInfo?): DeviceInfo? {
  if (device == null) return null
  fun bounded(value: String?): String? =
    if (value.isNullOrEmpty() || value.length > Wire.maxAppDeviceFieldLength) null else value
  val checked = DeviceInfo(
    bounded(device.os), bounded(device.osVersion), bounded(device.model), bounded(device.deviceType),
    bounded(device.manufacturer),
  )
  return if (checked == DeviceInfo()) null else checked
}
