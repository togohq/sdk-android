package ai.togohq.core

/**
 * Which activities are started, and so whether the app is in front, decided
 * HERE and platform-free: the Android adapter only forwards
 * `onActivityStarted` and `onActivityStopped`, so every rule below runs on
 * the JVM.
 *
 * - **The app is in front while at least one activity is started.** A second
 *   activity in split-screen or multi-window stopping while another is visible
 *   changes nothing.
 * - **The last one stopping is not yet the background.** It schedules the
 *   background after [BACKGROUND_DELAY_SECONDS], and a start before then
 *   cancels it: a rotation stops the old activity before the new one starts,
 *   and without the delay every rotation would cut a session and send an open.
 * - **A start with nothing started and no background pending is the
 *   foreground.** A start while the background is pending is the same visit.
 * - **An activity is known by identity.** One first seen RESUMING — the SDK
 *   was set up after its start, in its `onCreate` or `onStart` — is counted as
 *   started then. A stop for one never seen at all — set up after its resume —
 *   is ignored rather than counted, so the count can never go below zero, and
 *   reported through `onUnknownStop`, since until another activity starts the
 *   app reads as not in front and no session opens.
 * - **An activity already in front when the SDK was set up can be seeded.**
 *   [seedUnseen] counts one placeholder for it and is the foreground, exactly
 *   as its start would have been. The first activity the counter then sees —
 *   started or resumed — takes the placeholder's place, so the placeholder is
 *   never counted beside a real activity: that is the seeded one seen at
 *   last (resuming after a dialog), the launch's activity after a seed that
 *   ran before it started, or one started over it, whose own stop or the
 *   seeded one's next start keeps the count right. A stop with nothing seen
 *   since the seed is the seeded one's. After a seed a stop for an activity
 *   never seen is not reported: the setup was late, and the seed is the
 *   answer to it. The adapter decides WHEN to seed — never in a process with
 *   no activity in front.
 *
 * Thread-safe: the callbacks come on the main thread, a seed from the thread
 * of a late `setup()`, and the delayed background from [scheduler]; every
 * change is under one lock. The two signals go wherever the caller sends
 * them — the engine's executor, through [AppLifecycle].
 */
internal class ActivityCounter(
  private val scheduler: TogoExecutor,
  private val onForeground: () -> Unit,
  private val onBackground: () -> Unit,
  private val onUnknownStop: () -> Unit = {},
) {
  private val lock = Any()
  private val started = HashSet<Any>()
  private var cancelBackground: (() -> Unit)? = null
  private var seeded = false

  fun activityStarted(activity: Any): Unit = synchronized(lock) {
    // The first activity seen after a seed takes the placeholder's place: the visit is already open.
    if (started.remove(Unseen)) {
      started.add(activity)
      return
    }
    val wasEmpty = started.isEmpty()
    if (!started.add(activity) || !wasEmpty) return
    val pending = cancelBackground
    if (pending != null) {
      // Back before the delay ran out: a rotation, or one activity replacing another.
      pending()
      cancelBackground = null
      return
    }
    onForeground()
  }

  /** A resume is a start for an activity whose start the SDK missed; for any other, nothing. */
  fun activityResumed(activity: Any) = activityStarted(activity)

  /** Nothing counted and no background pending: what [seedUnseen] requires. */
  val isEmpty: Boolean get() = synchronized(lock) { started.isEmpty() && cancelBackground == null }

  /**
   * One activity is in front that the SDK never saw start: count a placeholder
   * for it, and be the foreground. Ignored once anything is counted or a
   * background is pending — then an activity has been seen, and the counter
   * already knows what is in front.
   */
  fun seedUnseen(): Boolean = synchronized(lock) {
    if (!(started.isEmpty() && cancelBackground == null)) return false
    started.add(Unseen)
    seeded = true
    onForeground()
    true
  }

  fun activityStopped(activity: Any): Unit = synchronized(lock) {
    if (!started.remove(activity)) {
      // The seeded activity stopping, with nothing seen since the seed.
      if (!started.remove(Unseen)) {
        if (!seeded) onUnknownStop()
        return
      }
    }
    if (started.isNotEmpty()) return
    cancelBackground = scheduler.after(BACKGROUND_DELAY_SECONDS) {
      synchronized(lock) {
        cancelBackground = null
        onBackground()
      }
    }
  }

  /** The placeholder [seedUnseen] counts: never equal to an activity. */
  private object Unseen

  companion object {
    /** The delay the platform's own process lifecycle uses for the same reason. */
    const val BACKGROUND_DELAY_SECONDS = 0.7
  }
}

/**
 * The app's lifecycle as the engine sees it, on the engine's executor.
 *
 * - **`$app_opened` is the move from nothing in front to something.** Never
 *   for a process started by a broadcast, a job, a push or a content provider,
 *   since no activity starts in any of them. `from_background` is false for the
 *   process's FIRST foreground, however it was started, and true for every
 *   return after it.
 * - **A person is present only while the app is in front**, which includes
 *   the moment between the last activity stopping and the background, and only
 *   an event with a person present can open a session. So `setup()` and the
 *   launch's `$app_installed` never open one by themselves. Under pending
 *   consent the launch is held with everything else, and a grant while the app
 *   is in front releases the buffer in ONE session opened at its first event,
 *   the install — the same as the iOS SDK, and pinned by the native scenarios'
 *   shared rules.
 */
internal class AppLifecycle {
  private var engine: Engine? = null
  private var hasBeenForeground = false

  /** What the engine's `humanPresent` reads. */
  @Volatile var isForeground: Boolean = false
    private set

  /** The process started; the launch's install or update event is decided first, so it precedes this launch's open. */
  fun launched(engine: Engine) {
    this.engine = engine
    engine.start()
  }

  /** Sent once per visit: [ActivityCounter] never reports a foreground twice without a background between. */
  fun foregrounded() {
    isForeground = true
    val fromBackground = hasBeenForeground
    hasBeenForeground = true
    engine?.appOpened(fromBackground)
  }

  /** `completion` runs after the background flush, or at once when the app was not in front. */
  fun backgrounded(completion: (() -> Unit)? = null) {
    val engine = engine
    if (!isForeground || engine == null) {
      completion?.invoke()
      return
    }
    isForeground = false
    engine.appBackgrounded(completion)
  }
}
