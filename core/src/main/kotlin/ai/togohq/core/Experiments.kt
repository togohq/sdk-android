package ai.togohq.core

import ai.togohq.TogoExperiment
import ai.togohq.TogoExperimentStatus
import java.io.File
import java.io.IOException
import java.net.URL

/**
 * Where the stored answers live: `experiments.json`, beside the identity file in
 * the no-backup directory. Never `SharedPreferences`: a restored backup would
 * carry one device's assignments onto another.
 */
internal interface ExperimentStore {
  fun read(): ByteArray?
  fun write(data: ByteArray)
  fun remove()
}

internal class MemoryExperimentStore(data: ByteArray? = null) : ExperimentStore {
  @Volatile private var data: ByteArray? = data
  /** When true, [read] finds nothing, as a locked device's file reads. */
  @Volatile var unreadable = false
  override fun read(): ByteArray? = if (unreadable) null else data
  override fun write(data: ByteArray) {
    this.data = data
  }
  override fun remove() {
    data = null
  }
}

internal class FileExperimentStore(private val file: File) : ExperimentStore {
  override fun read(): ByteArray? = try {
    if (file.exists()) file.readBytes() else null
  } catch (_: IOException) {
    null
  }

  override fun write(data: ByteArray) {
    try {
      atomicWrite(file, data)
    } catch (_: IOException) {
      // A full disk is expected operation on a device: the answers stay in
      // memory for this process, and the next answer writes the file again.
    }
  }

  override fun remove() {
    file.delete()
  }
}

/**
 * `getExperiment()` on a device: a port of the browser SDK's `experiments.ts`
 * with the three things a phone needs:
 *
 * - **A stored answer never expires into `unavailable`.** Past its
 *   `maxAgeSeconds` it is still returned, and refreshed in the background. A
 *   phone is offline in normal operation, and the answer for a unit is
 *   deterministic, so the old variant is the better failure than flipping a
 *   returning user onto the default path.
 * - **A synchronous read** ([cached]), so an app can choose its first screen
 *   before any network call returns. It records the exposure like the async
 *   call: the answer an app branched on is the answer counted. Answered before
 *   anyone is in front — a launch before its first screen, or a push — the
 *   exposure and the stamp wait for the next `$app_opened`, and a launch that
 *   never comes to the front records neither.
 * - **A refresh on every `$app_opened`**, and an answer pinned for the
 *   session, so a refresh that lands mid-session changes the next one.
 *
 * Confined to the engine's executor, like the engine.
 */
internal class Experiments(
  private val writeKey: String,
  endpoint: String,
  private val store: ExperimentStore,
  private val transport: Transport,
  private val executor: TogoExecutor,
  private val clock: Clock,
  private val ids: () -> Ids?,
  private val sessionId: () -> String?,
  private val humanPresent: () -> Boolean,
  private val enter: (String, String) -> Unit,
  log: Logger = Logger { },
) {
  data class Ids(val anonymousId: String, val deviceId: String, val userId: String?)

  data class Entry(
    val status: TogoExperimentStatus,
    val variant: String?,
    val participating: Boolean,
    val unit: String?,
    /** Wall-clock milliseconds when it was fetched. */
    val at: Long,
    val ttlSeconds: Long,
  )

  /**
   * What is persisted, keyed by the ids, as the browser's `Doc`: a new
   * anonymous id starts over, a new `userId` drops the answers and keeps the
   * stamps, and the once-only record outlives both.
   */
  class Doc(
    var anonymousId: String? = null,
    var userId: String? = null,
    val entries: MutableMap<String, Entry> = LinkedHashMap(),
    /** Entered and still stamped: name → variant. */
    val entered: MutableMap<String, String> = LinkedHashMap(),
    /** The once-only record, `name:hash of the unit's value`, oldest first. Hashed so a `userId` is not left behind. */
    val once: MutableList<String> = ArrayList(),
  )

  private val url: URL? = try {
    URL(endpoint.trimEnd('/') + "/v1/assignments")
  } catch (_: java.net.MalformedURLException) {
    // Said once, here: otherwise every experiment answers unavailable forever and
    // nothing says why.
    log.log(
      "assignEndpoint is not a URL ($endpoint), so every getExperiment() answers unavailable and " +
        "nobody enters an experiment. Set it to https://… or leave it out for the default.",
    )
    null
  }
  private val test = WriteKeys.isTest(writeKey)

  /** The working document: read from the store once, then kept in step with it. */
  private var doc: Doc? = null
  /** A test key caches nothing, so a forced variant is seen at once; its stamps last the process. */
  private var memory = Doc()
  private val seenThisProcess = HashSet<String>()
  /** The answers this session returned, so a refresh never flips a variant inside one session. */
  private val pinned = HashMap<String, Entry>()
  private var pinnedFor: Pair<String?, Ids>? = null
  /** Callbacks per name waiting on a request, which is one per name in flight. */
  private val waiting = HashMap<String, MutableList<(Entry?) -> Unit>>()
  /** Names asked in this turn of the executor, sent as one request. */
  private val batch = ArrayList<String>()
  /**
   * Exposures answered while nobody was in front — a launch before its first
   * screen, or a push — recorded at the next `$app_opened`, under the ids they
   * were answered for. A launch that never comes to the front records none.
   */
  private data class Pending(val name: String, val variant: String, val unit: String?, val ids: Ids)
  private val pending = ArrayList<Pending>()

  // The surface.

  /**
   * The answer, from the stored one when there is one — refreshed in the
   * background when it is old — or from the network, within 800 ms.
   * `completion` runs on the executor.
   */
  fun get(name: String, completion: (TogoExperiment) -> Unit) {
    if (!valid(name)) return completion(TogoExperiment(name, TogoExperimentStatus.NOT_FOUND, null, false))
    // Locked (no unit) or consent withholding. With nobody in front yet — a
    // launch choosing its first screen — it still answers; the exposure waits for
    // the foreground (`answer`).
    val ids = ids() ?: return completion(TogoExperiment.unavailable(name))
    if (!test) {
      val entry = current(name, ids)
      if (entry != null) {
        if (stale(entry)) refresh(listOf(name))
        return completion(answer(name, entry, ids))
      }
    }
    // Whichever comes first answers: the timer or the network. Both run on the executor.
    var done = false
    val cancel = executor.after(TIMEOUT_SECONDS) {
      if (!done) {
        done = true
        completion(TogoExperiment.unavailable(name))
      }
    }
    ask(name) { entry ->
      if (!done) {
        done = true
        cancel()
        val now = ids()
        completion(if (entry != null && now != null) answer(name, entry, now) else TogoExperiment.unavailable(name))
      }
    }
  }

  /** The stored answer, now, never from the network: `null` when there is none. Records the exposure. */
  fun cached(name: String): TogoExperiment? {
    if (!valid(name)) return null
    val ids = ids() ?: return null
    val entry = current(name, ids) ?: return null
    return answer(name, entry, ids)
  }

  /** Fetch without recording an exposure: what has no answer, or an old one. */
  fun preload(names: List<String>) {
    // Nothing to fetch mints nothing: `ids()` is the first permitted write.
    if (names.none(::valid) || !humanPresent()) return
    val ids = ids() ?: return
    val doc = load(ids)
    refresh(names.filter { valid(it) && (test || doc.entries[it]?.let(::stale) ?: true) })
  }

  /**
   * `$app_opened`: refresh everything this device holds an answer or a stamp
   * for, and the names the app preloads. A finished experiment comes back
   * `finished`, which is what ends its stamp.
   */
  fun appOpened(preload: List<String>) {
    val ids = ids() ?: return
    // What was answered before anyone was in front is an exposure now, unless
    // the ids changed meanwhile (a reset or another user).
    val due = ArrayList(pending)
    pending.clear()
    for (exposure in due) if (exposure.ids == ids) record(exposure.name, exposure.variant, exposure.unit, ids)
    val doc = load(ids)
    val all = LinkedHashSet<String>()
    all.addAll(doc.entries.keys)
    all.addAll(doc.entered.keys)
    preload.filterTo(all, ::valid)
    refresh(all.toList())
  }

  /** The stamp for an ordinary event tracked now, or `null` for none. A `$` event carries none. */
  fun stampFor(eventName: String): Map<String, String>? {
    if (eventName.startsWith(Wire.reservedEventPrefix)) return null
    val ids = ids() ?: return null
    val entered = load(ids).entered
    if (entered.isEmpty()) return null
    return entered.entries.take(Wire.maxEnvelopeExperimentsPerEvent).associate { it.key to it.value }
  }

  /** Consent withdrawal: everything goes, the file included. */
  fun clear() {
    memory = Doc()
    pending.clear()
    seenThisProcess.clear()
    pinned.clear()
    pinnedFor = null
    doc = null
    store.remove()
  }

  /** `reset()`: answers and stamps go, the record of who has entered stays. */
  fun forgetIdentity() {
    memory = Doc()
    pending.clear()
    seenThisProcess.clear()
    pinned.clear()
    pinnedFor = null
    if (test) return
    // Unreadable — a locked device in Direct Boot — or absent: leave the file.
    // Removing what could not be read would lose the once-only record and count
    // the same unit twice; the next load under the new anonymous id drops its
    // answers and stamps anyway.
    val current = doc ?: read()
    if (current == null) {
      doc = null
      return
    }
    val once = current.once
    if (once.isEmpty()) {
      doc = null
      store.remove()
    } else {
      val kept = Doc(once = ArrayList(once))
      doc = kept
      save(kept)
    }
  }

  // Answers.

  private fun valid(name: String) = name.isNotEmpty() && name.length <= Wire.maxEnvelopeExperimentKeyLength

  /** The answer this session returns for `name`: the pinned one, or the stored one, which is then pinned. */
  private fun current(name: String, ids: Ids): Entry? {
    pin(ids)
    pinned[name]?.let { return it }
    val entry = load(ids).entries[name] ?: return null
    pinned[name] = entry
    return entry
  }

  /** Start a new pin when the session or the ids changed. */
  private fun pin(ids: Ids) {
    val key = sessionId() to ids
    if (pinnedFor != key) {
      pinned.clear()
      pinnedFor = key
    }
  }

  private fun stale(entry: Entry): Boolean {
    val now = clock.wallMs()
    return now < entry.at || now - entry.at >= entry.ttlSeconds * 1000
  }

  private fun answer(name: String, entry: Entry, ids: Ids): TogoExperiment {
    pin(ids)
    pinned[name] = entry
    if (entry.participating && entry.variant != null) {
      if (humanPresent()) {
        record(name, entry.variant, entry.unit, ids)
      } else if (pending.none { it.name == name && it.ids == ids }) {
        pending.add(Pending(name, entry.variant, entry.unit, ids))
      }
    }
    return TogoExperiment(name, entry.status, entry.variant, entry.participating)
  }

  /**
   * The exposure, once per unit per experiment — across launches, since the
   * record is in the file — and the stamp on every later event.
   */
  private fun record(name: String, variant: String, unit: String?, ids: Ids) {
    if (test) {
      memory.entered[name] = variant
      if (!seenThisProcess.add("$name\u0000$variant")) return
      enter(name, variant)
      return
    }
    val doc = load(ids)
    // A refresh that said `finished` ended the stamp: the answer pinned for this
    // session is still returned, but it must not bring the stamp back.
    if (doc.entries[name]?.status == TogoExperimentStatus.FINISHED) return
    doc.entered[name] = variant
    val value = when (unit) {
      "user_id" -> ids.userId
      "device_id" -> ids.deviceId
      else -> ids.anonymousId
    }
    val key = "$name:${hashUnit(value.orEmpty())}"
    if (key in doc.once) {
      save(doc)
      return
    }
    doc.once.add(key)
    if (doc.once.size > MAX_ONCE_RECORDS) doc.once.removeAt(0)
    save(doc)
    enter(name, variant)
  }

  // Requests.

  private fun refresh(names: List<String>) {
    for (name in names) ask(name) {}
  }

  /** One request per turn of the executor, and one in flight per name. */
  private fun ask(name: String, callback: (Entry?) -> Unit) {
    waiting[name]?.let {
      it.add(callback)
      return
    }
    waiting[name] = mutableListOf(callback)
    batch.add(name)
    if (batch.size == 1) executor.execute { send() }
  }

  private fun send() {
    val names = ArrayList(batch)
    batch.clear()
    val ids = ids()
    val url = url
    if (ids == null || url == null) return resolve(names, emptyMap())
    for (chunk in names.chunked(MAX_NAMES_PER_REQUEST)) {
      val body = LinkedHashMap<String, JsonValue>()
      body["anonymousId"] = JsonValue.Str(ids.anonymousId)
      body["deviceId"] = JsonValue.Str(ids.deviceId)
      ids.userId?.let { body["userId"] = JsonValue.Str(it) }
      body["experiments"] = JsonValue.Arr(chunk.map { JsonValue.Str(it) })
      val bytes = JsonValue.Obj(body).serialized().toByteArray(Charsets.UTF_8)
      transport.post(url, writeKey, bytes) { response -> executor.execute { received(response, chunk, ids) } }
    }
  }

  /**
   * Offline, a 503 or a garbled body is `unavailable` for a name with nothing
   * stored, and the stored answer for one that has it. And the answer belongs to
   * the ids it was asked for: a reset() or identify() that landed while it was
   * in flight means someone else's variant.
   */
  private fun received(response: TransportResponse, names: List<String>, askedFor: Ids) {
    val status = response.status
    val body = response.body
    val json = if (status != null && status in 200..299 && body != null) {
      try {
        JsonValue.parse(body) as? JsonValue.Obj
      } catch (_: JsonParseException) {
        null
      }
    } else {
      null
    }
    val assignments = json?.get("assignments") as? JsonValue.Obj
    if (json == null || assignments == null || ids() != askedFor) return resolve(names, emptyMap())
    val now = clock.wallMs()
    val ttl = (json["maxAgeSeconds"] as? JsonValue.Int)?.value ?: 0
    val doc = load(askedFor)
    val answers = HashMap<String, Entry>()
    for ((name, value) in assignments.values) {
      val answer = value as? JsonValue.Obj ?: continue
      // A drifted body: `unavailable`.
      val status = answer.string("status")?.let(TogoExperimentStatus::from) ?: continue
      val entry = Entry(
        status, answer.string("variant"), answer["participating"] == JsonValue.Bool(true),
        answer.string("unit"), now, ttl,
      )
      answers[name] = entry
      doc.entries[name] = entry
      // The only thing that ends a stamp.
      if (status == TogoExperimentStatus.FINISHED) doc.entered.remove(name)
    }
    save(doc)
    resolve(names, answers)
  }

  private fun resolve(names: List<String>, answers: Map<String, Entry>) {
    for (name in names) waiting.remove(name)?.forEach { it(answers[name]) }
  }

  // The document.

  private fun load(ids: Ids): Doc {
    if (test) return memory
    var doc = this.doc ?: read() ?: Doc()
    if (doc.anonymousId != ids.anonymousId) {
      doc = Doc(ids.anonymousId, ids.userId, once = doc.once)
    } else if (doc.userId != ids.userId) {
      doc.userId = ids.userId
      doc.entries.clear()
    }
    this.doc = doc
    return doc
  }

  private fun save(doc: Doc) {
    if (test) {
      memory = doc
      return
    }
    this.doc = doc
    store.write(doc.json().serialized().toByteArray(Charsets.UTF_8))
  }

  /** A corrupt file is a refetch, never a crash. */
  private fun read(): Doc? {
    val bytes = store.read() ?: return null
    val json = try {
      JsonValue.parse(bytes) as? JsonValue.Obj
    } catch (_: JsonParseException) {
      null
    } ?: return null
    val entries = json["e"] as? JsonValue.Obj ?: return null
    val entered = json["x"] as? JsonValue.Obj ?: return null
    val doc = Doc(json.string("a"), json.string("u"))
    for ((name, value) in entries.values) entryFrom(value)?.let { doc.entries[name] = it }
    for ((name, value) in entered.values) (value as? JsonValue.Str)?.let { doc.entered[name] = it.value }
    (json["n"] as? JsonValue.Arr)?.values?.forEach { (it as? JsonValue.Str)?.let { s -> doc.once.add(s.value) } }
    return doc
  }

  private fun entryFrom(value: JsonValue): Entry? {
    val o = value as? JsonValue.Obj ?: return null
    val status = o.string("status")?.let(TogoExperimentStatus::from) ?: return null
    val at = (o["at"] as? JsonValue.Int)?.value ?: return null
    return Entry(
      status, o.string("variant"), o["participating"] == JsonValue.Bool(true), o.string("unit"), at,
      (o["ttl"] as? JsonValue.Int)?.value ?: 0,
    )
  }

  /** The file's shape. `v` lets a later SDK read an older file. */
  private fun Doc.json(): JsonValue {
    val o = LinkedHashMap<String, JsonValue>()
    o["v"] = JsonValue.Int(1)
    anonymousId?.let { o["a"] = JsonValue.Str(it) }
    userId?.let { o["u"] = JsonValue.Str(it) }
    o["e"] = JsonValue.Obj(entries.mapValues { (_, e) ->
      val m = LinkedHashMap<String, JsonValue>()
      m["status"] = JsonValue.Str(e.status.value)
      m["variant"] = e.variant?.let { JsonValue.Str(it) } ?: JsonValue.Null
      m["participating"] = JsonValue.Bool(e.participating)
      e.unit?.let { m["unit"] = JsonValue.Str(it) }
      m["at"] = JsonValue.Int(e.at)
      m["ttl"] = JsonValue.Int(e.ttlSeconds)
      JsonValue.Obj(m)
    })
    o["x"] = JsonValue.Obj(entered.mapValues { JsonValue.Str(it.value) })
    o["n"] = JsonValue.Arr(once.map { JsonValue.Str(it) })
    return JsonValue.Obj(o)
  }

  companion object {
    /** Past this a call answers `unavailable`; a later answer still fills the cache. */
    const val TIMEOUT_SECONDS = 0.8
    /** The Worker's `ASSIGNMENT_MAX_EXPERIMENTS`: above it the request is refused whole. */
    const val MAX_NAMES_PER_REQUEST = 50
    /** Enough that a shared device keeps its record; few enough to stay small. */
    const val MAX_ONCE_RECORDS = 200
    const val DEFAULT_ENDPOINT = "https://assign.togohq.ai"

    /** The browser's `hashUnit`: djb2 over UTF-16, base 36. Only so the raw id is not what the file holds. */
    fun hashUnit(value: String): String {
      var hash = 5381
      for (c in value) hash = hash * 33 + c.code
      return (hash.toLong() and 0xFFFFFFFFL).toString(36)
    }
  }
}
