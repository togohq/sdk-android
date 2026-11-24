package ai.togohq.scenarios

import ai.togohq.core.JsonValue
import ai.togohq.core.SDK_NAME

/** What makes a recording comparable to `expected/`. */
internal object Normalize {
  private val idKeys = setOf("eventId", "anonymousId", "deviceId", "sessionId")
  const val SDK_VERSION_PLACEHOLDER = "<sdk.version>"

  /**
   * The ids the SDK mints at random — `eventId`, `anonymousId`, `deviceId`,
   * `sessionId` — replaced by placeholders in order of first appearance, and
   * `sdk.version` by one placeholder. Applied again to an already-normalized
   * recording it renumbers, which is what the projection relies on once it has
   * removed batches.
   */
  fun placeholders(uploads: List<Upload>): List<Upload> {
    val seen = HashMap<String, HashMap<String, String>>()
    fun walk(value: JsonValue): JsonValue = when (value) {
      is JsonValue.Arr -> JsonValue.Arr(value.values.map(::walk))
      is JsonValue.Obj -> {
        val out = LinkedHashMap<String, JsonValue>()
        // Keys in a fixed order — `events` last — so first appearance never
        // depends on a map's iteration order, and a batch's own fields never
        // number before its events.
        for (key in value.values.keys.sortedWith(compareBy<String> { it == "events" }.thenBy { it })) {
          val inner = value.values.getValue(key)
          out[key] = when {
            key in idKeys && inner is JsonValue.Str -> {
              val ids = seen.getOrPut(key) { HashMap() }
              JsonValue.Str(ids.getOrPut(inner.value) { "<$key:${ids.size + 1}>" })
            }
            key == "sdk" && inner is JsonValue.Obj && inner.values.containsKey("version") ->
              JsonValue.Obj(inner.values + ("version" to JsonValue.Str(SDK_VERSION_PLACEHOLDER)))
            else -> walk(inner)
          }
        }
        JsonValue.Obj(out)
      }
      else -> value
    }
    return uploads.map { Upload(it.client, walk(it.body) as JsonValue.Obj) }
  }
}

/**
 * The comparison a device SDK makes against goldens the BROWSER recorded.
 * Everything that says what the app did must match exactly — which events, in
 * what order, at what times, in which sessions, under which ids, with which
 * properties and profile writes, under consent. What differs because a phone is
 * not a page is removed from both sides, and only that:
 *
 * - **`server` batches**, from the expected side: the server SDK sends them.
 * - **`page`**, from the expected events: only the web has a URL.
 * - **`deviceIdMode`**, from both batches: `cookie` on the web, `install` here.
 * - **`app` and `device`**, from the recorded batches: the browser's goldens
 *   carry neither, and this SDK always sends them.
 * - **`sdk.name`**, compared as `sdk-android` rather than `sdk-browser`.
 *
 * Then both sides are renumbered, since removing a batch shifts the
 * placeholders after it.
 */
internal object Projection {
  fun expected(golden: JsonValue.Obj): List<Upload> {
    val batches = (golden["batches"] as? JsonValue.Arr)?.values ?: emptyList()
    val device = batches.mapNotNull { item ->
      val batch = item as? JsonValue.Obj ?: return@mapNotNull null
      val client = batch.string("client") ?: return@mapNotNull null
      if (client.endsWith(":server")) return@mapNotNull null
      val body = LinkedHashMap((batch["body"] as? JsonValue.Obj)?.values ?: return@mapNotNull null)
      body.remove("deviceIdMode")
      body["events"] = JsonValue.Arr(
        ((body["events"] as? JsonValue.Arr)?.values ?: emptyList()).map { event ->
          val o = LinkedHashMap((event as? JsonValue.Obj)?.values ?: return@map event)
          o.remove("page")
          (o["sdk"] as? JsonValue.Obj)?.let { o["sdk"] = JsonValue.Obj(it.values + ("name" to JsonValue.Str(SDK_NAME))) }
          JsonValue.Obj(o)
        },
      )
      Upload(client, JsonValue.Obj(body))
    }
    return Normalize.placeholders(device)
  }

  /**
   * `expected-native/` was recorded by the iOS SDK. What a different platform
   * cannot match is removed from both sides, and only that: `device` (an
   * iPhone is not an Android phone), `deviceIdMode` (`idfv` there, `install`
   * here), and `sdk.name`, compared as `sdk-android`. `app` stays: the build
   * each batch names is part of what the goldens pin.
   */
  fun nativeExpected(golden: JsonValue.Obj): List<Upload> = Normalize.placeholders(
    ((golden["batches"] as? JsonValue.Arr)?.values ?: emptyList()).mapNotNull { item ->
      val batch = item as? JsonValue.Obj ?: return@mapNotNull null
      val body = batch["body"] as? JsonValue.Obj ?: return@mapNotNull null
      val events = ((body["events"] as? JsonValue.Arr)?.values ?: emptyList()).map { event ->
        val o = LinkedHashMap((event as? JsonValue.Obj)?.values ?: return@map event)
        (o["sdk"] as? JsonValue.Obj)?.let { o["sdk"] = JsonValue.Obj(it.values + ("name" to JsonValue.Str(SDK_NAME))) }
        JsonValue.Obj(o)
      }
      Upload(batch.string("client") ?: "", JsonValue.Obj(body.values - setOf("device", "deviceIdMode") + ("events" to JsonValue.Arr(events))))
    },
  )

  fun nativeRecorded(uploads: List<Upload>): List<Upload> = Normalize.placeholders(
    uploads.map { Upload(it.client, JsonValue.Obj(it.body.values - setOf("device", "deviceIdMode"))) },
  )

  fun recorded(uploads: List<Upload>): List<Upload> = Normalize.placeholders(
    uploads.map { Upload(it.client, JsonValue.Obj(it.body.values - setOf("deviceIdMode", "app", "device"))) },
  )

  /** `null` when they agree; otherwise the first difference, as a path. */
  fun difference(expected: List<Upload>, actual: List<Upload>): String? {
    if (expected.map { it.client } != actual.map { it.client }) {
      return "batches: expected ${expected.map { it.client }}, recorded ${actual.map { it.client }}"
    }
    expected.zip(actual).forEachIndexed { index, (e, a) ->
      firstDifference(e.body, a.body, "batches[$index]")?.let { return it }
    }
    return null
  }

  private fun firstDifference(a: JsonValue, b: JsonValue, path: String): String? {
    if (a is JsonValue.Obj && b is JsonValue.Obj) {
      for (key in (a.values.keys + b.values.keys).sorted()) {
        val l = a.values[key] ?: return "$path.$key: not expected, recorded ${b.values.getValue(key).serialized()}"
        val r = b.values[key] ?: return "$path.$key: expected ${l.serialized()}, not recorded"
        firstDifference(l, r, "$path.$key")?.let { return it }
      }
      return null
    }
    if (a is JsonValue.Arr && b is JsonValue.Arr) {
      a.values.zip(b.values).forEachIndexed { i, (l, r) -> firstDifference(l, r, "$path[$i]")?.let { return it } }
      return if (a.values.size == b.values.size) null else "$path: expected ${a.values.size} items, recorded ${b.values.size}"
    }
    return if (a == b) null else "$path: expected ${a.serialized()}, recorded ${b.serialized()}"
  }
}
