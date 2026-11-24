package ai.togohq.core

import ai.togohq.TogoErrorCode

/**
 * The durable event queue — the one place a failure is not an error. Offline is
 * normal operation on a phone, so a failed send re-queues rather than throwing,
 * and the queue survives the process being killed, for days. It is a different
 * thing from the consent buffer, which is memory only.
 *
 * **Each queued event carries the `app` and `device` current when it was
 * tracked**, and [take] never puts two different ones in one batch. Both are
 * batch-level on the wire, so an event recorded on 1.2 and flushed after an
 * update to 1.3 would otherwise be stamped 1.3 — a plausible wrong answer on a
 * row nothing rewrites.
 */
internal class EventQueue(
  private val store: QueueStore,
  private val onDrop: (List<RawEvent>, TogoErrorCode) -> Unit,
) {
  class Entry(
    val event: RawEvent,
    val app: AppInfo?,
    val device: DeviceInfo?,
    /**
     * Where the event's `deviceId` came from, recorded with it: an upload made
     * before this process has read its identity record must still say how long
     * the id lasts, and a batch never mixes two modes.
     */
    val deviceIdMode: String? = null,
  ) {
    /**
     * This entry as one element of the queue file, and the event's size on the
     * wire — encoded ONCE, here. The queue is rewritten on every change, and
     * re-encoding a 5,000-event backlog for each `track()` would stall the
     * thread the getters wait on.
     */
    val line: String
    val lineBytes: Int
    val eventBytes: Int

    init {
      val eventJson = event.json()
      val o = LinkedHashMap<String, JsonValue>()
      o["event"] = eventJson
      app?.let { o["app"] = it.json() }
      device?.let { o["device"] = it.json() }
      deviceIdMode?.let { o["deviceIdMode"] = JsonValue.Str(it) }
      line = JsonValue.Obj(o).serialized()
      lineBytes = utf8Length(line)
      eventBytes = utf8Length(eventJson.serialized()) + 1
    }

    override fun equals(other: Any?): Boolean =
      other is Entry && other.event == event && other.app == app && other.device == device &&
        other.deviceIdMode == deviceIdMode

    override fun hashCode(): Int = event.hashCode()
  }

  /** Taken for an upload that has not been answered. **Still in the file** until an answer removes it. */
  private var inFlight: List<Entry> = emptyList()
  var entries: MutableList<Entry> = ArrayList()
    private set
  /**
   * The bytes [entries] take in the file, kept as they change so an append
   * never sums the backlog. Events taken for an upload are not counted, though
   * the file still holds them until the answer: they leave it with that answer,
   * and counting them would make every add during the upload a rewrite.
   */
  private var bytes = 0
  private var hydrated = false
  /**
   * A withdrawal cleared the queue but the file could not be deleted (a locked
   * device). Until it is, nothing is appended to it — an append would leave the
   * events from before the refusal in front of the new ones, to be read back
   * at the next launch — and the next change rewrites it whole instead.
   */
  private var removeOwed = false

  val count: Int get() = entries.size

  /** Load from the file once. An unreadable file is dropped: worth less than an exception every launch. */
  fun hydrate() {
    if (hydrated) return
    hydrated = true
    val data = store.read() ?: return
    // One entry per line. A line that does not parse — the last one, cut off
    // by a kill mid-append — is skipped, and the rest are kept.
    val lines = String(data, Charsets.UTF_8).split('\n').filter { it.isNotBlank() }
    val loaded = lines.mapNotNull { line ->
      val o = JsonValue.parseOrNull(line) as? JsonValue.Obj ?: return@mapNotNull null
      val event = o["event"]?.let(RawEvent::from) ?: return@mapNotNull null
      Entry(event, AppInfo.from(o["app"]), DeviceInfo.from(o["device"]), o.string("deviceIdMode"))
    }
    if (loaded.isEmpty()) {
      // Nothing in it was an entry: worth less than an exception, and
      // re-reading the same garbage on every launch.
      store.remove()
      return
    }
    entries = ArrayList(loaded + entries)
    bytes = entries.sumOf { it.lineBytes + 1 }
    // Rewritten once, so a torn line does not stay in the file under the
    // appends that follow it — nor a whole last line that lost its newline,
    // which the next append would join into one line that reads as neither.
    if (loaded.size != lines.size || data.last() != '\n'.code.toByte()) persist()
  }

  /**
   * One more event: an APPEND of its line, not a rewrite of the file — a
   * 5,000-event backlog rewritten, and synced, on every `track()` is megabytes
   * of writing on the thread the getters wait on. The whole file is rewritten
   * only when the queue changes otherwise (an answer, an eviction, an expiry),
   * once per batch rather than once per event.
   */
  fun add(entry: Entry) {
    entries.add(entry)
    bytes += entry.lineBytes + 1
    // Rewritten whole, never appended to, while a withdrawal's deletion is owed.
    if (removeOwed || entries.size > MAX_EVENTS || bytes > MAX_BYTES) {
      persist()
    } else {
      store.append((entry.line + "\n").toByteArray(Charsets.UTF_8))
    }
  }

  /**
   * The next batch: up to `limit` events, oldest first, all with the same `app`,
   * `device` and `deviceIdMode`, within one upload's bytes. Events past
   * `maxEventAgeMs` at `nowMs` are dropped first and reported — the edge would
   * refuse them, and they would crowd out events that can still land.
   */
  fun take(limit: Int, nowMs: Long): List<Entry> {
    val stale = entries.filter { nowMs - it.event.clientEventMs > Wire.maxEventAgeMs }
    if (stale.isNotEmpty()) {
      entries.removeAll { nowMs - it.event.clientEventMs > Wire.maxEventAgeMs }
      onDrop(stale.map { it.event }, TogoErrorCode.TIMESTAMP_TOO_OLD)
    }
    val first = entries.firstOrNull()
    if (first == null) {
      if (stale.isNotEmpty()) persist()
      return emptyList()
    }
    val taken = ArrayList<Entry>()
    var batchBytes = 0
    for (entry in entries) {
      if (taken.size >= limit || entry.app != first.app || entry.device != first.device ||
        entry.deviceIdMode != first.deviceIdMode
      ) {
        break
      }
      // Always at least one: a single event past the budget goes alone and the
      // edge's 413 answers it, rather than blocking the queue forever.
      if (taken.isNotEmpty() && batchBytes + entry.eventBytes > MAX_UPLOAD_BYTES) break
      batchBytes += entry.eventBytes
      taken.add(entry)
    }
    entries = ArrayList(entries.subList(taken.size, entries.size))
    bytes = entries.sumOf { it.lineBytes + 1 }
    // Not removed from the file: in flight until answered.
    inFlight = taken
    if (stale.isNotEmpty()) persist()
    return taken
  }

  /** The upload was answered — delivered, or refused for good: out of the file. */
  fun acknowledge() {
    if (inFlight.isEmpty()) return
    inFlight = emptyList()
    persist()
  }

  /**
   * The upload will be retried: back at the FRONT, in order, so the oldest —
   * closest to the age bound — are not starved. Nothing to do when a withdrawal
   * cleared the queue while the upload was out: putting them back would keep
   * events from before an explicit refusal.
   */
  fun retryInFlight() {
    if (inFlight.isEmpty()) return
    entries = ArrayList(inFlight + entries)
    inFlight = emptyList()
    // The file already holds them there — in flight first — so nothing is written.
    bytes = entries.sumOf { it.lineBytes + 1 }
  }

  /** Forget everything, in memory and on disk. Consent withdrawal. */
  fun clear() {
    entries = ArrayList()
    inFlight = emptyList()
    bytes = 0
    hydrated = true
    removeOwed = !store.remove()
  }

  /**
   * The device was unlocked, or the queue is about to change: make the
   * deletion [clear] could not — by deleting the file, or, when events have
   * been queued since, by rewriting it with only them.
   */
  fun retryRemove() {
    if (!removeOwed) return
    if (entries.isEmpty() && inFlight.isEmpty()) removeOwed = !store.remove() else persist()
  }

  private fun persist() {
    val dropped = ArrayList<RawEvent>()
    if (entries.size > MAX_EVENTS) {
      val excess = entries.size - MAX_EVENTS
      dropped += entries.subList(0, excess).map { it.event }
      entries = ArrayList(entries.subList(excess, entries.size))
    }
    // Sizes and lines are the ones each entry cached when it was made: summing
    // integers and joining strings, never re-encoding the backlog.
    var total = entries.sumOf { it.lineBytes + 1 }
    // The byte bound evicts the OLDEST, like the count. Largest-first dropped the
    // same kinds of event every time a queue filled — some are reliably bigger than
    // others — and so quietly under-counted exactly those; oldest-first loses in
    // time order. No event over the 256 KiB limit is ever queued.
    var evict = 0
    while (total > MAX_BYTES && entries.size - evict > 1) {
      total -= entries[evict].lineBytes + 1
      dropped.add(entries[evict].event)
      evict += 1
    }
    if (evict > 0) entries = ArrayList(entries.subList(evict, entries.size))
    // In flight first: that is their place in the queue if the process dies
    // before the answer, and where `retryInFlight` puts them if it comes.
    bytes = total
    val all = inFlight + entries
    val replaced = if (all.isEmpty()) {
      store.remove()
    } else {
      val out = StringBuilder(total + inFlight.sumOf { it.lineBytes + 1 })
      for (e in all) out.append(e.line).append('\n')
      store.write(out.toString().toByteArray(Charsets.UTF_8))
    }
    // A whole rewrite replaces whatever the file held before a withdrawal.
    if (replaced) removeOwed = false
    if (dropped.isNotEmpty()) onDrop(dropped, TogoErrorCode.QUEUE_FULL)
  }

  companion object {
    /**
     * Bounds, both of them: a count bound alone lets a few fat events grow the
     * file without limit, a byte bound alone lets a flood of tiny ones make
     * every rewrite slow. Larger than the browser's, because an app's queue
     * lasts days of offline use where a page's lasts seconds.
     */
    const val MAX_EVENTS = 5_000
    /**
     * 8 MiB: about a week of an active user's events, the 7-day age past which the edge refuses
     * one. The queue has two copies — the file survives a kill, and the in-memory list, the WHOLE
     * queue, is what the SDK works from — so the bound is app memory as well as disk.
     */
    const val MAX_BYTES = 8 * 1024 * 1024
    /**
     * One upload's byte budget: a device SDK's 1 MiB batch, less room for the batch's own fields —
     * set on its own rather than from the edge's much larger request limit.
     */
    const val MAX_UPLOAD_BYTES = 1_048_576 - 16 * 1024
  }
}

/** UTF-8 byte length without allocating the bytes. Lone surrogates are written as U+FFFD (3 bytes). */
internal fun utf8Length(s: String): Int {
  var n = 0
  var i = 0
  while (i < s.length) {
    val c = s[i]
    n += when {
      c.code < 0x80 -> 1
      c.code < 0x800 -> 2
      Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { i++; 4 }
      else -> 3
    }
    i++
  }
  return n
}
