package ai.togohq

import ai.togohq.core.ActivityCounter
import ai.togohq.core.TogoExecutor
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import java.io.File
import java.io.IOException

// The Android half of the lifecycle and of a locked device. It only FORWARDS:
// which callback means what — counting started activities, the 700 ms delay,
// what an open is, when background time starts, what a locked device holds —
// is decided in the core, where the JVM tests cover it. What is left here is
// what only the platform can run: the callbacks, the unlock broadcast, the
// main thread's handler and the process name.

/**
 * Where the adapter's signals go: the SDK's thread, through `Togo`. Called on
 * the main thread, and — for the foreground of a seed — on the thread of a
 * late `setup()`.
 */
internal class Signals(
  val foreground: () -> Unit,
  val background: () -> Unit,
  val unlocked: () -> Unit,
)

/** The main thread's handler as the core's scheduler: the activity counter's delayed background runs there. */
internal class MainThreadScheduler(private val handler: Handler = Handler(Looper.getMainLooper())) : TogoExecutor {
  override fun execute(work: () -> Unit) {
    handler.post(work)
  }

  override fun after(seconds: Double, work: () -> Unit): () -> Unit {
    val runnable = Runnable(work)
    handler.postDelayed(runnable, (seconds * 1000).toLong())
    return { handler.removeCallbacks(runnable) }
  }

  override val isCurrent: Boolean get() = Looper.myLooper() == handler.looper
}

/**
 * Started activities, counted by the core's [ActivityCounter]. Registered in
 * `setup()`, which belongs in `Application.onCreate()`, before any activity
 * starts. A resume stands for a start the SDK missed; a stop for an activity it
 * never saw at all is said once in the log, with the fix.
 *
 * Every start also asks whether the device is unlocked: an activity can start
 * before the unlock broadcast arrives, and its events must not wait for it.
 *
 * A `setup()` after an activity was already in front — a Solar2D game's,
 * which runs on the engine's thread once its activity shows — sees none of
 * that activity's callbacks: [seedIfInFront] counts it as started, so its
 * visit has an open and a session from the first event. The callbacks run on
 * the main thread and the seed on the setup's; the counter takes its own lock.
 */
internal class ActivityCallbacks(
  scheduler: TogoExecutor,
  signals: Signals,
  private val unlock: UnlockWatcher,
  private val log: (String) -> Unit,
) : Application.ActivityLifecycleCallbacks {
  private var warnedLate = false
  private val counter = ActivityCounter(scheduler, signals.foreground, signals.background) {
    if (!warnedLate) {
      warnedLate = true
      log(
        "Togo.setup() ran after an activity was already in front, so that activity was not seen: no " +
          "\$app_opened was sent and events had no session until another activity started. Call " +
          "Togo.setup() in Application.onCreate().",
      )
    }
  }

  override fun onActivityStarted(activity: Activity) {
    unlock.check()
    counter.activityStarted(activity)
  }

  override fun onActivityStopped(activity: Activity) = counter.activityStopped(activity)
  override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
  override fun onActivityResumed(activity: Activity) {
    unlock.check()
    counter.activityResumed(activity)
  }

  /**
   * Count the activity in front as started when the process is in front and
   * the counter holds nothing — no activity counted, no background pending.
   * Then every activity it has seen has stopped and gone to the background, so
   * one in front is one it never saw. Never in a process started for a broadcast, a
   * job, a service or a provider: none of them is [inFront]. Returns whether
   * it seeded. The importance is a call into the system, so it is asked only
   * when the counter is empty, and outside the counter's lock, which the
   * main thread's callbacks need; the seed checks again under it.
   */
  fun seedIfInFront(inFront: () -> Boolean): Boolean = counter.isEmpty && inFront() && counter.seedUnseen()
  override fun onActivityPaused(activity: Activity) {}
  override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
  override fun onActivityDestroyed(activity: Activity) {}
}

/**
 * Whether the user has unlocked the device since it booted. Before that, the
 * app's credential-protected storage — the no-backup directory with it — cannot
 * be read, and only a component marked direct-boot aware runs at all. Below
 * API 24 there is no such state.
 *
 * Signals [Signals.unlocked] once, the first time the device is seen unlocked
 * after being seen locked: from `ACTION_USER_UNLOCKED`, or from [check] when
 * the broadcast has not arrived yet (an activity starting, the moment after
 * the receiver was registered).
 */
internal class UnlockWatcher(
  private val context: Context,
  private val isUnlocked: () -> Boolean = { userUnlocked(context) },
) {
  @Volatile private var waiting = false
  private var signal: (() -> Unit)? = null
  private var receiver: BroadcastReceiver? = null

  /** Start watching, when the device is locked now. */
  fun start(unlocked: () -> Unit) {
    signal = unlocked
    // Below API 24 there is no locked state to wait for.
    if (Build.VERSION.SDK_INT < 24 || isUnlocked()) return
    waiting = true
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) = check()
    }
    this.receiver = receiver
    val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
    // A system broadcast reaches a receiver that is not exported; the flag is
    // required from API 33 for any receiver registered at run time.
    if (Build.VERSION.SDK_INT >= 33) {
      context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
      context.registerReceiver(receiver, filter)
    }
    // Unlocked between the first look and the registration: the broadcast is gone.
    check()
  }

  /** Signal once if the device was locked and is not any more. */
  fun check() {
    if (!waiting || !isUnlocked()) return
    synchronized(this) {
      if (!waiting) return
      waiting = false
    }
    receiver?.let {
      receiver = null
      try {
        context.unregisterReceiver(it)
      } catch (_: IllegalArgumentException) {
        // Already unregistered: nothing to do.
      }
    }
    signal?.invoke()
  }

  companion object {
    fun userUnlocked(context: Context): Boolean {
      if (Build.VERSION.SDK_INT < 24) return true
      val users = context.getSystemService(Context.USER_SERVICE) as? UserManager ?: return true
      return users.isUserUnlocked
    }
  }
}

/**
 * The process this code runs in. Two processes writing one identity file and
 * one queue would corrupt both and start two sessions, so the SDK runs only in
 * the app's main process: the one its manifest names for the application,
 * which is the package name unless `<application android:process>` renames it.
 */
internal object Processes {
  fun currentName(): String? =
    if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else fromCmdline(File("/proc/self/cmdline"))

  /** The process name below API 28: the first NUL-terminated field of the kernel's command line. */
  fun fromCmdline(file: File): String? = try {
    val bytes = file.readBytes()
    val end = bytes.indexOf(0.toByte()).let { if (it < 0) bytes.size else it }
    String(bytes, 0, end, Charsets.UTF_8).trim().ifEmpty { null }
  } catch (_: IOException) {
    null
  }

  /**
   * `null` — the name could not be read — counts as the main process: an SDK
   * that turned itself off whenever `/proc` was unreadable would collect
   * nothing in the one process that matters.
   */
  fun isMain(context: Context, name: String?): Boolean = name == null || name == mainName(context)

  /**
   * Whether this process is the one in front — an activity of it visible and
   * focused — from the importance the system gives it: `IMPORTANCE_FOREGROUND`.
   * A process running a broadcast receiver, a job or a service, or one holding
   * a foreground service with no activity, has a lower importance. From API 21
   * the list holds this app's own processes only. Read through
   * `getRunningAppProcesses()` rather than `getMyMemoryState()` because it is
   * the one a JVM test can set. Any failure reads as not in front: a missed
   * seed costs one visit's open, a wrong one keeps the app in front.
   */
  fun inFront(context: Context): Boolean = try {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val pid = android.os.Process.myPid()
    manager?.runningAppProcesses.orEmpty().any {
      it.pid == pid && it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
  } catch (_: RuntimeException) {
    false
  }

  /** The application's process name from the manifest: the package name unless renamed. */
  fun mainName(context: Context): String = context.applicationInfo?.processName ?: context.packageName
}
