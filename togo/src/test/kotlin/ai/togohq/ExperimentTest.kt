package ai.togohq

import ai.togohq.core.Logger
import ai.togohq.core.MemoryIdentityStore
import ai.togohq.core.MemoryQueueStore
import ai.togohq.core.Transport
import ai.togohq.core.TransportResponse
import android.content.Context
import android.os.Looper
import java.net.URL
import java.util.Collections
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** Answers every assignment request with variant `b`, and every upload with 202. */
internal class AssignTransport : Transport {
  val assignUrls: MutableList<URL> = Collections.synchronizedList(ArrayList())
  override fun post(url: URL, writeKey: String, body: ByteArray, completion: (TransportResponse) -> Unit) {
    if (!url.path.endsWith("v1/assignments")) return completion(TransportResponse(202, body = "{}".toByteArray()))
    assignUrls.add(url)
    val answer = """{"maxAgeSeconds":3600,"assignments":{"checkout":""" +
      """{"status":"active","variant":"b","participating":true,"unit":"anonymous_id"}}}"""
    completion(TransportResponse(200, body = answer.toByteArray()))
  }
}

/** `Togo.getExperiment` and `Togo.cachedExperiment` through the public object: the thread hop and the configuration. */
@RunWith(RobolectricTestRunner::class)
class ExperimentTest {
  private val context: Context get() = RuntimeEnvironment.getApplication()
  private lateinit var network: AssignTransport

  @Before fun before() {
    Togo.resetForTest()
    Togo.log = Logger { }
    network = AssignTransport()
  }

  @After fun after() = Togo.resetForTest()

  private fun setup(config: TogoConfig) =
    Togo.setup(context, config, Togo.Environment(MemoryIdentityStore(), MemoryQueueStore(), network))

  /** Run the main looper until the callback has run, or give up. */
  private fun <T> awaitOnMain(box: () -> T?): T? {
    val deadline = System.currentTimeMillis() + 5_000
    while (box() == null && System.currentTimeMillis() < deadline) {
      shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
      Thread.sleep(5)
    }
    return box()
  }

  @Test fun getExperimentAnswersOnTheMainThreadFromTheConfiguredEndpoint() {
    setup(TogoConfig.Builder("togo_pkl_live0000000000").assignEndpoint("https://assign.example.test").build())
    var result: TogoExperiment? = null
    var onMain = false
    Togo.getExperiment("checkout") {
      onMain = Looper.myLooper() == Looper.getMainLooper()
      result = it
    }
    assertEquals("b", awaitOnMain { result }?.variant)
    assertTrue("the callback runs on the main thread", onMain)
    assertEquals("assign.example.test", network.assignUrls.first().host)
    assertEquals("b", Togo.cachedExperiment("checkout")?.variant)
  }

  @Test fun withoutALifecycleTheConfiguredNamesAreFetchedAtSetup() {
    setup(TogoConfig.Builder("togo_pkl_live0000000000").experiments(listOf("checkout")).build())
    // The answer lands on the SDK's thread a moment after setup() returns.
    var cached: TogoExperiment? = null
    val deadline = System.currentTimeMillis() + 5_000
    while (cached == null && System.currentTimeMillis() < deadline) {
      cached = Togo.cachedExperiment("checkout")
      if (cached == null) Thread.sleep(10)
    }
    assertEquals("b", cached?.variant)
    assertEquals(1, network.assignUrls.size)
  }

  @Test fun beforeSetupTheAnswerIsUnavailable() {
    var result: TogoExperiment? = null
    Togo.getExperiment("checkout") { result = it }
    assertEquals(TogoExperimentStatus.UNAVAILABLE, awaitOnMain { result }?.status)
    assertNull(Togo.cachedExperiment("checkout"))
  }
}
