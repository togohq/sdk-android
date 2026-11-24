package ai.togohq.scenarios

import ai.togohq.TogoConsent
import ai.togohq.core.ActivityCounter
import ai.togohq.core.AppInfo
import ai.togohq.core.AppLifecycle
import ai.togohq.core.MemoryIdentityStore
import ai.togohq.core.MemoryQueueStore
import ai.togohq.core.Clock
import ai.togohq.core.DeviceInfo
import ai.togohq.core.Engine
import ai.togohq.core.HttpTransport
import ai.togohq.core.EngineOptions
import ai.togohq.core.JsonValue
import ai.togohq.core.Logger
import ai.togohq.core.TogoExecutor
import ai.togohq.core.Transport
import ai.togohq.core.TransportResponse
import ai.togohq.core.toProps
import java.io.File
import java.net.URL

/**
 * One recorded upload: which device sent it, and the batch — and, for one sent
 * to a real edge, its status and answer.
 */
internal data class Upload(
  val client: String,
  val body: JsonValue.Obj,
  val status: Int? = null,
  val response: JsonValue? = null,
)

/**
 * Perform planned visitors through the core, and record what each device would
 * upload. A port of the browser SDK's scenario driver in its record mode, and
 * the same two phases, because the edge dates an event by the device clock
 * corrected at upload:
 *
 * 1. **Offline.** Every step runs at its planned time on a virtual clock with
 *    the network held — an upload answers as offline, which the SDK re-queues —
 *    and the flush timer never fires, so events accumulate with their real
 *    `clientEventTs`.
 * 2. **Release.** The clock reads now, the network opens, and every device
 *    flushes; each upload is answered 202 and recorded. A `flush` step is the
 *    same release for one device, mid-scenario.
 *
 * **A `server` step is skipped.** It is what a real product sends from its
 * backend, through the server SDK — never through a device SDK — so a device
 * SDK replays the device steps only.
 */
internal object ScenarioDriver {
  const val GOLDEN_NOW_MS = 1_792_584_000_000L // 2026-10-21T12:00:00.000Z
  const val GOLDEN_SEED = 1
  const val GOLDEN_RUN_ID = "golden"
  /** Every device's zone, pinned as the browser runner pins it. */
  const val SITE_TIMEZONE = "America/New_York"
  private const val WRITE_KEY = "togo_pkt_scenarioRecordingKey00"
  private const val ENDPOINT = "https://in.example.test"
  /** One upload per device, as in the browser runner. */
  private const val MAX_EVENTS_PER_DEVICE = 500

  /** The app and device every recording device reports. Not in the browser's goldens, so the comparison removes them. */
  val APP = AppInfo("1.4.0", "140")
  val DEVICE = DeviceInfo("Android", "14", "SM-S918B", "mobile", "samsung")

  /** One scenario's batches under the golden clock and seed, normalized. */
  fun record(name: String, root: File = ScenarioFormat.defaultRoot): List<Upload> {
    val scenario = ScenarioFormat.loadScenario(name, root)
    val plan = ScenarioFormat.planVisitor(scenario, GOLDEN_SEED, 1, GOLDEN_NOW_MS, GOLDEN_RUN_ID)
    return Normalize.placeholders(drive(listOf(plan), GOLDEN_NOW_MS))
  }

  private class Client(val label: String, val engine: Engine, val skew: Long)

  /** The raw uploads, in the order each device first appeared. */
  fun drive(plans: List<Plan>, nowMs: Long): List<Upload> {
    val clock = VirtualClock(nowMs)
    val network = HeldNetwork()
    val executor = InlineExecutor()
    val clients = ArrayList<Client>()

    fun upload(client: Client) {
      val saved = clock.at to clock.skew
      network.released = true
      network.label = client.label
      clock.at = nowMs
      clock.skew = client.skew
      // One upload per call; a second only for a batch the SDK itself cut.
      for (attempt in 0 until 4) {
        val before = network.sent.size
        client.engine.flush()
        if (network.sent.size == before) break
      }
      clock.at = saved.first
      clock.skew = saved.second
      network.released = false
    }

    for (plan in plans) {
      val own = HashMap<String, Client>()
      for (item in plan.ordered) {
        if (item.actor == "server") continue
        clock.at = item.timeMs
        clock.skew = plan.deviceClockOffsetMs
        val device = item.device ?: "browser"
        val client = own.getOrPut(device) {
          val options = EngineOptions(
            writeKey = WRITE_KEY,
            endpoint = ENDPOINT,
            sessionTimeoutSeconds = number(plan.initOptions["sessionTimeoutSeconds"]),
            consent = consent(plan.initOptions["consent"]) ?: TogoConsent.NOT_REQUIRED,
            defaultProperties = (plan.initOptions["defaultProperties"] as? JsonValue.Obj)?.let { toProps(plain(it)) { _, _ -> } },
            app = APP,
            device = DEVICE,
            timezone = SITE_TIMEZONE,
            batchSize = MAX_EVENTS_PER_DEVICE,
            flushIntervalSeconds = 86_400.0,
          )
          val engine = Engine(options, transport = network, executor = executor, callbacks = executor, clock = clock, log = Logger { })
          Client("${plan.scenario}#${plan.n}:$device", engine, plan.deviceClockOffsetMs).also { clients += it }
        }
        perform(client.engine, item.call) { upload(client) }
      }
    }
    for (client in clients) upload(client)
    return network.sent
  }

  // Native scenarios.

  /** One native scenario's batches under the golden clock and seed, normalized: what `expected-native/` is compared with. */
  fun recordNative(name: String, root: File = ScenarioFormat.defaultRoot): List<Upload> {
    val scenario = ScenarioFormat.loadNativeScenario(name, root)
    val plan = ScenarioFormat.planVisitor(scenario, GOLDEN_SEED, 1, GOLDEN_NOW_MS, GOLDEN_RUN_ID)
    return Normalize.placeholders(driveNative(plan, GOLDEN_NOW_MS))
  }

  /**
   * One app on one device, through its launches. A `launch` is a new process
   * over the same identity and queue stores — the last one was killed with no
   * callback — with the build the step names. Activities start and stop
   * through the core's `ActivityCounter`, exactly as the Android adapter drives
   * it, and its delayed background runs at its due time on the virtual clock.
   *
   * **The install time is the first launch's.** The native format names a
   * build for each launch but not when the package was installed; every
   * native scenario's first launch is a new install, so that is the honest
   * input, and Android's seven-day rule then agrees with the goldens.
   */
  fun driveNative(
    plan: Plan,
    nowMs: Long,
    network: HeldNetwork = HeldNetwork(),
    writeKey: String = WRITE_KEY,
    endpoint: String = ENDPOINT,
  ): List<Upload> {
    val clock = VirtualClock(nowMs)
    clock.skew = plan.deviceClockOffsetMs
    network.label = "${plan.scenario}#${plan.n}:app"
    val executor = InlineExecutor()
    val mainThread = VirtualScheduler(clock)
    val identity = MemoryIdentityStore()
    val queue = MemoryQueueStore()
    var engine: Engine? = null
    var counter: ActivityCounter? = null
    var installedAtMs: Long? = null

    for (item in plan.ordered) {
      mainThread.runUntil(item.timeMs)
      clock.at = item.timeMs
      val call = item.call
      val launch = call["launch"] as? JsonValue.Obj
      if (launch != null) {
        // The last process is gone, killed with no callback, and anything it
        // had scheduled — a background 700 ms after its last stop — with it.
        mainThread.clear()
        val installed = installedAtMs ?: clock.wallMs().also { installedAtMs = it }
        val options = EngineOptions(
          writeKey = writeKey,
          endpoint = endpoint,
          sessionTimeoutSeconds = number(plan.initOptions["sessionTimeoutSeconds"]),
          consent = consent(plan.initOptions["consent"]) ?: TogoConsent.NOT_REQUIRED,
          defaultProperties = (plan.initOptions["defaultProperties"] as? JsonValue.Obj)?.let { toProps(plain(it)) { _, _ -> } },
          app = AppInfo(launch.string("appVersion"), launch.string("appBuild")),
          device = DEVICE,
          timezone = SITE_TIMEZONE,
          firstInstallMs = installed,
          batchSize = MAX_EVENTS_PER_DEVICE,
          flushIntervalSeconds = 86_400.0,
        )
        val lifecycle = AppLifecycle()
        val started = Engine(
          options, identity, queueStore = queue, transport = network, executor = executor, callbacks = executor,
          clock = clock, log = Logger { }, humanPresent = { lifecycle.isForeground },
        )
        counter = ActivityCounter(mainThread, { lifecycle.foregrounded() }, { lifecycle.backgrounded() })
        lifecycle.launched(started)
        engine = started
        continue
      }
      val current = engine ?: continue // The loader requires a launch first.
      val foreground = call.string("foreground")
      val background = call.string("background")
      when {
        foreground != null -> counter!!.activityStarted(foreground)
        background != null -> counter!!.activityStopped(background)
        else -> perform(current, call) { release(current, network, clock, nowMs) }
      }
    }
    mainThread.runUntil(Long.MAX_VALUE)
    engine?.let { release(it, network, clock, nowMs) }
    return network.sent
  }

  /**
   * One native scenario performed through the core and uploaded to a REAL edge
   * at `endpoint` — the stage journey's sender. The same two phases as a
   * recording: every step at its planned time on a virtual clock with the
   * network held, so each event keeps its real `clientEventTs`; then the clock
   * reads now and the device uploads through the SDK's own HttpTransport, so
   * the edge's skew correction dates each event in the past it happened in
   * (inside the seven-day bound: the planner places a visit within the last six
   * and a half days). Nothing is normalized: the batches are as sent, with the
   * edge's answers.
   */
  fun sendNative(name: String, endpoint: String, writeKey: String, nowMs: Long, runId: String): List<Upload> {
    val scenario = ScenarioFormat.loadNativeScenario(name, ScenarioFormat.defaultRoot)
    val plan = ScenarioFormat.planVisitor(scenario, GOLDEN_SEED, 1, nowMs, runId)
    val network = HeldNetwork().apply { forward = HttpTransport() }
    return driveNative(plan, nowMs, network, writeKey, endpoint)
  }

  /** Every batch the device holds, at `now`: one per app build, since a batch never mixes two. */
  private fun release(engine: Engine, network: HeldNetwork, clock: VirtualClock, nowMs: Long) {
    val saved = clock.at
    network.released = true
    clock.at = nowMs
    for (attempt in 0 until 20) {
      val before = network.sent.size
      engine.flush()
      if (network.sent.size == before) break
      // A real edge that refused or did not answer is the run's answer: retrying
      // would only resend the same batch and bury the status under twenty of them.
      if (network.forward != null && network.sent.last().status !in 200..299) break
    }
    clock.at = saved
    network.released = false
  }

  /** One device verb, on one engine. */
  fun perform(engine: Engine, call: JsonValue.Obj, flush: () -> Unit) {
    val name = call.string("track")
    val userId = call.string("identify")
    val ops = call["setPersonProperties"] as? JsonValue.Obj
    val state = consent(call["setConsent"])
    when {
      name != null -> engine.track(name, (call["properties"] as? JsonValue.Obj)?.let(::plain))
      userId != null -> engine.identify(userId)
      ops != null -> {
        val email = ops["email"]
        val personName = ops["name"]
        engine.setPersonProperties(
          email = (email as? JsonValue.Str)?.value,
          name = (personName as? JsonValue.Str)?.value,
          set = (ops["set"] as? JsonValue.Obj)?.let(::plain),
          setOnce = (ops["setOnce"] as? JsonValue.Obj)?.let(::plain),
          unset = (ops["unset"] as? JsonValue.Arr)?.values?.mapNotNull { (it as? JsonValue.Str)?.value },
          removeEmail = email == JsonValue.Null,
          removeName = personName == JsonValue.Null,
        )
      }
      state != null -> engine.setConsent(state)
      call.values.containsKey("reset") -> engine.reset()
      call.values.containsKey("flush") -> flush()
    }
  }

  private fun consent(value: JsonValue?): TogoConsent? =
    (value as? JsonValue.Str)?.value?.let { v -> TogoConsent.values().firstOrNull { it.value == v } }

  private fun number(value: JsonValue?): Double? = when (value) {
    is JsonValue.Int -> value.value.toDouble()
    is JsonValue.Dbl -> value.value
    else -> null
  }

  /** A scenario's JSON object as the map an app would pass. */
  fun plain(obj: JsonValue.Obj): Map<String, Any?> = obj.values.mapValues { (_, v) ->
    when (v) {
      is JsonValue.Str -> v.value
      is JsonValue.Int -> v.value
      is JsonValue.Dbl -> v.value
      is JsonValue.Bool -> v.value
      JsonValue.Null -> null
      else -> v // A nested value: the SDK refuses it, as it would an app's.
    }
  }
}

/**
 * The device clock: the step's planned time plus the scenario's skew. The
 * monotonic clock moves with it, so idle time within the run is the same on
 * both, as it is on a device whose wall clock nobody touched.
 */
internal class VirtualClock(var at: Long) : Clock {
  var skew = 0L
  override fun wallMs() = at + skew
  override fun monotonicMs() = at + skew
}

/**
 * Offline until released; then every upload is recorded and answered 202 — or,
 * with `forward` set, sent there and answered with what it answered, waiting for
 * it so the release stays one step on the virtual clock.
 */
internal class HeldNetwork : Transport {
  var released = false
  var label = ""
  var forward: Transport? = null
  val sent = ArrayList<Upload>()

  override fun post(url: URL, writeKey: String, body: ByteArray, completion: (TransportResponse) -> Unit) {
    val json = JsonValue.parse(body) as? JsonValue.Obj
    if (!released || json == null) {
      completion(TransportResponse(null))
      return
    }
    val real = forward
    if (real != null) {
      val done = java.util.concurrent.CountDownLatch(1)
      var answer = TransportResponse(null)
      real.post(url, writeKey, body) { answer = it; done.countDown() }
      done.await()
      val parsed = answer.body?.let { runCatching { JsonValue.parse(it) }.getOrNull() }
      sent += Upload(label, json, answer.status, parsed)
      completion(answer)
      return
    }
    sent += Upload(label, json)
    val count = (json["events"] as? JsonValue.Arr)?.values?.size ?: 0
    completion(TransportResponse(202, body = "{\"accepted\":$count}".toByteArray()))
  }
}

/**
 * The main thread of a native scenario's app: a timer runs when the virtual
 * clock reaches it, at its own due time, before the step that comes after it.
 */
internal class VirtualScheduler(private val clock: VirtualClock) : TogoExecutor {
  private class Timer(val dueMs: Long, val seq: Int, val work: () -> Unit) {
    var cancelled = false
  }
  private val timers = ArrayList<Timer>()
  private var seq = 0

  override fun execute(work: () -> Unit) = work()

  override fun after(seconds: Double, work: () -> Unit): () -> Unit {
    val timer = Timer(clock.at + (seconds * 1000).toLong(), seq++, work)
    timers += timer
    return { timer.cancelled = true }
  }

  fun runUntil(timeMs: Long) {
    while (true) {
      val next = timers.filter { !it.cancelled && it.dueMs <= timeMs }.minWithOrNull(compareBy({ it.dueMs }, { it.seq })) ?: break
      timers.remove(next)
      clock.at = next.dueMs
      next.work()
    }
    timers.removeAll { it.cancelled }
  }

  fun clear() = timers.clear()
}

/** Runs work at once, on the caller's thread; a timer never fires. The run is single-threaded. */
internal class InlineExecutor : TogoExecutor {
  override fun execute(work: () -> Unit) = work()
  override fun after(seconds: Double, work: () -> Unit): () -> Unit = {}
}
