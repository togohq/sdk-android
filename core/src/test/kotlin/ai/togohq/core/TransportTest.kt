package ai.togohq.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** `HttpTransport` against a real socket: the request it makes and what it reads back. */
class TransportTest {
  private lateinit var server: HttpServer
  private var status = 202
  private var answer = "{}"
  private var retryAfter: String? = null
  private var redirectTo: String? = null
  private var method: String? = null
  private var key: String? = null
  private var contentType: String? = null
  private var received: ByteArray? = null

  @Before fun start() {
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/v1/events") { exchange ->
      method = exchange.requestMethod
      key = exchange.requestHeaders.getFirst("X-Write-Key")
      contentType = exchange.requestHeaders.getFirst("Content-Type")
      received = exchange.requestBody.readBytes()
      retryAfter?.let { exchange.responseHeaders.add("Retry-After", it) }
      redirectTo?.let { exchange.responseHeaders.add("Location", it) }
      val bytes = answer.toByteArray()
      exchange.sendResponseHeaders(status, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    server.createContext("/elsewhere") { exchange ->
      exchange.sendResponseHeaders(200, 2)
      exchange.responseBody.use { it.write("ok".toByteArray()) }
    }
    server.start()
  }

  @After fun stop() = server.stop(0)

  private fun post(url: URL): TransportResponse {
    val done = CountDownLatch(1)
    var result: TransportResponse? = null
    HttpTransport(timeoutMs = 5_000).post(url, "togo_pkl_k", "{\"a\":1}".toByteArray()) {
      result = it
      done.countDown()
    }
    done.await(10, TimeUnit.SECONDS)
    return result!!
  }

  private val url get() = URL("http://127.0.0.1:${server.address.port}/v1/events")

  @Test fun postsTheBodyWithTheWriteKeyAndReadsTheAnswer() {
    answer = """{"rejected":[]}"""
    val response = post(url)
    assertEquals("POST", method)
    assertEquals("togo_pkl_k", key)
    assertEquals("application/json", contentType)
    assertArrayEquals("{\"a\":1}".toByteArray(), received)
    assertEquals(202, response.status)
    assertArrayEquals(answer.toByteArray(), response.body)
  }

  @Test fun readsAnErrorStatusAndRetryAfter() {
    status = 429
    retryAfter = "42"
    val response = post(url)
    assertEquals(429, response.status)
    assertEquals(42.0, response.retryAfterSeconds!!, 0.0)
  }

  /** A redirect is reported, never followed: followed, the batch would land nowhere and read as a 200. */
  @Test fun aRedirectIsAnsweredNotFollowed() {
    status = 301
    redirectTo = "http://127.0.0.1:${server.address.port}/elsewhere"
    assertEquals(301, post(url).status)
  }

  @Test fun aServerErrorIsAStatusNotAThrow() {
    status = 503
    assertEquals(503, post(url).status)
  }

  /** A refusal on this side of the network is named, not read as offline; offline stays offline. */
  @Test fun aPermanentRefusalIsNamedAndOfflineIsNot() {
    val cleartext = classify(java.net.UnknownServiceException("CLEARTEXT communication to 10.0.2.2 not permitted by network security policy"))
    assertNull(cleartext.status)
    assertTrue(cleartext.failure!!.contains("CLEARTEXT"))
    assertNotNull(classify(SecurityException("Permission denied (missing INTERNET permission?)")).failure)
    assertNotNull(classify(java.net.MalformedURLException("no protocol")).failure)
    assertNotNull(classify(IllegalStateException("odd")).failure)
    for (offline in listOf(java.net.ConnectException(), java.net.SocketTimeoutException(), java.net.UnknownHostException(), javax.net.ssl.SSLHandshakeException("clock"))) {
      assertNull("$offline is offline", classify(offline).failure)
    }
  }

  /** Offline — nothing listening — is no answer, never an exception. */
  @Test fun nothingListeningIsNoAnswer() {
    val port = ServerSocket(0).use { it.localPort }
    val response = post(URL("http://127.0.0.1:$port/v1/events"))
    assertNull(response.status)
  }
}
