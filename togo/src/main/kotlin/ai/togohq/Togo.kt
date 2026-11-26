package ai.togohq

import ai.togohq.core.AppLifecycle
import ai.togohq.core.Engine
import ai.togohq.core.EngineOptions
import ai.togohq.core.FileIdentityStore
import ai.togohq.core.FileQueueStore
import ai.togohq.core.HttpTransport
import ai.togohq.core.IdentityStore
import ai.togohq.core.Logger
import ai.togohq.core.QueueStore
import ai.togohq.core.SDK_NAME
import ai.togohq.core.SdkIdentity
import ai.togohq.core.SerialExecutor
import ai.togohq.core.TogoExecutor
import ai.togohq.core.Transport
import ai.togohq.core.Wire
import ai.togohq.core.WriteKeys
import ai.togohq.core.toProps
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * The Togo AI Android SDK: the same surface as the web, server and iOS SDKs.
 *
 * ```kotlin
 * class App : Application() {
 *   override fun onCreate() {
 *     super.onCreate()
 *     Togo.setup(this, TogoConfig.Builder(if (BuildConfig.DEBUG) "togo_pkt_…" else "togo_pkl_…").build())
 *   }
 * }
 * Togo.track("plan_selected", mapOf("plan_id" to "pro"))
 * ```
 *
 * **Every method can be called from any thread and never blocks on the
 * network.** Calls are queued, in order, onto one thread that owns the SDK's
 * state; the getters wait only for the calls made before them. No method
 * throws: a refusal is a `[togo]` log line and an `onError` call.
 */
public object Togo {
  private val lock = Any()
  private var engine: Engine? = null
  private var executor: SerialExecutor? = null
  private var callbacks: SerialExecutor? = null
  private var warnedNotSetUp = false
  /** `setup()` ran in a process that is not the app's main one: every call is a no-op, silently. */
  private var inert = false
  internal var log: Logger = Logger { message -> Log.i("togo", "[togo] $message") }

  /**
   * Configure the SDK, once, in `Application.onCreate()` — before a consent
   * decision exists if need be (`consent(TogoConsent.PENDING)`). Stores nothing
   * and sends nothing until consent permits, and never starts a session:
   * `onCreate()` runs for a broadcast or a job as often as for a person. In any
   * process but the app's main one it does nothing, and says so once.
   */
  @JvmStatic
  public fun setup(context: Context?, config: TogoConfig?) {
    // Every parameter on this object is nullable on purpose: a Java caller can
    // pass null, and a non-null Kotlin parameter would throw a
    // NullPointerException on the caller's thread — a crash, where the promise
    // is that no call throws. Each null is refused the way an empty value is.
    if (context == null || config == null) {
      log.log("Togo.setup() was given a null ${if (context == null) "context" else "config"} and did nothing.")
      return
    }
    setup(context, config, Processes.currentName()) { executor -> Environment.device(context, executor) }
  }

  /** Send an event. The name is stored as given and becomes queryable by its registry key. */
  @JvmStatic
  @JvmOverloads
  public fun track(eventName: String?, properties: Map<String, Any?>? = null) {
    val copy = properties?.let { HashMap(it) }
    // A null name is refused as an empty one: event_name_empty, through onError.
    client { it.track(eventName ?: "", copy) }
  }

  /** The signed-in user: your opaque account id, never an email address. A different id first does what [reset] does. */
  @JvmStatic
  public fun identify(userId: String?) {
    // A null id is refused as an empty one: user_id_invalid, and nothing changes.
    client { it.identify(userId ?: "") }
  }

  /** Update the person's profile: `email` and `name` are Togo's typed fields; `set` takes your own keys. */
  @JvmStatic
  @JvmOverloads
  public fun setPersonProperties(set: Map<String, Any?>? = null, email: String? = null, name: String? = null) {
    val copy = set?.let { HashMap(it) }
    client { it.setPersonProperties(email = email, name = name, set = copy) }
  }

  /** The whole profile update, with `setOnce`, `unset` and removals. */
  @JvmStatic
  public fun setPersonProperties(update: TogoPersonProperties?) {
    if (update == null) {
      log.log("Togo.setPersonProperties() was given null and sent nothing.")
      return
    }
    client {
      it.setPersonProperties(
        email = update.email, name = update.name, set = update.set, setOnce = update.setOnce,
        unset = update.unset, removeEmail = update.removeEmail, removeName = update.removeName,
      )
    }
  }

  /** Call at logout. A new anonymous visitor and a new session on this device; the device id stays. */
  @JvmStatic
  public fun reset() {
    client { it.reset() }
  }

  /** Idempotent, callable at any time. GRANTED after PENDING sends what was held; DENIED deletes what is stored. */
  @JvmStatic
  public fun setConsent(consent: TogoConsent?) {
    if (consent == null) {
      log.log("Togo.setConsent() was given null and changed nothing.")
      return
    }
    client { it.setConsent(consent) }
  }

  @JvmStatic
  public fun getConsent(): TogoConsent = read(TogoConsent.NOT_REQUIRED) { it.consent }

  /** Read-only, never mints: `null` before the first event consent permits. */
  @JvmStatic
  public fun getAnonymousId(): String? = read(null) { it.anonymousId }

  @JvmStatic
  public fun getDeviceId(): String? = read(null) { it.deviceId }

  /** Read-only, never mints: `null` when there is no session. */
  @JvmStatic
  public fun getSessionId(): String? = read(null) { it.sessionId }

  /**
   * Send what is queued now. `callback` runs after the attempt, on the SDK's
   * callback thread, so it may call any `Togo` method; a failure stays queued
   * and is retried.
   */
  @JvmStatic
  @JvmOverloads
  public fun flush(callback: FlushCallback? = null) {
    val (engine, executor, callbacks) = synchronized(lock) { Triple(engine, executor, callbacks) }
    if (engine == null || executor == null || callbacks == null) {
      callback?.onComplete()
      return
    }
    val done: (() -> Unit)? = callback?.let { { callbacks.execute { it.onComplete() } } }
    executor.execute { engine.flush(done) }
  }

  // Below: the device, and what a test replaces.

  /**
   * What the client stores to and sends through, and what tells it about the
   * app. A test of the rest of the surface passes memory stores, a fake network
   * and no lifecycle.
   */
  internal class Environment(
    val identity: IdentityStore,
    val queue: QueueStore,
    val transport: Transport,
    /** When the package was installed: the input of the install rule. */
    val firstInstallMs: Long? = null,
    /**
     * Starts what sends the app's lifecycle into [Signals]: the activity
     * callbacks and the unlock receiver. Called once, AFTER `setup()` has
     * released its lock, since it may signal at once. `null` sends no
     * lifecycle event at all, and every event may open a session.
     */
    val observe: ((Signals) -> Unit)? = null,
  ) {
    companion object {
      /**
       * Two files in `noBackupFilesDir`, which Auto Backup never copies whatever
       * the app's backup rules say, so neither follows a backup onto other
       * hardware: the identity and the queue. While the device is locked after
       * a reboot neither is read, and nothing is minted.
       */
      fun device(context: Context, executor: TogoExecutor, transport: Transport = HttpTransport()): Environment {
        val app = context.applicationContext ?: context
        val directory = File(app.noBackupFilesDir, "togo")
        val unlock = UnlockWatcher(app)
        return Environment(
          FileIdentityStore(File(directory, "identity.json"), executor, { UnlockWatcher.userUnlocked(app) }, log),
          FileQueueStore(File(directory, "queue.jsonl")),
          transport,
          Platform.firstInstallMs(app),
          observe = { signals ->
            val application = app as? Application
            if (application == null) {
              log.log("Togo.setup() was given a context with no Application, so no lifecycle event is sent.")
            } else {
              val callbacks = ActivityCallbacks(MainThreadScheduler(), signals, unlock) { log.log(it) }
              application.registerActivityLifecycleCallbacks(callbacks)
              // An activity already in front is seen by no callback. Off the
              // main thread — a Solar2D game's setup, on its engine thread —
              // ask now, so the first event after setup() has its session.
              // On it, ask after what the main thread has queued: in
              // Application.onCreate() the launch's activity starts there,
              // is counted, and nothing is seeded, as before.
              val seed = { callbacks.seedIfInFront { Processes.inFront(application) } }
              if (Looper.myLooper() == Looper.getMainLooper()) Handler(Looper.getMainLooper()).post { seed() } else seed()
            }
            unlock.start(signals.unlocked)
          },
        )
      }
    }
  }

  /** A test's setup: its environment, in the main process. */
  internal fun setup(context: Context, config: TogoConfig, environment: Environment) =
    setup(context, config, Processes.mainName(context)) { environment }

  internal fun setup(context: Context, config: TogoConfig, processName: String?, environment: (TogoExecutor) -> Environment) {
    var start: (() -> Unit)? = null
    synchronized(lock) {
      start = configure(context, config, processName, environment)
    }
    // Outside the lock: what starts the lifecycle may signal at once, and a
    // signal — or a callback it reaches — takes this lock. The iOS SDK's first
    // launch hung on exactly this.
    start?.invoke()
  }

  /** Everything `setup()` does under the lock. Returns what starts the lifecycle's signals, when there is one. */
  private fun configure(
    context: Context,
    config: TogoConfig,
    processName: String?,
    makeEnvironment: (TogoExecutor) -> Environment,
  ): (() -> Unit)? {
    if (engine != null || inert) {
      log.log("Togo.setup() was called again and ignored: the first configuration stands.")
      return null
    }
    if (!Processes.isMain(context, processName)) {
      inert = true
      log.log(
        "Togo.setup() ran in the process \"$processName\", not the app's main process " +
          "(\"${Processes.mainName(context)}\"); the SDK does nothing in this process. Two processes would " +
          "write one identity file and one queue, so only the main process sends events.",
      )
      return null
    }
    val app = context.applicationContext ?: context
    val executor = SerialExecutor("togo")
    val environment = makeEnvironment(executor)
    val callbacks = SerialExecutor("togo-callbacks")
    val log = log
    val onError = config.onError
    val options = EngineOptions(
      writeKey = config.writeKey,
      endpoint = config.endpoint,
      sessionTimeoutSeconds = config.sessionTimeoutSeconds.toDouble(),
      consent = config.consent,
      consentBufferMaxEvents = config.consentBufferMaxEvents,
      defaultProperties = toProps(config.defaultProperties) { key, _ ->
        log.log("defaultProperties \"$key\" was not used: a key is a string, and a value a string, a number, a boolean or null.")
      },
      app = Platform.app(app, config.appVersion, config.appBuild),
      device = Platform.device(app),
      timezone = Platform.timezone(),
      onError = onError?.let { listener -> { error: TogoError -> listener.onError(error) } },
      sdk = sdkIdentity(config),
      firstInstallMs = environment.firstInstallMs,
    )
    val problem = WriteKeys.problem(config.writeKey)
    val lifecycle = environment.observe?.let { AppLifecycle() }
    val created = executor.await {
      Engine(
        options, environment.identity, queueStore = environment.queue, transport = environment.transport,
        executor = executor, callbacks = callbacks, clock = AndroidClock, log = log,
        humanPresent = if (lifecycle == null) ({ true }) else ({ lifecycle.isForeground }),
      ).also { engine ->
        // A malformed key is a typo or an unsubstituted build setting: the
        // client is inert with one error that says why, never a crash.
        if (problem != null) engine.disable(problem, TogoErrorCode.WRITE_KEY_INVALID)
        // The launch: its install or update decision, and no session.
        lifecycle?.launched(engine)
      }
    }
    engine = created
    this.executor = executor
    this.callbacks = callbacks
    val observe = environment.observe ?: return null
    val signals = Signals(
      foreground = { executor.execute { lifecycle?.foregrounded() } },
      background = { executor.execute { lifecycle?.backgrounded() } },
      unlocked = { executor.execute { created.identityBecameAvailable() } },
    )
    return { observe(signals) }
  }

  /**
   * The `sdk` events name: a wrapper's own when `TogoWrapper.set()` gave one the
   * server reads, else this SDK's. A refused one is said once and costs nothing.
   */
  private fun sdkIdentity(config: TogoConfig): SdkIdentity {
    val name = config.wrapperName ?: return SdkIdentity.NATIVE
    return SdkIdentity.wrapper(name, config.wrapperVersion ?: "") ?: run {
      log.log(
        "Togo.setup() was given a wrapper name or version the server would not read (a name is " +
          "one of ${Wire.wrapperSdkNames.joinToString(", ")}; a version is 1 to " +
          "${Wire.maxSdkVersionLength} characters with no spaces), so events name $SDK_NAME instead.",
      )
      SdkIdentity.NATIVE
    }
  }

  /** Run `work` on the SDK's thread, in call order. Before `setup()` the call is dropped with one log line. */
  private fun client(work: (Engine) -> Unit) {
    var warn = false
    val (engine, executor) = synchronized(lock) {
      if (engine == null && !inert && !warnedNotSetUp) {
        warnedNotSetUp = true
        warn = true
      }
      engine to executor
    }
    if (warn) log.log("a call was made before Togo.setup() and was dropped. Call setup() first.")
    if (engine == null || executor == null) return
    executor.execute { work(engine) }
  }

  /**
   * A getter: waits for the calls queued before it, never for the network.
   * Already on the SDK's thread — an `onError` an app routed back onto it — it
   * reads directly, since waiting on its own thread would never return.
   */
  private fun <T> read(fallback: T, value: (Engine) -> T): T {
    val (engine, executor) = synchronized(lock) { engine to executor }
    if (engine == null || executor == null) return fallback
    if (executor.isCurrent) return value(engine)
    return executor.await { value(engine) }
  }

  /** Forget the configured client, so the next test can set up its own. */
  internal fun resetForTest() {
    synchronized(lock) {
      engine = null
      executor = null
      callbacks = null
      warnedNotSetUp = false
      inert = false
    }
  }
}
