package ai.togohq.core

import ai.togohq.TogoConsent
import ai.togohq.TogoError
import java.net.URL

/** A clock the test moves. */
internal class FakeClock : Clock {
  var wall: Long = 1_792_584_000_000 // 2026-10-21T12:00:00.000Z
  var monotonic: Long = 50_000
  override fun wallMs() = wall
  override fun monotonicMs() = monotonic

  /** Time passing on a device nobody touched: both clocks move. */
  fun advance(ms: Long) {
    wall += ms
    monotonic += ms
  }
}

/** A network the test answers. Once the answers are used up, every upload is a 202. */
internal class FakeNetwork : Transport {
  val answers = ArrayDeque<TransportResponse>()
  val sent = ArrayList<JsonValue.Obj>()
  val urls = ArrayList<URL>()
  val keys = ArrayList<String>()
  /** Uploads go out and are not answered until [answer] — or never, when the process is killed first. */
  var holds = false
  val held = ArrayList<(TransportResponse) -> Unit>()

  fun answer(response: TransportResponse = accepted) {
    held.removeAt(0)(response)
  }

  override fun post(url: URL, writeKey: String, body: ByteArray, completion: (TransportResponse) -> Unit) {
    sent.add(JsonValue.parse(body) as JsonValue.Obj)
    urls.add(url)
    keys.add(writeKey)
    if (holds) {
      held.add(completion)
      return
    }
    completion(answers.removeFirstOrNull() ?: accepted)
  }

  val events: List<JsonValue.Obj> get() = sent.flatMap { (it["events"] as JsonValue.Arr).values.map { e -> e as JsonValue.Obj } }
  val names: List<String> get() = events.map { it.string("eventName")!! }

  companion object {
    val accepted = TransportResponse(202, body = "{}".toByteArray())
  }
}

/** Work run inline; timers recorded rather than fired. */
internal class InlineExecutor : TogoExecutor {
  val timers = ArrayList<Double>()
  override fun execute(work: () -> Unit) = work()
  override fun after(seconds: Double, work: () -> Unit): () -> Unit {
    timers.add(seconds)
    return {}
  }
}

/** A client wired to fakes. */
internal class Harness(
  writeKey: String = "togo_pkl_live0000000000",
  consent: TogoConsent = TogoConsent.NOT_REQUIRED,
  sessionTimeoutSeconds: Double? = null,
  defaultProperties: Map<String, Any?>? = null,
  app: AppInfo? = AppInfo("1.2.0", "12"),
  device: DeviceInfo? = DeviceInfo("Android", "14", "Pixel 8", "mobile", "Google"),
  val identity: MemoryIdentityStore = MemoryIdentityStore(),
  val queueStore: MemoryQueueStore = MemoryQueueStore(),
  val deviceIds: DeviceIdProvider = generatedDeviceIds,
  consentBufferMaxEvents: Int = 100,
  firstInstallMs: Long? = null,
  /** The client reports the app's lifecycle: each [start] is a launch, and [foreground]/[background] move activities. */
  private val lifecycle: Boolean = false,
) {
  val clock = FakeClock()
  val network = FakeNetwork()
  val executor = InlineExecutor()
  val logs = ArrayList<String>()
  val errors = ArrayList<TogoError>()
  var options = EngineOptions(
    writeKey = writeKey, endpoint = "https://in.example.test", sessionTimeoutSeconds = sessionTimeoutSeconds,
    consent = consent, consentBufferMaxEvents = consentBufferMaxEvents,
    defaultProperties = defaultProperties?.let { toProps(it) { _, _ -> } },
    app = app, device = device, timezone = "Europe/Berlin", onError = { errors.add(it) },
    firstInstallMs = firstInstallMs,
  )
  /** Where the activity counter's delayed background runs; [background] moves it past the delay. */
  val mainThread = ManualScheduler()
  var appLifecycle: AppLifecycle? = null
    private set
  private var counter: ActivityCounter? = null
  lateinit var engine: Engine
    private set

  init {
    start()
  }

  /** A new process over the same stores: the app was killed and relaunched. */
  fun start(): Engine {
    if (!lifecycle) {
      engine = Engine(
        options, identity, deviceIds, queueStore, network, executor, executor, clock, Logger { logs.add(it) },
      )
      return engine
    }
    val next = AppLifecycle()
    engine = Engine(
      options, identity, deviceIds, queueStore, network, executor, executor, clock, Logger { logs.add(it) },
      humanPresent = { next.isForeground },
    )
    appLifecycle = next
    counter = ActivityCounter(mainThread, { next.foregrounded() }, { next.backgrounded() }) { unknownStops++ }
    next.launched(engine)
    return engine
  }

  /** A new process running another build. */
  fun relaunch(version: String?, build: String?): Engine {
    options = options.copy(app = AppInfo(version, build))
    return start()
  }

  /** Stops for an activity the counter never saw: what the adapter reports once in the log. */
  var unknownStops = 0

  /** An activity resumes: for one never seen starting, the start the SDK missed. */
  fun resume(activity: String = "main") = counter!!.activityResumed(activity)

  /** An activity was already in front when the SDK was set up: the adapter seeds it. */
  fun seed() = counter!!.seedUnseen()

  /** An activity starts. */
  fun foreground(activity: String = "main") = counter!!.activityStarted(activity)

  /** An activity stops, and — unless [settle] is false — the background delay runs out. */
  fun background(activity: String = "main", settle: Boolean = true) {
    counter!!.activityStopped(activity)
    if (settle) mainThread.advance(700)
  }

  /** Flush until the queue is empty. */
  fun flushAll() {
    repeat(10) {
      if (engine.queuedCount == 0) return
      flush()
    }
  }

  fun flush() = engine.flush()

  /** What the queue file holds, by event name. */
  fun storedNames(): List<String> {
    val data = queueStore.read() ?: return emptyList()
    return String(data).split('\n').filter { it.isNotBlank() }
      .map { ((JsonValue.parse(it) as JsonValue.Obj)["event"] as JsonValue.Obj).string("eventName")!! }
  }
}

internal fun str(value: String) = JsonValue.Str(value)
internal fun obj(vararg pairs: Pair<String, JsonValue>) = JsonValue.Obj(mapOf(*pairs))
internal fun JsonValue.Obj.obj(key: String) = this[key] as JsonValue.Obj
internal fun JsonValue.Obj.ids(): List<String> =
  (this["events"] as JsonValue.Arr).values.map { (it as JsonValue.Obj).string("eventId")!! }

/**
 * Timers that fire when the test says so: [advance] moves the scheduler's own
 * time and runs every timer that came due, in due order. `execute` runs inline.
 */
internal class ManualScheduler : TogoExecutor {
  private class Timer(val dueMs: Long, val seq: Int, val work: () -> Unit) {
    var cancelled = false
  }
  private val timers = ArrayList<Timer>()
  private var seq = 0
  var nowMs: Long = 0
    private set

  override fun execute(work: () -> Unit) = work()

  override fun after(seconds: Double, work: () -> Unit): () -> Unit {
    val timer = Timer(nowMs + (seconds * 1000).toLong(), seq++, work)
    timers.add(timer)
    return { timer.cancelled = true }
  }

  /** Timers waiting to fire. */
  val pending: Int get() = timers.count { !it.cancelled }

  fun advance(ms: Long) {
    val until = nowMs + ms
    while (true) {
      val next = timers.filter { !it.cancelled && it.dueMs <= until }.minWithOrNull(compareBy({ it.dueMs }, { it.seq })) ?: break
      timers.remove(next)
      nowMs = next.dueMs
      next.work()
    }
    nowMs = until
    timers.removeAll { it.cancelled }
  }
}
