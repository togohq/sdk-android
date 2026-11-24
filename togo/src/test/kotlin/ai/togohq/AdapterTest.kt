package ai.togohq

import ai.togohq.core.JsonValue
import ai.togohq.core.Logger
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.UserManager
import java.io.File
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * The Android adapter over the platform: the activity callbacks, the files in
 * `noBackupFilesDir`, `PackageInfo`, a locked device and the process check,
 * through the real `Togo` and the real device environment, with only the
 * network replaced.
 */
@RunWith(RobolectricTestRunner::class)
class AdapterTest {
  private val context: Context get() = RuntimeEnvironment.getApplication()
  private val logs: MutableList<String> = Collections.synchronizedList(ArrayList())
  private lateinit var network: RecordingTransport
  private val day = 86_400_000L

  private val directory get() = File(context.noBackupFilesDir, "togo")
  private val identityFile get() = File(directory, "identity.json")

  @Before fun before() {
    Togo.resetForTest()
    Togo.log = Logger { logs.add(it) }
    network = RecordingTransport()
    directory.deleteRecursively()
    // No activity of this process in front unless a test puts one there.
    setImportance(context, android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE)
  }

  @After fun after() = Togo.resetForTest()

  private fun key() = TogoConfig.Builder("togo_pkl_live0000000000")

  /** `setup()` as an app calls it, over the device's own environment, in the main process. */
  private fun launch(config: TogoConfig = key().build(), process: String? = context.packageName) =
    Togo.setup(context, config, process) { executor -> Togo.Environment.device(context, executor, network) }

  /** A new process over the same files. */
  private fun relaunch(config: TogoConfig = key().build()) {
    Togo.resetForTest()
    launch(config)
  }

  /** Run what the main thread has queued, then wait for the SDK's thread to finish what it was given. */
  private fun settle(ms: Long = 0) {
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    val done = CountDownLatch(1)
    Togo.flush { done.countDown() }
    assertTrue("flush completed", done.await(10, TimeUnit.SECONDS))
  }

  private fun names() = network.events.map { it.string("eventName") }
  private fun count(name: String) = names().count { it == name }
  private fun opens() = network.events.filter { it.string("eventName") == "\$app_opened" }
    .map { (it["properties"] as JsonValue.Obj)["from_background"] }

  private fun activity(): ActivityController<Activity> = Robolectric.buildActivity(Activity::class.java)

  private fun installedDaysAgo(days: Long) {
    val info = shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName)
    info.firstInstallTime = System.currentTimeMillis() - days * day
    // Play updated it since: the last update is now. Not what the rule reads.
    info.lastUpdateTime = System.currentTimeMillis()
  }

  private fun setLocked(locked: Boolean) =
    shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(!locked)

  // Installed and opened, from the activity callbacks.

  @Test fun anActivityStartingOpensTheAppAfterTheLaunchsInstall() {
    launch()
    activity().setup()
    settle()
    assertEquals(listOf("\$app_installed", "\$session_started", "\$app_opened"), names())
    assertNull("the launch opens no session", network.events[0]["sessionId"])
    assertEquals(listOf<JsonValue?>(JsonValue.Bool(false)), opens())
    assertEquals("install", network.sent[0].string("deviceIdMode"))
  }

  /**
   * FR test case 2: the first launch writes one install id to the identity file
   * in `noBackupFilesDir`; a relaunch reads the same one and sends no install.
   */
  @Test fun theIdentityFileKeepsOneInstallIdAcrossARelaunch() {
    launch()
    val main = activity().setup()
    main.pause().stop()
    settle(700)
    assertTrue("written at once on the move to the background", identityFile.exists())
    val device = network.events[0].string("deviceId")
    val stored = JsonValue.parse(identityFile.readBytes()) as JsonValue.Obj
    assertEquals(device, stored.string("deviceId"))
    assertEquals("install", stored.string("deviceIdMode"))

    relaunch()
    Togo.track("screen_viewed")
    settle()
    assertEquals(device, network.events.last().string("deviceId"))
    assertEquals(1, count("\$app_installed"))
  }

  /** A process started for a broadcast, a job or a push starts no activity: no open, and no session. */
  @Test fun aProcessWithNoActivitySendsNoOpenAndOpensNoSession() {
    launch()
    Togo.track("push_received")
    settle(5_000)
    assertEquals(listOf("\$app_installed", "push_received"), names())
    assertNull(network.events[1]["sessionId"])
  }

  // FR test case 4: rotation, navigation and multi-window.

  @Test fun aRotationIsNotABackgroundAndARealOneIs() {
    launch(key().sessionTimeoutSeconds(1).build())
    val main = activity().setup()
    main.recreate()
    // A slow rotation: the old activity stops, the main thread runs for 300 ms, then the new one starts.
    main.pause().stop()
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
    val rotated = activity().setup()
    settle(5_000)
    assertEquals("no second open and no session cut", listOf("\$app_installed", "\$session_started", "\$app_opened"), names())
    assertEquals("one upload: the flush above, no background flush", 1, network.sent.size)

    rotated.pause().stop()
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(699))
    settle()
    assertEquals("not yet a background", 1, network.sent.size)
    Thread.sleep(1_100) // Past the one-second session timeout, in the background.
    settle(1)
    activity().setup()
    settle()
    assertEquals(listOf<JsonValue?>(JsonValue.Bool(false), JsonValue.Bool(true)), opens())
    assertEquals("a real background is one cut on return", 2, count("\$session_started"))
  }

  /** The main thread's scheduler: a delayed background runs at its time on the main looper, and a cancelled one never runs. */
  @Test fun theMainThreadSchedulerDelaysAndCancels() {
    val scheduler = MainThreadScheduler()
    var fired = 0
    scheduler.after(0.7) { fired++ }
    val cancel = scheduler.after(0.7) { fired += 10 }
    cancel()
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(699))
    assertEquals(0, fired)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
    assertEquals(1, fired)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
    assertEquals("the cancelled one never ran", 1, fired)
  }

  /** `setup()` in an activity's `onCreate` or `onStart`: its start is missed, its resume is not, and the visit is whole. */
  @Test fun aSetupAfterAnActivityStartedSeesItAtItsResume() {
    val main = activity().create().start()
    launch()
    main.resume()
    settle()
    assertEquals(listOf("\$app_installed", "\$session_started", "\$app_opened"), names())
    main.pause().stop()
    settle(700)
    assertEquals("the background flushed", 1, network.sent.size)
    assertEquals(0, logs.count { it.contains("ran after an activity") })
  }

  /** `setup()` after the activity's resume: nothing to see it by, so its stop says so in the log, once. */
  @Test fun aSetupAfterAnActivityResumedSaysSoOnce() {
    val main = activity().setup()
    val second = activity().setup()
    launch()
    Togo.track("a")
    main.pause().stop()
    settle(700)
    assertEquals(listOf("\$app_installed", "a"), names())
    assertNull("no session, which is what the log line explains", network.events[1]["sessionId"])
    assertEquals(1, logs.count { it.contains("ran after an activity") })
    second.pause().stop()
    settle(700)
    assertEquals("once, though a second activity went unseen too", 1, logs.count { it.contains("ran after an activity") })
  }

  // A setup() after an activity is already in front: a Solar2D game's, from its engine thread.

  private val inFront = android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND

  /** `setup()` and `track()` on another thread, as a Solar2D game's Lua runs them. */
  private fun offMainThread(work: () -> Unit) {
    val thread = Thread(work)
    thread.start()
    thread.join(10_000)
    assertFalse(thread.isAlive)
  }

  /**
   * The activity resumed before `setup()`, which ran off the main thread: it is
   * seeded at once, so the first event already has the visit's session, after
   * the visit's open. Its stop is the background, with no log line.
   */
  @Test fun aSetupOffTheMainThreadAfterTheActivityResumedSeedsIt() {
    val main = activity().setup()
    setImportance(context, inFront)
    var session: String? = null
    offMainThread {
      launch()
      Togo.track("level_complete")
      session = Togo.getSessionId()
    }
    settle()
    assertEquals(listOf("\$app_installed", "\$session_started", "\$app_opened", "level_complete"), names())
    assertNotNull(network.events[3].string("sessionId"))
    assertEquals(network.events[3].string("sessionId"), session)
    assertEquals("one batch", 1, network.sent.size)

    Togo.track("paused")
    main.pause().stop()
    settle(700)
    assertEquals("the background flushed", "paused", names().last())
    assertEquals(0, logs.count { it.contains("ran after an activity") })
    activity().setup()
    settle()
    assertEquals(listOf<JsonValue?>(JsonValue.Bool(false), JsonValue.Bool(true)), opens())
  }

  /** On the main thread the seed comes after what the main thread had queued: the visit still opens, and later events have its session. */
  @Test fun aSetupOnTheMainThreadAfterTheActivityResumedSeedsIt() {
    activity().setup()
    setImportance(context, inFront)
    launch()
    settle()
    Togo.track("a")
    settle()
    assertEquals(listOf("\$app_installed", "\$session_started", "\$app_opened", "a"), names())
    assertNotNull(network.events[3].string("sessionId"))
  }

  /**
   * `setup()` in `Application.onCreate()` during a cold start, when the process
   * already reads as in front: the launch's activity starts before the check
   * runs, so nothing is seeded — one open, and its stop is the background.
   */
  @Test fun aSetupInApplicationOnCreateSeedsNothingEvenInFront() {
    setImportance(context, inFront)
    launch()
    val main = activity().setup()
    settle()
    assertEquals(listOf("\$app_installed", "\$session_started", "\$app_opened"), names())
    assertNull(network.events[0]["sessionId"])
    main.pause().stop()
    settle(700)
    activity().setup()
    settle()
    assertEquals(
      "a return from the background: nothing seeded kept the app in front",
      listOf<JsonValue?>(JsonValue.Bool(false), JsonValue.Bool(true)),
      opens(),
    )
  }

  /** A process started for a broadcast or a job is not in front: a setup off the main thread seeds nothing. */
  @Test fun aSetupInAProcessNotInFrontSeedsNothing() {
    offMainThread {
      launch()
      Togo.track("push_received")
    }
    settle(5_000)
    assertEquals(listOf("\$app_installed", "push_received"), names())
    assertNull(network.events[1]["sessionId"])
  }

  /** After a seed, two activities never seen stopping say nothing in the log, and the app goes to the background. */
  @Test fun afterASeedUnseenStopsAreNotLoggedAndTheAppGoesToTheBackground() {
    val first = activity().setup()
    val second = activity().setup()
    setImportance(context, inFront)
    offMainThread { launch() }
    first.pause().stop()
    second.pause().stop()
    settle(700)
    assertEquals(0, logs.count { it.contains("ran after an activity") })
    activity().setup()
    settle()
    assertEquals(listOf<JsonValue?>(JsonValue.Bool(false), JsonValue.Bool(true)), opens())
  }

  /** The seeded activity pausing for a dialog and resuming: its stop is still the background. */
  @Test fun aSeededActivityResumingAfterADialogStillGoesToTheBackground() {
    val main = activity().setup()
    setImportance(context, inFront)
    offMainThread { launch() }
    main.pause().resume()
    main.pause().stop()
    settle(700)
    activity().setup()
    settle()
    assertEquals(listOf<JsonValue?>(JsonValue.Bool(false), JsonValue.Bool(true)), opens())
  }

  /**
   * `setup()` on a worker thread started in `Application.onCreate()`, with the
   * process already in front: seeded before the launch's activity starts, which
   * then takes the placeholder's place — one open, and a real background.
   */
  @Test fun aSetupOnAWorkerThreadDuringTheColdStartIsReplacedByTheLaunchActivity() {
    setImportance(context, inFront)
    offMainThread { launch() }
    val main = activity().setup()
    settle()
    assertEquals(1, names().count { it == "\$app_opened" })
    main.pause().stop()
    settle(700)
    activity().setup()
    settle()
    assertEquals(listOf<JsonValue?>(JsonValue.Bool(false), JsonValue.Bool(true)), opens())
  }

  /** The importance is a call into the system: never asked once the counter already holds an activity. */
  @Test fun theImportanceIsNotAskedWhenAnActivityIsCounted() {
    val callbacks = ActivityCallbacks(MainThreadScheduler(), Signals({}, {}, {}), UnlockWatcher(context)) {}
    var asked = 0
    callbacks.onActivityStarted(activity().get())
    assertFalse(callbacks.seedIfInFront { asked++; true })
    assertEquals(0, asked)
  }

  @Test fun startingAnotherActivityIsNotABackground() {
    launch()
    val list = activity().setup()
    val detail = activity().create().start()
    list.pause().stop()
    detail.resume()
    settle(5_000)
    assertEquals(1, count("\$app_opened"))
    assertEquals(1, network.sent.size)
  }

  /** Split-screen: one activity stopping while another is visible changes nothing; the last one stopping is the background. */
  @Test fun inMultiWindowOneActivityStoppingIsNotABackground() {
    launch()
    val left = activity().setup()
    val right = activity().setup()
    left.pause().stop()
    settle(5_000)
    assertEquals(1, network.sent.size)
    left.restart().start().resume()
    right.pause().stop()
    left.pause().stop()
    settle(700)
    assertEquals("one open for the whole visit", 1, count("\$app_opened"))
    assertTrue("the background flushed", network.sent.size >= 1)
    activity().setup()
    settle()
    assertEquals(listOf<JsonValue?>(JsonValue.Bool(false), JsonValue.Bool(true)), opens())
  }

  // FR test case 5: install or update, from PackageInfo.

  @Test fun noFileAndAnInstallTwoDaysOldIsAnInstallEvenAfterAPlayUpdate() {
    installedDaysAgo(2)
    launch()
    settle()
    assertEquals(listOf("\$app_installed"), names())
  }

  @Test fun noFileAndAnInstallThirtyDaysOldSendsNeitherThenTheUpdateIsDetected() {
    installedDaysAgo(30)
    launch(key().appVersion("1.0.0").appBuild("10").build())
    Togo.track("a")
    settle()
    assertEquals("no install, and no activity so no session", listOf("a"), names())
    // No background in this process: the record reaches the file by the one-second coalesced write.
    Thread.sleep(1_200)
    assertTrue(identityFile.exists())

    relaunch(key().appVersion("1.1.0").appBuild("11").build())
    settle()
    assertEquals("\$app_updated", names().last())
    assertEquals(
      JsonValue.Obj(mapOf("previous_app_version" to JsonValue.Str("1.0.0"), "previous_app_build" to JsonValue.Str("10"))),
      network.events.last()["properties"],
    )
  }

  // FR test case 3: a locked device (Direct Boot).

  @Test fun beforeTheUnlockNothingIsMintedAndEventsGoWithTheIdsAfterIt() {
    setLocked(true)
    launch()
    Togo.track("push_received")
    settle()
    assertTrue("held, not sent", network.sent.isEmpty())
    assertNull("nothing minted", Togo.getDeviceId())
    assertFalse("nothing written", directory.exists())

    setLocked(false)
    context.sendBroadcast(Intent(Intent.ACTION_USER_UNLOCKED))
    settle()
    assertEquals(listOf("\$app_installed", "push_received"), names())
    assertNotNull(network.events[1].string("deviceId"))
    assertEquals(network.events[1].string("deviceId"), Togo.getDeviceId())
  }

  /** An existing install started locked uses its stored ids after the unlock, and is not an install. */
  @Test fun anExistingInstallStartedLockedKeepsItsIds() {
    launch()
    activity().setup().pause().stop()
    settle(700)
    val device = network.events[0].string("deviceId")

    setLocked(true)
    relaunch()
    Togo.track("push_received")
    settle()
    val sent = network.sent.size
    setLocked(false)
    context.sendBroadcast(Intent(Intent.ACTION_USER_UNLOCKED))
    settle()
    assertTrue(network.sent.size > sent)
    assertEquals(device, network.events.last().string("deviceId"))
    assertEquals(1, count("\$app_installed"))
  }

  /** The unlock broadcast has not arrived when an activity starts: the start asks again, and nothing waits for good. */
  @Test fun anActivityStartingAfterTheUnlockReleasesWhatWasHeldWithoutTheBroadcast() {
    setLocked(true)
    launch()
    Togo.track("push_received")
    settle()
    assertTrue(network.sent.isEmpty())

    setLocked(false)
    activity().setup()
    settle()
    // Released before the open, as they happened: in the background, with no session.
    assertEquals(listOf("\$app_installed", "push_received", "\$session_started", "\$app_opened"), names())
  }

  /** The other half: a start while still locked releases nothing. */
  @Test fun anActivityStartingWhileLockedReleasesNothing() {
    setLocked(true)
    launch()
    Togo.track("push_received")
    activity().setup()
    settle()
    assertTrue(network.sent.isEmpty())
  }

  /** Unlocked between the first look and the receiver's registration: the broadcast is gone, so the watcher looks again. */
  @Test fun anUnlockJustBeforeTheReceiverIsRegisteredIsNotMissed() {
    var looks = 0
    var signalled = 0
    val watcher = UnlockWatcher(context) { looks++ > 0 }
    watcher.start { signalled++ }
    assertEquals(1, signalled)
    watcher.check()
    context.sendBroadcast(Intent(Intent.ACTION_USER_UNLOCKED))
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals("once, however many times it is told", 1, signalled)
  }

  /** Idle time counts deep sleep: the monotonic clock is elapsedRealtime, which moves while the device sleeps. */
  @Test fun theMonotonicClockCountsDeepSleep() {
    val before = AndroidClock.monotonicMs()
    org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofHours(6))
    assertEquals(6 * 3_600_000L, AndroidClock.monotonicMs() - before)
  }

  // FR test case 7: the main process only.

  @Test fun inAnotherProcessEveryCallIsANoOpAndItSaysSoOnce() {
    launch(process = "${context.packageName}:sync")
    Togo.track("a")
    Togo.identify("user_1")
    activity().setup()
    settle(1_000)
    assertTrue(network.sent.isEmpty())
    assertNull(Togo.getDeviceId())
    assertFalse(directory.exists())
    assertEquals(1, logs.count { it.contains("not the app's main process") })
    assertEquals("no \"before setup\" line either", 0, logs.count { it.contains("before Togo.setup()") })
  }

  @Test fun theMainProcessIsThePackageName() {
    assertTrue(Processes.isMain(context, context.packageName))
    assertFalse(Processes.isMain(context, "${context.packageName}:remote"))
    assertTrue("an unreadable name is not a reason to collect nothing", Processes.isMain(context, null))
    assertEquals(context.packageName, Processes.currentName())
  }

  /**
   * An app that renames its main process with `<application android:process>`:
   * that process is the main one, and the package name is not a process at all.
   */
  @Test fun aRenamedMainProcessIsTheMainProcess() {
    val renamed = "${context.packageName}:core"
    context.applicationInfo.processName = renamed
    try {
      assertTrue(Processes.isMain(context, renamed))
      assertFalse(Processes.isMain(context, context.packageName))
      launch(process = renamed)
      Togo.track("a")
      settle()
      assertEquals(listOf("\$app_installed", "a"), names())
      assertEquals(0, logs.count { it.contains("not the app's main process") })
    } finally {
      context.applicationInfo.processName = context.packageName
    }
  }

  /** Below API 28: the first NUL-terminated field of `/proc/self/cmdline`. */
  @Test fun theProcessNameBelowApi28IsReadFromCmdline() {
    val file = File.createTempFile("cmdline", null)
    try {
      file.writeBytes("com.example.app:sync\u0000--flag\u0000".toByteArray())
      assertEquals("com.example.app:sync", Processes.fromCmdline(file))
      file.writeBytes(ByteArray(0))
      assertNull(Processes.fromCmdline(file))
    } finally {
      file.delete()
    }
    assertNull(Processes.fromCmdline(File("/nonexistent/cmdline")))
  }

  /**
   * What starts the lifecycle runs after `setup()` releases its lock: an
   * observer that waits on another thread calling into the SDK must not hang
   * the app's first launch.
   */
  @Test fun anObserverThatCallsTheSdkFromAnotherThreadDoesNotDeadlockSetup() {
    var answered = false
    val environment = Togo.Environment(
      ai.togohq.core.MemoryIdentityStore(), ai.togohq.core.MemoryQueueStore(), network,
      observe = {
        val other = Thread {
          Togo.getConsent()
          answered = true
        }
        other.start()
        other.join(5_000)
      },
    )
    val done = Thread { Togo.setup(context, key().build(), context.packageName) { environment } }
    done.start()
    done.join(10_000)
    assertFalse("setup returned", done.isAlive)
    assertTrue("the other thread got its answer", answered)
  }
}
