package ai.togohq

import ai.togohq.core.JsonValue
import ai.togohq.core.Logger
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.os.UserManager
import java.io.File
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

/**
 * The adapter as it runs on the oldest platforms the SDK supports: API 21
 * (Android 5.0) and API 22 (Android 5.1, which Fire OS 5 tablets run). The rest
 * of the suite runs on the newest API, so it takes the newer side of every
 * `Build.VERSION.SDK_INT` guard; these tests take the older side — no locked
 * state below 24, the process name from `/proc` and the int version code below
 * 28 — through `setup()`, `track()`, the lifecycle and the identity file.
 *
 * Robolectric runs no framework older than API 24 (it removed 21 and 22 in 4.16
 * and 23 in 4.18), so the framework here is the newest one with `SDK_INT` set to
 * the old level: that drives every guard in the SDK down its old branch. What
 * it cannot show is a call the old framework itself lacks. That is Android
 * lint's `NewApi` check, which fails `./gradlew check` on any unguarded call
 * above the minimum, and the emulator run on API 21 and 22 before a release.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class OlderApiTest(private val api: Int) {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "API {0}")
    fun levels(): List<Array<Any>> = listOf(arrayOf(21), arrayOf(22))
  }

  private val context: Context get() = RuntimeEnvironment.getApplication()
  private val logs: MutableList<String> = Collections.synchronizedList(ArrayList())
  private lateinit var network: RecordingTransport
  private var realApi = 0

  private val directory get() = File(context.noBackupFilesDir, "togo")
  private val identityFile get() = File(directory, "identity.json")

  @Before fun before() {
    realApi = Build.VERSION.SDK_INT
    ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", api)
    Togo.resetForTest()
    Togo.log = Logger { logs.add(it) }
    network = RecordingTransport()
    directory.deleteRecursively()
    setImportance(context, android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE)
  }

  @After fun after() {
    Togo.resetForTest()
    ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", realApi)
  }

  private fun key() = TogoConfig.Builder("togo_pkl_live0000000000")

  /** `setup()` over the device's own environment, in the main process. */
  private fun launch(config: TogoConfig = key().build()) =
    Togo.setup(context, config, context.packageName) { executor -> Togo.Environment.device(context, executor, network) }

  private fun settle(ms: Long = 0) {
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    val done = CountDownLatch(1)
    Togo.flush { done.countDown() }
    assertTrue("flush completed", done.await(10, TimeUnit.SECONDS))
  }

  private fun names() = network.events.map { it.string("eventName") }

  /** What the framework would say about a locked device, if the platform had the state. */
  private fun frameworkSaysLocked() =
    shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)

  /** Guards the rest: an `SDK_INT` that did not take would test the newest API twice. */
  @Test fun theSdkSeesTheOldApiLevel() {
    assertEquals(api, Build.VERSION.SDK_INT)
  }

  /** Set up, open, track and flush, then a relaunch over the identity file in `noBackupFilesDir`. */
  @Test fun setupTrackAndTheIdentityFileWorkAcrossARelaunch() {
    launch()
    val main = Robolectric.buildActivity(Activity::class.java).setup()
    Togo.track("screen_viewed")
    settle()
    assertEquals(listOf("\$app_installed", "\$session_started", "\$app_opened", "screen_viewed"), names())
    main.pause().stop()
    settle(700)
    assertTrue("written on the move to the background", identityFile.exists())
    val device = network.events[0].string("deviceId")
    assertEquals(device, (JsonValue.parse(identityFile.readBytes()) as JsonValue.Obj).string("deviceId"))

    Togo.resetForTest()
    launch()
    Togo.track("a")
    settle()
    assertEquals(device, network.events.last().string("deviceId"))
    assertEquals("one install", 1, names().count { it == "\$app_installed" })
  }

  /**
   * Below API 24 there is no locked state and `UserManager.isUserUnlocked` does
   * not exist: the SDK never asks, mints and sends at once, and waits for no
   * unlock broadcast.
   */
  @Test fun belowApi24TheDeviceIsNeverLocked() {
    frameworkSaysLocked()
    assertTrue(UnlockWatcher.userUnlocked(context))
    launch()
    Togo.track("push_received")
    settle()
    assertEquals(listOf("\$app_installed", "push_received"), names())
    assertNotNull(Togo.getDeviceId())
    assertTrue("the identity file is written", directory.exists())

    var signalled = 0
    val watcher = UnlockWatcher(context) { false }
    watcher.start { signalled++ }
    context.sendBroadcast(Intent(Intent.ACTION_USER_UNLOCKED))
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals("no receiver was registered", 0, shadowOf(RuntimeEnvironment.getApplication()).registeredReceivers.size)
    assertEquals(0, signalled)
  }

  /** Below API 28 the build is the int `versionCode`; `longVersionCode` does not exist. */
  @Test fun theAppBuildBelowApi28IsTheIntVersionCode() {
    val info = shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName)
    info.versionName = "3.1.4"
    // A major part, so the long code (4294967610) and the int one (314) differ.
    info.longVersionCode = (1L shl 32) or 314L
    launch()
    Togo.track("a")
    settle()
    assertEquals(
      JsonValue.Obj(mapOf("version" to JsonValue.Str("3.1.4"), "build" to JsonValue.Str("314"))),
      network.sent.first()["app"],
    )
  }

  /** Below API 28 the process name is read from `/proc/self/cmdline`, never `Application.getProcessName()`. */
  @Test fun theProcessNameBelowApi28ComesFromProc() {
    assertEquals(Processes.fromCmdline(File("/proc/self/cmdline")), Processes.currentName())
  }
}
