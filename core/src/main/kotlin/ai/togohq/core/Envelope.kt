package ai.togohq.core

// The wire format, hand-written.
//
// This DELIBERATELY duplicates the server's definition of the envelope, as the
// browser and iOS SDKs do, so the library can move to its own repository with
// no refactor: it shares no code with the server, only a wire format. Do not
// "fix" it with an import.
//
// Where the SDK is developed beside the server, a contract test reads THIS FILE
// and fails the build when a constant here and the server's disagree, or when
// the server exports a cap this file never learned. So every number below is a
// plain literal — the test parses them, and `7 * 24 * …` is not a literal.

/**
 * Which SDK sent a batch. **Wire format, permanent once published**: the server
 * maps it to `event_source = "android"`.
 */
internal const val SDK_NAME = "sdk-android"

/** The library's own semver, set by the release. The first published version is 1.0.0. */
internal const val SDK_VERSION = "1.1.0"

/**
 * The `sdk` a batch names: this SDK's own, or a wrapper's built over it.
 *
 * A wrapper is an SDK for another runtime that calls this one underneath — the
 * Solar2D plugin, `sdk-solar2d`. Its events name the wrapper, because that is
 * what a developer installed and what a breakdown by SDK should count; the
 * server reads the platform of a wrapper's batch from its `device`, which this
 * SDK still fills. Set only through `ai.togohq.wrapper.TogoWrapper`, never by an app.
 */
internal data class SdkIdentity(val name: String, val version: String) {
  companion object {
    /** This SDK, as it names itself. */
    val NATIVE: SdkIdentity = SdkIdentity(SDK_NAME, SDK_VERSION)

    /**
     * A wrapper's name and version, or null when they would not be read as one —
     * then the batch keeps naming this SDK. The name is one the server reads a
     * wrapper's platform for (`Wire.wrapperSdkNames`): any other would land as
     * `unknown` and lose its device. The version is non-empty, holds no
     * whitespace, and is at most `Wire.maxSdkVersionLength` UTF-16 code units
     * (`length`), as the edge counts.
     */
    fun wrapper(name: String, version: String): SdkIdentity? =
      if (name in Wire.wrapperSdkNames && version.isNotEmpty() &&
        version.length <= Wire.maxSdkVersionLength && version.none { it.isWhitespace() }
      ) SdkIdentity(name, version) else null
  }
}

internal object Wire {
  const val envelopeVersion = 2
  /** The edge's bound on `sdk.version`, in UTF-16 code units. */
  const val maxSdkVersionLength = 32
  /**
   * The wrapper SDKs the server reads a platform for from the batch's
   * `device.os`; a wrapper not listed here names this SDK instead.
   */
  val wrapperSdkNames: List<String> = listOf("sdk-solar2d")
  const val maxBatchSize = 500
  /**
   * How old an event may be when it is UPLOADED. The edge rejects anything
   * older, so the queue drops it locally and reports the count. A data-loss
   * dial: never tighter than the server's.
   */
  const val maxEventAgeMs = 604_800_000L
  const val maxEnvelopePropertyValueLength = 1024
  const val maxEnvelopePropertiesPerEvent = 256
  /** How many dropped names an `envelope_properties_too_many` report carries. */
  const val maxReportedDroppedProperties = 10
  /**
   * The property names this SDK writes into a map itself — `setPersonProperties`'s `email` and
   * `name` as `$email` and `$name` — which the property cap always keeps, first.
   */
  val platformPropertyNames: List<String> = listOf("\$email", "\$name")
  const val maxBatchBytes = 5_242_880
  const val maxEventBytes = 262_144
  const val maxEnvelopeExperimentsPerEvent = 64
  const val maxEnvelopeExperimentKeyLength = 64
  const val maxTimezoneLength = 64
  const val maxEventNameLength = 255
  const val maxAppDeviceFieldLength = 64
  /**
   * The edge's bound on `userId` (1–128). Not a named constant on the server,
   * so the contract test does not enumerate it; an id outside it fails the
   * WHOLE batch, which is why `identify()` refuses it.
   */
  const val maxUserIdLength = 128

  /** What the edge accepts as a `sessionId`. The SDK only sends its own UUIDs, which match. */
  const val sessionIdPattern = "^[A-Za-z0-9_.:@+%-]{1,128}$"

  const val personPropertiesCarrierEvent = "\$set_person_properties"
  const val identifyEvent = "\$identify"
  const val experimentEnteredEvent = "\$experiment_entered"
  const val sessionStartedEvent = "\$session_started"
  const val appInstalledEvent = "\$app_installed"
  const val appUpdatedEvent = "\$app_updated"
  const val appOpenedEvent = "\$app_opened"

  /**
   * Reserved events: a row like any other. The names are here because the edge
   * accepts them and `track()` must refuse them under both key classes.
   */
  val sentReservedEvents = listOf(
    "\$session_started",
    "\$experiment_entered",
    "\$app_installed",
    "\$app_updated",
    "\$app_opened",
    "\$page_viewed",
    "\$page_left",
  )

  const val reservedEventPrefix = "$"

  /** Every `$` name the server accepts: the two control events and the reserved events. */
  val reservedEventNames = listOf(
    "\$set_person_properties",
    "\$identify",
    "\$session_started",
    "\$experiment_entered",
    "\$app_installed",
    "\$app_updated",
    "\$app_opened",
    "\$page_viewed",
    "\$page_left",
  )

  /** The prefix the edge reads as a test key. */
  const val testWriteKeyPrefix = "togo_pkt_"
}

/** One property value: a string, a number, a boolean or null — the four the envelope admits. */
internal typealias Props = Map<String, JsonValue>

/** Person-property operations, on the PERSON rather than the event. */
internal data class PersonPropertyOps(
  val set: Props? = null,
  val setOnce: Props? = null,
  val unset: List<String>? = null,
) {
  fun json(): JsonValue {
    val o = LinkedHashMap<String, JsonValue>()
    set?.let { o["set"] = JsonValue.Obj(it) }
    setOnce?.let { o["setOnce"] = JsonValue.Obj(it) }
    unset?.let { list -> o["unset"] = JsonValue.Arr(list.map { JsonValue.Str(it) }) }
    return JsonValue.Obj(o)
  }

  companion object {
    fun from(json: JsonValue?): PersonPropertyOps? {
      if (json !is JsonValue.Obj) return null
      return PersonPropertyOps(
        set = primitives(json["set"]),
        setOnce = primitives(json["setOnce"]),
        unset = (json["unset"] as? JsonValue.Arr)?.values?.mapNotNull { (it as? JsonValue.Str)?.value },
      )
    }
  }
}

/** A JSON object back into properties; `null` if it is not one, or holds a nested value. */
internal fun primitives(json: JsonValue?): Props? {
  if (json !is JsonValue.Obj) return null
  if (json.values.values.any { !it.isPrimitive }) return null
  return json.values
}

/** The customer's app build: `PackageInfo`, or the `appVersion`/`appBuild` options. Each 1–64 characters. */
internal data class AppInfo(val version: String? = null, val build: String? = null) {
  fun json(): JsonValue {
    val o = LinkedHashMap<String, JsonValue>()
    version?.let { o["version"] = JsonValue.Str(it) }
    build?.let { o["build"] = JsonValue.Str(it) }
    return JsonValue.Obj(o)
  }

  companion object {
    fun from(json: JsonValue?): AppInfo? {
      if (json !is JsonValue.Obj) return null
      return AppInfo(json.string("version"), json.string("build"))
    }
  }
}

/**
 * The device as a native SDK knows it: `os` (`Android`), `osVersion` (`14`),
 * `model` (`SM-S918B`), `deviceType` (`mobile` or `tablet`, the web's
 * vocabulary) and `manufacturer` (`samsung`), which an Android model id is
 * ambiguous without. Each value wins over what curation parses from the
 * User-Agent.
 */
internal data class DeviceInfo(
  val os: String? = null,
  val osVersion: String? = null,
  val model: String? = null,
  val deviceType: String? = null,
  val manufacturer: String? = null,
) {
  fun json(): JsonValue {
    val o = LinkedHashMap<String, JsonValue>()
    os?.let { o["os"] = JsonValue.Str(it) }
    osVersion?.let { o["osVersion"] = JsonValue.Str(it) }
    model?.let { o["model"] = JsonValue.Str(it) }
    deviceType?.let { o["deviceType"] = JsonValue.Str(it) }
    manufacturer?.let { o["manufacturer"] = JsonValue.Str(it) }
    return JsonValue.Obj(o)
  }

  companion object {
    fun from(json: JsonValue?): DeviceInfo? {
      if (json !is JsonValue.Obj) return null
      return DeviceInfo(
        json.string("os"), json.string("osVersion"), json.string("model"),
        json.string("deviceType"), json.string("manufacturer"),
      )
    }
  }
}

/**
 * One event on the wire. This SDK never sends `page` (a web field) or
 * `eventVersion` (the defined tier has no Kotlin codegen, by design).
 */
internal data class RawEvent(
  val eventName: String,
  /** Device clock when the event OCCURRED, epoch milliseconds; ISO-8601 UTC on the wire. */
  val clientEventMs: Long,
  val eventId: String,
  val anonymousId: String?,
  val deviceId: String?,
  val userId: String?,
  val sessionId: String?,
  val properties: Props,
  val personProperties: PersonPropertyOps?,
  val timezone: String?,
  val sdkName: String = SDK_NAME,
  val sdkVersion: String = SDK_VERSION,
) {
  fun json(): JsonValue {
    val o = LinkedHashMap<String, JsonValue>()
    o["eventName"] = JsonValue.Str(eventName)
    o["clientEventTs"] = JsonValue.Str(IsoTime.format(clientEventMs))
    o["eventId"] = JsonValue.Str(eventId)
    o["properties"] = JsonValue.Obj(properties)
    o["sdk"] = JsonValue.Obj(mapOf("name" to JsonValue.Str(sdkName), "version" to JsonValue.Str(sdkVersion)))
    anonymousId?.let { o["anonymousId"] = JsonValue.Str(it) }
    deviceId?.let { o["deviceId"] = JsonValue.Str(it) }
    userId?.let { o["userId"] = JsonValue.Str(it) }
    sessionId?.let { o["sessionId"] = JsonValue.Str(it) }
    personProperties?.let { o["personProperties"] = it.json() }
    timezone?.let { o["timezone"] = JsonValue.Str(it) }
    return JsonValue.Obj(o)
  }

  companion object {
    fun from(json: JsonValue): RawEvent? {
      if (json !is JsonValue.Obj) return null
      val eventName = json.string("eventName") ?: return null
      val clientEventMs = json.string("clientEventTs")?.let(IsoTime::parse) ?: return null
      val eventId = json.string("eventId") ?: return null
      val properties = primitives(json["properties"]) ?: return null
      val sdk = json["sdk"] as? JsonValue.Obj
      return RawEvent(
        eventName = eventName,
        clientEventMs = clientEventMs,
        eventId = eventId,
        anonymousId = json.string("anonymousId"),
        deviceId = json.string("deviceId"),
        userId = json.string("userId"),
        sessionId = json.string("sessionId"),
        properties = properties,
        personProperties = PersonPropertyOps.from(json["personProperties"]),
        timezone = json.string("timezone"),
        sdkName = sdk?.string("name") ?: SDK_NAME,
        sdkVersion = sdk?.string("version") ?: SDK_VERSION,
      )
    }
  }
}

/**
 * One upload. Consent is deliberately NOT on the wire; `app` and `device` are
 * batch-level because one upload is one build on one device, which is why the
 * queue cuts a batch where either changes.
 */
internal data class RawBatch(
  val clientSentMs: Long,
  val deviceIdMode: String?,
  val sessionIdMode: String?,
  val app: AppInfo?,
  val device: DeviceInfo?,
  val events: List<RawEvent>,
) {
  fun json(): JsonValue {
    val o = LinkedHashMap<String, JsonValue>()
    o["envelopeVersion"] = JsonValue.Int(Wire.envelopeVersion.toLong())
    o["clientSentTs"] = JsonValue.Str(IsoTime.format(clientSentMs))
    o["events"] = JsonValue.Arr(events.map { it.json() })
    deviceIdMode?.let { o["deviceIdMode"] = JsonValue.Str(it) }
    sessionIdMode?.let { o["sessionIdMode"] = JsonValue.Str(it) }
    app?.let { o["app"] = it.json() }
    device?.let { o["device"] = it.json() }
    return JsonValue.Obj(o)
  }
}
