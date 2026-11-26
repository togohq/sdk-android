package ai.togohq.core

import ai.togohq.TogoConsent
import ai.togohq.TogoError
import ai.togohq.TogoErrorCode
import ai.togohq.TogoExperiment
import java.net.URL
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/** Everything `Togo.setup()` configures, resolved: the Android module reads the package, the device and the time zone. */
internal data class EngineOptions(
  val writeKey: String,
  val endpoint: String,
  val sessionTimeoutSeconds: Double? = null,
  val consent: TogoConsent = TogoConsent.NOT_REQUIRED,
  val consentBufferMaxEvents: Int = 100,
  val defaultProperties: Props? = null,
  /** The app build: `PackageInfo`'s numbers, or the `appVersion`/`appBuild` options. */
  val app: AppInfo? = null,
  /** The device as the OS reports it. */
  val device: DeviceInfo? = null,
  /** The device's IANA zone, read once. */
  val timezone: String? = null,
  /**
   * When the package was first installed on this device, wall-clock
   * milliseconds: what tells a new install from an existing user's first
   * launch with the SDK. `null` when the platform cannot say, and then a
   * launch with no identity record is an install.
   */
  val firstInstallMs: Long? = null,
  val onError: ((TogoError) -> Unit)? = null,
  /** The `sdk` every event names: this SDK's own unless a wrapper set its own. */
  val sdk: SdkIdentity = SdkIdentity.NATIVE,
  /** Where `getExperiment()` asks: the assignment service. */
  val assignEndpoint: String = Experiments.DEFAULT_ENDPOINT,
  /**
   * Experiment names fetched at each `$app_opened` without an exposure, so the
   * first `getExperiment()` of a launch does not wait.
   */
  val experiments: List<String> = emptyList(),
  /**
   * Fixed on a device, not options, because batching is the SDK's to set: 50
   * events or 5 seconds. Only the scenario runner changes them.
   */
  val batchSize: Int = 50,
  val flushIntervalSeconds: Double = 5.0,
)

/**
 * The SDK's behaviour, platform-free: a port of the browser SDK's client for a
 * device that is an app rather than a page.
 *
 * **Confined to one serial executor.** Every method must be called on the
 * executor it was given, which is what `Togo` does; that keeps calls in the
 * order they were made and the state free of locks. The scenario runner and
 * the tests call it directly, on one thread, with an executor that runs work
 * inline.
 *
 * **Nothing here throws.** A refusal is a log line naming the problem and the
 * fix, and an `onError` call, never an exception: an analytics call must not be
 * able to take down the code it sits in.
 */
internal class Engine(
  val options: EngineOptions,
  private val identityStore: IdentityStore = MemoryIdentityStore(),
  private val deviceIds: DeviceIdProvider = generatedDeviceIds,
  queueStore: QueueStore = MemoryQueueStore(),
  private val transport: Transport,
  private val executor: TogoExecutor,
  private val callbacks: TogoExecutor,
  private val clock: Clock = SystemClock,
  private val log: Logger = standardErrorLogger,
  private val humanPresent: () -> Boolean = { true },
  private val experimentStore: ExperimentStore = MemoryExperimentStore(),
) {
  private val sessions = Sessions(options.sessionTimeoutSeconds, log)
  private val defaults = DefaultProperties(options.defaultProperties, log)
  private val reservedKeys = ReservedKeys(options.writeKey)
  private val valueCaps = ValueCaps(options.writeKey)
  private val app = validatedApp(options.app, log)
  private val device = validatedDevice(options.device)
  private val queue: EventQueue = EventQueue(queueStore) { events, code ->
    val reason = if (code == TogoErrorCode.QUEUE_FULL) "queue full (offline for a long while, or very large events), oldest first" else "older than 7 days"
    report(code, "${events.size} event(s) dropped: $reason", events.map { it.eventId })
  }

  var consent: TogoConsent = options.consent
    private set

  private val assignments = Experiments(
    options.writeKey, options.assignEndpoint, experimentStore, transport, executor, clock,
    ids = {
      if (disabled) null else identity()?.let { Experiments.Ids(it.anonymousId, it.deviceId, userId) }
    },
    sessionId = { sessionId },
    humanPresent = humanPresent,
    enter = { name, variant ->
      submit(
        Wire.experimentEnteredEvent,
        mapOf("experiment" to JsonValue.Str(name), "variant" to JsonValue.Str(variant)),
        null, options.timezone,
      )
    },
    log = log,
  )

  /**
   * The signed-in user. Remembered in the identity record when consent permits
   * a write; kept in memory otherwise, and written at the first moment it does.
   */
  private var userId: String? = null
  /** The working copy of the identity record, loaded at the first moment consent permits reading the device. */
  private var record = IdentityRecord()
  private var loaded = false
  /** `identify()` and `reset()` calls in this process. A held event made before any inherits the stored user. */
  private var identityCalls = 0
  /**
   * The user the identity record named when it was first read, before anything
   * this process did to it, and the anonymous id beside it.
   */
  private var storedUserId: String? = null
  private var storedAnonymousId: String? = null
  private var storedUserRead = false
  /** `reset()` was called before the record could be read: applied to it at the load. */
  private var logoutBeforeLoad = false
  /** `identify()` was called before the record could be read: a different stored user is reset at the load. */
  private var identifiedBeforeLoad = false
  /** Held in memory: under pending consent, or while the identity cannot be read. Bounded; never persisted. */
  private val held = ArrayList<HeldEvent>()
  /**
   * The launch's `$app_installed` / `$app_updated` decision is owed: it needs
   * the identity record, which waits for consent and for the device to be
   * unlocked. The wall time of the launch it is about.
   */
  private var launchOwedAtMs: Long? = null
  /** The last load found no record: a first launch, cleared data, or a withdrawal deleted it. */
  private var foundNoRecord = false
  /**
   * [start] was called: this client reports the app's lifecycle. A client that
   * never starts — the browser-parity scenario replay, a test of the rest of the
   * surface — sends no lifecycle event at all.
   */
  private var reportsLaunches = false

  var disabled: Boolean = false
    private set
  private var cancelTimer: (() -> Unit)? = null
  /** Consecutive failed uploads. Reset by any outcome that is not a retry. */
  private var attempt = 0
  /** An upload has been posted and not answered yet. One at a time, so a backlog drains in order. */
  private var inFlight = false
  /** A non-edge status (a redirect) was logged once; the next ones are only retried. */
  private var warnedUnexpectedStatus = false

  init {
    // A queue and an identity left on the device by a client whose consent is
    // now `denied` are deleted rather than read: they predate the refusal, and
    // a grant later must not link the person after it to the one before.
    if (consent == TogoConsent.DENIED) {
      queue.clear()
      identityStore.erase()
      experimentStore.remove()
    }
  }

  // The public surface.

  /** Send an event. Returns the `eventId` it queued (or holds), or `null` when nothing will be sent. */
  fun track(eventName: String, properties: Map<String, Any?>? = null): String? {
    if (eventName.isEmpty() || eventName.length > Wire.maxEventNameLength) {
      val named = eventName.isNotEmpty()
      report(
        if (named) TogoErrorCode.EVENT_NAME_TOO_LONG else TogoErrorCode.EVENT_NAME_EMPTY,
        "an event was not sent: its name must be 1 to ${Wire.maxEventNameLength} characters " +
          "(got ${eventName.length}).",
        emptyList(), if (named) eventName else null,
      )
      return null
    }
    if (!disabled) {
      val reserved = Reserved.checkName(eventName, options.writeKey)
      if (reserved != null) {
        if (reserved.send) {
          log.log(reserved.message)
        } else {
          report(TogoErrorCode.EVENT_NAME_RESERVED, reserved.message, emptyList(), eventName)
          return null
        }
      }
    }
    val props = values(eventName, properties)
    val merged = defaults.merge(eventName, props)
    // The property count first, on the merged map as sent, so the name checks see what the edge
    // would (`ValueCaps.capCount`).
    val drops = ArrayList<Pair<String, String>>()
    val checked = if (disabled) {
      merged
    } else {
      reservedKeys.check(valueCaps.capCount(eventName, merged) { once, message -> drops.add(once to message) }) {
          code, message, property ->
        report(code, message, emptyList(), eventName, property)
      } ?: emptyMap()
    }
    return submit(eventName, checked, null, options.timezone, drops)
  }

  /**
   * The authenticated identity. The same user again sends nothing; a new one
   * sends one `$identify`; a different one is a missed logout, so it resets first.
   */
  fun identify(id: String): String? {
    // Refused here, per call: the edge bounds `userId` to 1–128 characters for
    // the WHOLE batch, so an empty id from an account not yet loaded would ride
    // every later event and lose each upload with a 400.
    if (id.isEmpty() || id.length > Wire.maxUserIdLength) {
      report(
        TogoErrorCode.USER_ID_INVALID,
        "identify() was given a userId of ${id.length} characters; it must be 1 to " +
          "${Wire.maxUserIdLength}. Nothing changed: pass your account's own id once it exists.",
      )
      return null
    }
    loadIfPermitted()
    if (id == userId) return null
    identityCalls++
    if (userId != null) resetIdentity()
    userId = id
    // Compared with the stored user once the record can be read.
    if (!loaded) identifiedBeforeLoad = true
    if (consent.permitted) {
      record = record.copy(userId = id)
      save()
    }
    return submit(Wire.identifyEvent, emptyMap(), null, null)
  }

  /** One `$set_person_properties` carrier through the consent gate; an empty update sends nothing. */
  fun setPersonProperties(
    email: Any? = null,
    name: Any? = null,
    set: Map<String, Any?>? = null,
    setOnce: Map<String, Any?>? = null,
    unset: List<String>? = null,
    removeEmail: Boolean = false,
    removeName: Boolean = false,
  ): String? {
    if (disabled) return null
    val label = Wire.personPropertiesCarrierEvent
    val typedEmail = if (removeEmail) JsonValue.Null else email?.let { JsonValue.Str(it.toString()) }
    val typedName = if (removeName) JsonValue.Null else name?.let { JsonValue.Str(it.toString()) }
    // The property count first, on each map as sent — `set` with the `$email` and `$name` the SDK
    // adds — before the name checks.
    val drops = ArrayList<Pair<String, String>>()
    val collect = { once: String, message: String -> drops.add(once to message); Unit }
    val typed = listOfNotNull(
      typedEmail?.let { Wire.reservedEventPrefix + "email" },
      typedName?.let { Wire.reservedEventPrefix + "name" },
    )
    val update = PersonPropertyUpdate(
      email = typedEmail,
      name = typedName,
      set = valueCaps.capCount(label, set?.let { values(label, it) }, "set", typed, collect),
      setOnce = valueCaps.capCount(label, setOnce?.let { values(label, it) }, "setOnce", collect = collect),
      unset = valueCaps.capCount(label, unset, "unset", collect),
    )
    val ops = reservedKeys.personOps(update) { code, message, property ->
      report(code, message, emptyList(), null, property)
    } ?: return null
    return submit(label, emptyMap(), ops, null, drops)
  }

  /**
   * Which variant of an experiment this device gets. The stored answer when
   * there is one — at any age, offline included, refreshed in the background
   * when old — or the network's, within 800 ms; `unavailable` otherwise. A
   * participant's first answer records `$experiment_entered`, once per unit per
   * experiment, and later events carry the variant. `completion` runs on the
   * engine's executor.
   */
  fun getExperiment(name: String, completion: (TogoExperiment) -> Unit) {
    if (disabled) return completion(TogoExperiment.unavailable(name))
    assignments.get(name, completion)
  }

  /** The stored answer, now, with no network: `null` when this device has none. Records the exposure. */
  fun cachedExperiment(name: String): TogoExperiment? = if (disabled) null else assignments.cached(name)

  /** Fetch these names without recording an exposure. */
  fun preloadExperiments(names: List<String>) {
    if (!disabled) assignments.preload(names)
  }

  /** Logout: a new anonymous visitor and a new session; the device id stays. */
  fun reset() {
    loadIfPermitted()
    resetIdentity()
  }

  fun setConsent(next: TogoConsent) {
    val from = consent
    if (from == next) return
    consent = next
    if (next == TogoConsent.DENIED) {
      // Delete the stored queue before anything can read it.
      queue.clear()
      held.clear()
      // Withdrawal DELETES, it does not merely stop — from any state. Ids
      // written while consent was permitted are still on the device after a
      // pass through `pending`, and deleting is not writing, so it needs no
      // permission of its own.
      identityStore.erase()
      assignments.clear()
      record = IdentityRecord()
      loaded = true
      sessions.forget()
      // The record that said "installed" is gone, and keeping a marker would be
      // storage the user just refused: a later grant is decided as a launch
      // with no record.
      foundNoRecord = true
      // The signed-in user goes too. Kept in memory, a later grant in this
      // process would write it back to the device and stamp it on every new
      // event — linking traffic after the withdrawal to the person before it.
      // The app identifies again after a new grant if it wants to.
      userId = null
    }
    // A userId asserted while writes were not permitted is persisted at the
    // first moment they are, like the ids minted then.
    val user = userId
    if (user != null && consent.permitted) {
      loadIfPermitted()
      record = record.copy(userId = user)
      save()
    }
    if (next.permitted) {
      // After a withdrawal, the grant is the launch, at the grant's time —
      // whether it comes straight from denied or by way of pending: the record
      // is gone, and the launch decision clears this once it is made.
      if (foundNoRecord && reportsLaunches && launchOwedAtMs == null) launchOwedAtMs = clock.wallMs()
      decideLaunch()
      releaseHeld()
    }
  }

  /** Send what is queued now. `completion` runs after one upload attempt; a failure stays queued for the retry. */
  fun flush(completion: (() -> Unit)? = null) {
    cancelTimer?.invoke()
    cancelTimer = null
    // While the identity cannot be read (before the first unlock), neither can
    // the queue, and reading it as empty would let the next write replace it.
    loadIfPermitted()
    catchUp()
    if (disabled || !consent.permitted || !loaded || inFlight) {
      // In flight: that upload's answer schedules the next one.
      completion?.invoke()
      return
    }
    queue.hydrate()
    val entries = queue.take(options.batchSize, clock.wallMs())
    val first = entries.firstOrNull()
    if (first == null) {
      completion?.invoke()
      return
    }
    // Stamped HERE, at upload: the device clock at the moment the edge can
    // measure its offset. A retry gets a fresh reading.
    val batch = RawBatch(
      clientSentMs = clock.wallMs(), deviceIdMode = first.deviceIdMode, sessionIdMode = sessions.mode,
      app = first.app, device = first.device, events = entries.map { it.event },
    )
    val body = batch.json().serialized().toByteArray(Charsets.UTF_8)
    val url = try {
      URL(options.endpoint.trimEnd('/') + "/v1/events")
    } catch (_: java.net.MalformedURLException) {
      queue.retryInFlight()
      disable(
        "endpoint is not a URL (${options.endpoint}). No events will be sent until the app restarts; " +
          "queued events are kept on this device.",
        TogoErrorCode.ENDPOINT_INVALID,
      )
      completion?.invoke()
      return
    }
    inFlight = true
    transport.post(url, options.writeKey, body) { response ->
      executor.execute {
        inFlight = false
        handle(response, entries)
        completion?.invoke()
      }
    }
  }

  /** Read-only, never mints. */
  val anonymousId: String?
    get() {
      loadIfPermitted()
      return if (consent.permitted) record.anonymousId else null
    }

  val deviceId: String?
    get() {
      loadIfPermitted()
      return if (consent.permitted) record.deviceId else null
    }

  val sessionId: String?
    get() {
      loadIfPermitted()
      if (!consent.permitted) return null
      return sessions.current(record.session, clock.wallMs(), clock.monotonicMs())
    }

  /**
   * Stop for the rest of this process: one error, every later call a no-op. The
   * queue is KEPT — a 401 can be a key minted seconds ago that has not reached
   * the edge yet — and retried at the next launch.
   */
  fun disable(message: String, code: TogoErrorCode? = null) {
    if (disabled) return
    disabled = true
    if (code != null) report(code, message) else log.log(message)
    cancelTimer?.invoke()
    cancelTimer = null
  }

  /**
   * The identity store can be read again (the device was unlocked): load,
   * decide the launch, and send what was held, under the stored ids.
   */
  fun identityBecameAvailable() {
    // A withdrawal made while locked could not delete the files; it can now.
    identityStore.retryErase()
    queue.retryRemove()
    if (!loaded) loadIfPermitted()
    if (!consent.permitted) return
    decideLaunch()
    releaseHeld()
  }

  // The app's lifecycle.

  /**
   * The process started. Owes the launch's `$app_installed` or
   * `$app_updated`, sent as soon as the identity record can be read — now, or
   * when consent permits, or when the device is unlocked. Starts no session: a
   * process starts for a broadcast, a job or a push as often as for a person.
   */
  fun start() {
    reportsLaunches = true
    launchOwedAtMs = clock.wallMs()
    if (!consent.permitted) return
    decideLaunch()
    releaseHeld()
  }

  /**
   * The app came to the foreground with nothing in front before ([AppLifecycle]).
   * The event a person is present for, so it opens a session when none is
   * open, or continues the one inside the window.
   */
  fun appOpened(fromBackground: Boolean) {
    submit(Wire.appOpenedEvent, mapOf("from_background" to JsonValue.Bool(fromBackground)), null, options.timezone)
    // Every foreground refreshes the stored answers: a finished experiment ends
    // its stamp here, and the next session gets a changed answer.
    if (!disabled) assignments.appOpened(options.experiments)
  }

  /**
   * The app went to the background: background time is idle time, so the
   * session's `lastActivityAt` is now, and it is written at once, since a
   * backgrounded process can be killed with no callback. Then the queue is
   * flushed; `completion` runs after that attempt.
   */
  fun appBackgrounded(completion: (() -> Unit)? = null) {
    loadIfPermitted()
    val session = record.session
    if (consent.permitted && loaded && session != null) {
      val marked = sessions.markActivity(session, clock.wallMs(), clock.monotonicMs())
      if (marked != null) {
        record = record.copy(session = marked)
        save()
      }
    }
    identityStore.persistNow()
    flush(completion)
  }

  /**
   * The record reads: the launch's decision and what was held go now, in that
   * order. Whatever made it readable — the unlock signal, or an event or a
   * flush that found it readable first (a read that failed for another reason,
   * a signal still on its way) — nothing waits for a later signal that may
   * never come.
   */
  private fun catchUp() {
    if (!consent.permitted || !loaded) return
    decideLaunch()
    releaseHeld()
  }

  /**
   * Once per process (and again after a withdrawal), at the first moment the
   * record reads:
   *
   * - no record, and the package was installed within [INSTALL_WINDOW_MS] of
   *   the launch (or the platform cannot say when) → `$app_installed`;
   * - no record, and the install is older → nothing: an existing user's first
   *   launch of the release that added the SDK, or data cleared, and counting
   *   either as an install is a spike of installs that never happened;
   * - a stored version or build that differs → `$app_updated` with the
   *   previous ones;
   * - otherwise nothing.
   *
   * The event takes the launch's time and goes FIRST among what is held, so it
   * precedes what the app did on that launch.
   */
  private fun decideLaunch() {
    val launchedAtMs = launchOwedAtMs ?: return
    if (disabled || !consent.permitted) return
    loadIfPermitted()
    if (!loaded) return // Unreadable: decide after the unlock.
    launchOwedAtMs = null
    val previousVersion = record.appVersion
    val previousBuild = record.appBuild
    val current = app
    var event: Pair<String, Props>? = null
    if (foundNoRecord) {
      val installedAt = options.firstInstallMs
      if (installedAt == null || launchedAtMs - installedAt <= INSTALL_WINDOW_MS) {
        event = Wire.appInstalledEvent to emptyMap()
      }
    } else if ((previousVersion != null || previousBuild != null) &&
      (previousVersion != current?.version || previousBuild != current?.build)
    ) {
      val properties = LinkedHashMap<String, JsonValue>()
      previousVersion?.let { properties["previous_app_version"] = JsonValue.Str(it) }
      previousBuild?.let { properties["previous_app_build"] = JsonValue.Str(it) }
      event = Wire.appUpdatedEvent to properties
    }
    // An old install with no record sends nothing, and its version is
    // recorded at once, even if this process mints nothing: the next update
    // must be detected.
    val silentFirstLaunch = foundNoRecord && event == null
    foundNoRecord = false
    if (previousVersion != current?.version || previousBuild != current?.build) {
      record = record.copy(appVersion = current?.version, appBuild = current?.build)
      // A new install writes at the first minted id, never a record holding
      // nothing but a version: that would read as "installed" next launch even
      // if no `$app_installed` was ever sent.
      if (record.deviceId != null || silentFirstLaunch) save()
    }
    val (name, properties) = event ?: return
    held.add(
      0,
      HeldEvent(name, properties, launchedAtMs, randomId(), userId, null, options.timezone, app, device),
    )
  }

  /** What is held in memory, for a test. */
  val heldCount: Int get() = held.size

  /** What is queued on the device, for a test. */
  val queuedCount: Int
    get() {
      queue.hydrate()
      return queue.count
    }

  // The gate.

  private fun values(eventName: String, properties: Map<String, Any?>?): Props =
    toProps(properties) { key, value ->
      val message = if (key == null) {
        "\"$eventName\" has a property with a null key, which was not sent: a key must be a string."
      } else {
        "\"$eventName\" property \"$key\" was not sent: it is ${typeName(value)}, and a " +
          "property value must be a string, a number, a boolean or null."
      }
      report(TogoErrorCode.PROPERTY_VALUE_INVALID, message, emptyList(), eventName, key?.let(Names::shown))
    }

  /**
   * The one gate every event goes through, ours and the customer's: held
   * (`pending`, or identity unreadable), dropped (`denied`), or stamped and queued.
   */
  private fun submit(
    eventName: String,
    sent: Props,
    sentPerson: PersonPropertyOps?,
    timezone: String?,
    drops: List<Pair<String, String>> = emptyList(),
  ): String? {
    if (disabled) return null
    // Before the event takes its userId: after a relaunch the signed-in user
    // is in the stored record, and an event stamped first would carry none.
    loadIfPermitted()
    // What waited for the record goes before this event, as it happened before it.
    catchUp()
    // An over-long value: cut under a live key, sent whole under a test key
    // for the inspector to show the server's cut (`ValueCaps`), and reported
    // with the id of the event that was kept.
    val cuts = ArrayList<Triple<String, String, String>>()
    val cap = { values: Props? ->
      valueCaps.cap(eventName, values) { once, property, message -> cuts.add(Triple(once, property, message)) }
    }
    val properties = cap(sent) ?: emptyMap()
    val personProperties = sentPerson?.let { it.copy(set = cap(it.set), setOnce = cap(it.setOnce)) }
    val heldEvent = HeldEvent(
      eventName, properties, clock.wallMs(), randomId(), userId, personProperties, timezone, app, device,
      inheritsStoredUser = !loaded && userId == null && identityCalls == 0,
    )
    // Denied is a choice: nothing is sent, so nothing is reported.
    if (consent != TogoConsent.PENDING && !consent.permitted) return null
    // The drops the caller collected past 256 properties, claimed like a cut.
    for ((once, message) in drops) {
      if (!valueCaps.claim(once)) continue
      report(TogoErrorCode.ENVELOPE_PROPERTIES_TOO_MANY, message, listOf(heldEvent.eventId), eventName)
    }
    for ((once, property, message) in cuts) {
      if (!valueCaps.claim(once)) continue
      report(TogoErrorCode.ENVELOPE_PROPERTY_VALUE_TOO_LONG, message, listOf(heldEvent.eventId), eventName, property)
    }
    if (consent == TogoConsent.PENDING) return hold(heldEvent)
    val ids = identity() ?: return hold(heldEvent)
    val event = stamp(heldEvent, ids, null)
    if (!enqueue(event, app, device)) return null
    return event.eventId
  }

  /** Hold with no identifier attached. Oldest dropped past the bound. */
  private fun hold(event: HeldEvent): String {
    held.add(event)
    if (held.size > options.consentBufferMaxEvents) {
      val evicted = held.removeAt(0)
      report(
        TogoErrorCode.QUEUE_FULL,
        "\"${evicted.eventName}\" dropped: over consentBufferMaxEvents awaiting consent",
        listOf(evicted.eventId), evicted.eventName,
      )
    }
    return event.eventId
  }

  private data class Ids(val deviceId: String, val anonymousId: String)

  /**
   * Release what was held, under the identifiers minted now. One session for
   * the whole buffer: the events are contiguous by construction, and cutting
   * them by a clock that was not running would be an invention. A new session's
   * `$session_started` takes the first held event's time.
   *
   * **An event that inherits the stored user keeps the stored anonymous id
   * too**, once a logout or a different user's `identify()` has replaced it.
   * Under the new anonymous id it would carry the last user's `userId` next to
   * the id of whoever came after, and link the two people. It goes with no
   * session: the stored one was that user's, and the new one is the next
   * person's.
   */
  private fun releaseHeld() {
    if (held.isEmpty()) return
    val ids = identity() ?: return
    val events = ArrayList(held)
    held.clear()
    val storedAnonymous = storedAnonymousId
    val previousIds = if (storedAnonymous != null && storedAnonymous != ids.anonymousId) {
      Ids(ids.deviceId, storedAnonymous)
    } else {
      null
    }
    var sessionId: String? = null
    var touched = false
    for (event in events) {
      if (previousIds != null && event.inheritsStoredUser) {
        enqueue(stamp(event, previousIds, null to storedUserId), event.app, event.device)
        continue
      }
      if (!touched) {
        touched = true
        sessionId = touchSession { id ->
          sessionStarted(id, event.clientEventMs, heldUser(event), ids, event.app, event.device)
        }
      }
      enqueue(stamp(event, ids, sessionId to heldUser(event)), event.app, event.device)
    }
  }

  /**
   * The user a held event belongs to: the one asserted at the call, or — for an
   * event held before the record was read and before any `identify()` or
   * `reset()` — the user the record says was signed in when it was read.
   */
  private fun heldUser(event: HeldEvent): String? = if (event.inheritsStoredUser) storedUserId else event.userId

  private fun stamp(event: HeldEvent, ids: Ids, released: Pair<String?, String?>?): RawEvent {
    val eventUserId = if (released != null) released.second else event.userId
    val sessionId = if (released != null) {
      released.first
    } else {
      touchSession { id -> sessionStarted(id, event.clientEventMs, eventUserId, ids, event.app, event.device) }
    }
    return RawEvent(
      eventName = event.eventName, clientEventMs = event.clientEventMs, eventId = event.eventId,
      anonymousId = ids.anonymousId, deviceId = ids.deviceId, userId = eventUserId, sessionId = sessionId,
      properties = event.properties, personProperties = event.personProperties, timezone = event.timezone,
      sdkName = options.sdk.name, sdkVersion = options.sdk.version,
      // Stamped now, so a queued event keeps it. A released event has none: it
      // was held because nothing could be read or stored yet.
      experiments = if (released == null) assignments.stampFor(event.eventName) else null,
    )
  }

  /** `$session_started`, queued immediately before the event that opened the session, with that event's time. */
  private fun sessionStarted(sessionId: String, occurredMs: Long, userId: String?, ids: Ids, app: AppInfo?, device: DeviceInfo?) {
    val started = RawEvent(
      eventName = Wire.sessionStartedEvent, clientEventMs = occurredMs, eventId = randomId(),
      anonymousId = ids.anonymousId, deviceId = ids.deviceId, userId = userId, sessionId = sessionId,
      properties = emptyMap(), personProperties = null, timezone = options.timezone,
      sdkName = options.sdk.name, sdkVersion = options.sdk.version,
    )
    enqueue(started, app, device)
  }

  private fun touchSession(onStart: (String) -> Unit): String? {
    val result = sessions.touch(record.session, clock.wallMs(), clock.monotonicMs(), humanPresent(), ::randomId)
    if (result.record != record.session) {
      record = record.copy(session = result.record)
      save()
    }
    if (result.started && result.id != null) onStart(result.id)
    return result.id
  }

  /**
   * Queue one event, unless it is over 256 KiB as UTF-8 JSON: then it is dropped here, never sent,
   * and reported under every key — an event that large is a payload, not a fact about what a
   * person did. False when it was not queued.
   */
  private fun enqueue(event: RawEvent, app: AppInfo?, device: DeviceInfo?): Boolean {
    if (disabled) return false
    val entry = EventQueue.Entry(event, app, device, record.deviceIdMode)
    // `eventBytes` counts the line's newline too.
    if (entry.eventBytes - 1 > Wire.maxEventBytes) {
      report(
        TogoErrorCode.EVENT_TOO_LARGE,
        "\"${event.eventName}\" was not sent: its JSON is ${entry.eventBytes - 1} bytes, over the " +
          "${Wire.maxEventBytes}-byte limit. Send only the few values a chart needs, not a whole object.",
        listOf(event.eventId),
        event.eventName,
      )
      return false
    }
    queue.hydrate()
    queue.add(entry)
    // A full batch goes at once — unless an upload is in flight or a retry is
    // waiting out its backoff. Then the backlog waits for that, or every
    // track() during an outage would be a request, and every device with a
    // backlog would hit the edge together when it recovered.
    if (queue.count >= options.batchSize && attempt == 0 && !inFlight) {
      flush()
    } else {
      scheduleFlush(options.flushIntervalSeconds)
    }
    return true
  }

  // Identity.

  /**
   * Read the identity record at the first moment consent permits it. A read is
   * not a write, but under `pending` the SDK does not touch the device at all.
   */
  private fun loadIfPermitted() {
    if (loaded || !consent.permitted) return
    when (val result = identityStore.load()) {
      // Try again later; never mint over an unreadable record.
      IdentityLoad.Unavailable -> return
      IdentityLoad.Absent -> {
        noteStored(null)
        loaded = true
        foundNoRecord = true
        logoutBeforeLoad = false
        identifiedBeforeLoad = false
      }
      is IdentityLoad.Found -> {
        loaded = true
        val stored = result.record
        noteStored(stored)
        record = stored
        // What the app said while the record could not be read is applied to
        // the record now, as if it had been read then. A logout ends the
        // stored user, its anonymous id and its session; a different user
        // identified is a missed logout, the same as identify() on a read
        // record. Without this the stored user came back at the unlock and
        // the next person's events went out under it.
        val differentUser = identifiedBeforeLoad && stored.userId != null && stored.userId != userId
        if (logoutBeforeLoad || differentUser) {
          record = stored.copy(userId = null, anonymousId = null, session = null)
        } else if (userId == null) {
          userId = stored.userId
        }
        logoutBeforeLoad = false
        identifiedBeforeLoad = false
        if (record != stored) save()
      }
    }
  }

  /**
   * The device and anonymous ids, minted LAZILY at the first permitted write.
   * `null` while the record is unreadable or the device id is not available
   * yet — the caller holds the event rather than inventing an id.
   */
  private fun identity(): Ids? {
    if (!consent.permitted) return null
    loadIfPermitted()
    if (!loaded) return null
    var next = record
    if (next.deviceId == null) {
      val minted = deviceIds.deviceId() ?: return null
      next = next.copy(deviceId = minted.id, deviceIdMode = minted.mode)
    }
    if (next.anonymousId == null) next = next.copy(anonymousId = randomId())
    val user = userId
    if (user != null && next.userId != user) next = next.copy(userId = user)
    if (next != record) {
      record = next
      save()
    }
    return Ids(next.deviceId!!, next.anonymousId!!)
  }

  /**
   * Keep the device id, rotate the anonymous id, end the session, forget the
   * user. Nothing is minted for the session: the NEXT event opens it.
   */
  private fun resetIdentity() {
    identityCalls++
    userId = null
    // The record has not been read — the store is unavailable, or consent
    // withholds reading it — so the logout is remembered and applied to the
    // record once it is.
    if (!loaded) {
      logoutBeforeLoad = true
      identifiedBeforeLoad = false
    }
    sessions.forget()
    assignments.forgetIdentity()
    // In memory first, whatever consent says: a grant after this must mint a
    // new anonymous id and open a new session, never reuse the last user's.
    record = record.copy(userId = null, session = null)
    if (consent.permitted && loaded) {
      record = record.copy(anonymousId = randomId())
      save()
      return
    }
    // Not permitted to write: forget the anonymous id (the next permitted
    // write mints one), and REMOVE what is stored that would put the next
    // person's events on this user. Removing is not writing.
    record = record.copy(anonymousId = null)
    val stored = identityStore.load()
    if (stored is IdentityLoad.Found) {
      noteStored(stored.record)
      identityStore.save(stored.record.copy(userId = null, anonymousId = null, session = null))
    }
  }

  private fun noteStored(stored: IdentityRecord?) {
    if (storedUserRead) return
    storedUserRead = true
    storedUserId = stored?.userId
    storedAnonymousId = stored?.anonymousId
  }

  /**
   * Only a record that was READ may be written back. While the store is
   * unavailable `record` is a blank stand-in, and saving it would replace the
   * stored device and anonymous ids with nothing — a second identity for the
   * same install once the device unlocks. What changed meanwhile, a userId, is
   * in memory and reaches the record at the next permitted write after the load.
   */
  private fun save() {
    if (!loaded) return
    identityStore.save(record)
  }

  // Upload outcomes.

  private fun scheduleFlush(seconds: Double) {
    if (cancelTimer != null) return
    cancelTimer = executor.after(seconds) {
      cancelTimer = null
      flush()
    }
  }

  /**
   * Exponential backoff with JITTER, so every device that queued through one
   * outage does not retry on the same schedule into the recovery.
   */
  private fun backoffSeconds(): Double {
    val exponential = min(options.flushIntervalSeconds * 2.0.pow(attempt), MAX_BACKOFF_SECONDS)
    return exponential * (0.5 + Random.nextDouble(0.0, 0.5))
  }

  private fun handle(response: TransportResponse, entries: List<EventQueue.Entry>) {
    val ids = entries.map { it.event.eventId }
    fun retry(seconds: Double) {
      // Back in the queue — unless `setConsent(DENIED)` cleared it while the
      // upload was out, which `retryInFlight` sees for itself.
      queue.retryInFlight()
      if (!consent.permitted) return
      attempt += 1
      if (attempt <= MAX_FLUSH_ATTEMPTS) scheduleFlush(seconds)
    }
    val failure = response.failure
    if (failure != null) {
      // A refusal no retry can change. Said, once, with the fix — a retry loop
      // would stay silent until the seven-day bound dropped every event — and
      // the queue is kept for a build that can reach the endpoint.
      queue.retryInFlight()
      disable(
        "events cannot be sent to ${options.endpoint}: $failure. No more events will be sent until " +
          "the app restarts; ${entries.size} queued event(s) are kept on this device. An http:// " +
          "endpoint needs cleartext allowed for its host in the app's network security config; " +
          "the default endpoint is https.",
        TogoErrorCode.ENDPOINT_INVALID,
      )
      return
    }
    val status = response.status
    if (status == null) {
      // Offline is normal operation, so not an error: re-queue, retry later.
      retry(backoffSeconds())
      return
    }
    if (status == 429) {
      retry(response.retryAfterSeconds?.takeIf { it > 0 } ?: backoffSeconds())
      return
    }
    if (status >= 500) {
      // EVERY 5xx, not only 503, and with the same eventIds so the edge dedupes
      // a batch that did land: the edge answers 500 when a failure is above our
      // code, and retrying only 503 would drop those events.
      retry(backoffSeconds())
      return
    }
    if (status != 202 && status !in 400..499) {
      // Not an answer from the edge, which only ever says 202, 4xx or 5xx: a
      // redirect (a custom endpoint behind http→https, a proxy that moved), a
      // captive portal's 200 with its own page, or something stranger. Never
      // read as delivered — that would drop the batch with no error anywhere,
      // and a portal would swallow the whole backlog behind it. Kept and
      // retried, and said once, since a retry only helps if the cause was
      // transient.
      if (!warnedUnexpectedStatus) {
        warnedUnexpectedStatus = true
        log.log(
          "the ingest endpoint answered $status, not 202; ${entries.size} event(s) are kept and " +
            "retried. Point endpoint at the URL that answers, not one that redirects.",
        )
      }
      retry(backoffSeconds())
      return
    }
    attempt = 0
    if (status == 401) {
      // Kept for the next launch.
      queue.retryInFlight()
      disable(
        "the write key was rejected (401). No more events will be captured until the app " +
          "restarts; ${entries.size} queued event(s) are kept on this device and retried then. " +
          "Check the key.",
        TogoErrorCode.WRITE_KEY_REJECTED,
      )
      return
    }
    // Answered: delivered (202) or refused for good (413, another 4xx). Out of the file.
    queue.acknowledge()
    when {
      status == 413 -> report(
        TogoErrorCode.BATCH_TOO_LARGE,
        "the ingest endpoint refused a batch as too large (413); ${entries.size} event(s) dropped. " +
          "Usually one very large property value: check what you pass to track().",
        ids,
      )
      status >= 400 -> report(
        TogoErrorCode.BATCH_REJECTED,
        "a batch was rejected ($status); ${entries.size} event(s) dropped. A bug in sdk-android, " +
          "not in your code.",
        ids,
      )
      else -> surfaceRejections(response.body, entries)
    }
    // Whatever the answer, the rest of the backlog goes next. flush() cancelled
    // the pending timer when it started, and a timer that fired while this
    // upload was out found it in flight and did nothing, so nothing else will:
    // without this, what is left waits for the app's next call, which after a
    // background start can be days — past the seven-day bound.
    if (queue.count > 0) scheduleFlush(0.0)
  }

  /**
   * The per-event refusals a 202 can carry. These events are GONE, and `track()`
   * never throws, so this is the only notice anyone gets.
   */
  private fun surfaceRejections(body: ByteArray?, entries: List<EventQueue.Entry>) {
    if (body == null) return
    val json = try {
      JsonValue.parse(body) as? JsonValue.Obj
    } catch (_: JsonParseException) {
      null
    } ?: return
    val rejected = json["rejected"] as? JsonValue.Arr ?: return
    for (item in rejected.values) {
      val o = item as? JsonValue.Obj ?: continue
      val eventId = o.string("eventId") ?: continue
      val reason = o.string("reason") ?: continue
      val name = entries.firstOrNull { it.event.eventId == eventId }?.event?.eventName
      report(
        TogoErrorCode(reason),
        "\"${name ?: "?"}\" was rejected ($reason): https://togohq.ai/docs/reference/event-diagnostics#$reason",
        listOf(eventId), name,
      )
    }
  }

  /**
   * Log, then tell `onError` — after the call that caused it returns, on the
   * callback executor, so a handler that calls back into the SDK cannot
   * deadlock it.
   */
  private fun report(
    code: TogoErrorCode,
    message: String,
    eventIds: List<String> = emptyList(),
    eventName: String? = null,
    property: String? = null,
  ) {
    log.log(message)
    val onError = options.onError ?: return
    val error = TogoError(code, message, eventIds, eventName, property)
    callbacks.execute { onError(error) }
  }

  companion object {
    /**
     * Past this many consecutive automatic retries the SDK stops rescheduling.
     * Not a retention limit: the events stay queued for the next `track()`,
     * `flush()` or launch.
     */
    const val MAX_FLUSH_ATTEMPTS = 8
    const val MAX_BACKOFF_SECONDS = 300.0
    /** How recent an install must be for a launch with no identity record to be one: seven days. */
    const val INSTALL_WINDOW_MS = 7L * 86_400_000L
  }
}
