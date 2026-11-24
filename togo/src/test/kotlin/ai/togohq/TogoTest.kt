package ai.togohq

import ai.togohq.core.JsonValue
import ai.togohq.core.Logger
import ai.togohq.core.MemoryIdentityStore
import ai.togohq.core.MemoryQueueStore
import ai.togohq.core.Transport
import ai.togohq.core.TransportResponse
import android.content.Context
import android.os.Build
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.net.URL
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

/** Records every upload and answers 202. */
internal class RecordingTransport : Transport {
  val sent: MutableList<JsonValue.Obj> = Collections.synchronizedList(ArrayList())
  override fun post(url: URL, writeKey: String, body: ByteArray, completion: (TransportResponse) -> Unit) {
    sent.add(JsonValue.parse(body) as JsonValue.Obj)
    completion(TransportResponse(202, body = "{}".toByteArray()))
  }
  val events: List<JsonValue.Obj>
    get() = synchronized(sent) { sent.flatMap { (it["events"] as JsonValue.Arr).values.map { e -> e as JsonValue.Obj } } }
}

@RunWith(RobolectricTestRunner::class)
class TogoTest {
  private val context: Context get() = RuntimeEnvironment.getApplication()
  private val logs: MutableList<String> = Collections.synchronizedList(ArrayList())
  private lateinit var network: RecordingTransport

  @Before fun before() {
    Togo.resetForTest()
    Togo.log = Logger { logs.add(it) }
    network = RecordingTransport()
  }

  @After fun after() = Togo.resetForTest()

  private fun setup(config: TogoConfig) =
    Togo.setup(context, config, Togo.Environment(MemoryIdentityStore(), MemoryQueueStore(), network))

  private fun flushAndWait() {
    val done = CountDownLatch(1)
    Togo.flush { done.countDown() }
    assertTrue("flush completed", done.await(10, TimeUnit.SECONDS))
  }

  private fun liveKey() = TogoConfig.Builder("togo_pkl_live0000000000")

  @Test fun trackIdentifyAndFlushSendOneBatchFromThisDevice() {
    ShadowBuild.setManufacturer("samsung")
    ShadowBuild.setModel("SM-S918B")
    ShadowBuild.setVersionRelease("16")
    setup(liveKey().appVersion("2.0.0").appBuild("200").build())
    Togo.track("plan_selected", mapOf("plan_id" to "pro", "seats" to 5))
    Togo.identify("user_1")
    flushAndWait()

    val batch = network.sent.single()
    assertEquals(
      JsonValue.Obj(mapOf("version" to JsonValue.Str("2.0.0"), "build" to JsonValue.Str("200"))),
      batch["app"],
    )
    val device = batch["device"] as JsonValue.Obj
    assertEquals("Android", device.string("os"))
    assertEquals("16", device.string("osVersion"))
    assertEquals("SM-S918B", device.string("model"))
    assertEquals("samsung", device.string("manufacturer"))
    assertEquals("mobile", device.string("deviceType"))
    assertEquals("install", batch.string("deviceIdMode"))
    assertEquals(listOf("\$session_started", "plan_selected", "\$identify"), network.events.map { it.string("eventName") })
    assertEquals("sdk-android", (network.events[1]["sdk"] as JsonValue.Obj).string("name"))
    assertEquals(JsonValue.Int(5), (network.events[1]["properties"] as JsonValue.Obj)["seats"])
    assertEquals("user_1", network.events[2].string("userId"))
  }

  /** Without the options, the app's version and build come from its package. */
  @Test fun theAppVersionAndBuildAreReadFromThePackage() {
    val info = shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName)
    info.versionName = "3.1.4"
    info.longVersionCode = 314
    setup(liveKey().build())
    Togo.track("a")
    flushAndWait()
    assertEquals(
      JsonValue.Obj(mapOf("version" to JsonValue.Str("3.1.4"), "build" to JsonValue.Str("314"))),
      network.sent.single()["app"],
    )
  }

  /** `tablet` at a smallest width of 600 dp or more — never `phone`. */
  @Test
  @Config(qualifiers = "sw600dp")
  fun aSixHundredDpDeviceIsATablet() {
    setup(liveKey().build())
    Togo.track("a")
    flushAndWait()
    assertEquals("tablet", (network.sent.single()["device"] as JsonValue.Obj).string("deviceType"))
  }

  @Test fun theGettersReadWithoutMinting() {
    setup(liveKey().build())
    assertNull(Togo.getAnonymousId())
    assertNull(Togo.getDeviceId())
    assertNull(Togo.getSessionId())
    assertEquals(TogoConsent.NOT_REQUIRED, Togo.getConsent())
    Togo.track("a")
    assertNotNull(Togo.getAnonymousId())
    assertNotNull(Togo.getDeviceId())
    assertNotNull(Togo.getSessionId())
  }

  /** A getter called from a flush callback — or from `onError` — returns; it never waits on itself. */
  @Test fun aGetterFromAFlushCallbackOrOnErrorDoesNotHang() {
    val seen = CountDownLatch(2)
    var fromCallback: String? = null
    setup(
      liveKey().onError {
        Togo.getSessionId()
        seen.countDown()
      }.build(),
    )
    Togo.track("a")
    Togo.track("\$reserved") // refused under a live key: onError runs
    Togo.flush {
      fromCallback = Togo.getAnonymousId()
      seen.countDown()
    }
    assertTrue(seen.await(10, TimeUnit.SECONDS))
    assertNotNull(fromCallback)
  }

  @Test fun pendingConsentHoldsUntilGranted() {
    setup(liveKey().consent(TogoConsent.PENDING).build())
    Togo.track("a")
    flushAndWait()
    assertTrue(network.sent.isEmpty())
    assertEquals(TogoConsent.PENDING, Togo.getConsent())
    Togo.setConsent(TogoConsent.GRANTED)
    flushAndWait()
    assertEquals(listOf("\$session_started", "a"), network.events.map { it.string("eventName") })
  }

  @Test fun personPropertiesTakeTheTypedFieldsAndTheBuilder() {
    setup(liveKey().build())
    Togo.setPersonProperties(set = mapOf("plan" to "pro"), email = "ada@example.com")
    Togo.setPersonProperties(TogoPersonProperties.Builder().removeName().setOnce(mapOf("first_seen" to "android")).unset(listOf("legacy")).build())
    flushAndWait()
    val ops = network.events.filter { it.string("eventName") == "\$set_person_properties" }.map { it["personProperties"] as JsonValue.Obj }
    assertEquals(
      JsonValue.Obj(mapOf("\$email" to JsonValue.Str("ada@example.com"), "plan" to JsonValue.Str("pro"))),
      ops[0]["set"],
    )
    assertEquals(JsonValue.Obj(mapOf("\$name" to JsonValue.Null)), ops[1]["set"])
    assertEquals(JsonValue.Obj(mapOf("first_seen" to JsonValue.Str("android"))), ops[1]["setOnce"])
  }

  /** The caller's map is copied at the call: a change after it never reaches the queued event. */
  @Test fun aMapChangedAfterTheCallIsNotWhatIsSent() {
    setup(liveKey().build())
    val properties = HashMap<String, Any?>(mapOf("step" to 1))
    Togo.track("a", properties)
    properties["step"] = 2
    flushAndWait()
    assertEquals(JsonValue.Int(1), (network.events[1]["properties"] as JsonValue.Obj)["step"])
  }

  @Test fun aCallBeforeSetupIsDroppedWithOneLine() {
    Togo.track("a")
    Togo.identify("user_1")
    assertEquals(1, logs.count { it.contains("before Togo.setup()") })
    assertNull(Togo.getAnonymousId())
    flushAndWait() // returns at once, with nothing to send
  }

  @Test fun aSecondSetupIsIgnored() {
    setup(liveKey().build())
    setup(TogoConfig.Builder("togo_pkt_other").build())
    assertTrue(logs.any { it.contains("called again and ignored") })
  }

  /** A malformed or secret key disables the client with one error, never a crash. */
  @Test fun aKeyThatIsNotAWriteKeyDisablesTheClient() {
    val errors = Collections.synchronizedList(ArrayList<TogoError>())
    val reported = CountDownLatch(1)
    setup(
      TogoConfig.Builder("togo_sk_secret").onError {
        errors.add(it)
        reported.countDown()
      }.build(),
    )
    Togo.track("a")
    flushAndWait()
    assertTrue(reported.await(5, TimeUnit.SECONDS))
    assertTrue(network.sent.isEmpty())
    assertEquals(listOf(TogoErrorCode.WRITE_KEY_INVALID), errors.map { it.code })
    assertTrue(errors[0].message.contains("SECRET"))
  }

  /** The device's own environment keeps both files in `noBackupFilesDir`, excluded from Auto Backup. */
  @Test fun theQueueAndIdentityFilesAreInNoBackupFilesDir() {
    val environment = Togo.Environment.device(context, ai.togohq.core.SerialExecutor("test"))
    val queue = environment.queue as ai.togohq.core.FileQueueStore
    assertEquals(File(File(context.noBackupFilesDir, "togo"), "queue.jsonl"), queue.file)
    val identity = environment.identity as ai.togohq.core.FileIdentityStore
    assertEquals(File(File(context.noBackupFilesDir, "togo"), "identity.json"), identity.file)
  }

  /** `<= 0` disables sessions through the public option too. */
  @Test fun aZeroTimeoutSendsNoSession() {
    setup(liveKey().sessionTimeoutSeconds(0).build())
    Togo.track("a")
    flushAndWait()
    assertEquals("disabled", network.sent.single().string("sessionIdMode"))
    assertEquals(listOf("a"), network.events.map { it.string("eventName") })
  }

  @Test fun theBuildIsOnTheNewestApi() {
    // Robolectric runs the suite on the SDK the library compiles against.
    assertEquals(36, Build.VERSION.SDK_INT)
  }
}

/**
 * The importance the system gives this process. Robolectric reports it in
 * front by default; a test that sets nothing up in front says otherwise, the
 * way a process started for a broadcast or a job is.
 */
internal fun setImportance(context: Context, importance: Int) {
  val info = android.app.ActivityManager.RunningAppProcessInfo(context.packageName, android.os.Process.myPid(), arrayOf(context.packageName))
  info.importance = importance
  shadowOf(context.getSystemService(android.app.ActivityManager::class.java)).setProcesses(listOf(info))
}

/** For the Java test: an `internal` name is mangled outside Kotlin. */
object TestSupport {
  @JvmStatic fun reset() = Togo.resetForTest()
}
