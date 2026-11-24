package ai.togohq.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * What came back from one upload. A `null` status means no answer — offline, a
 * timeout, a dropped connection — unless [failure] says why no upload can ever
 * succeed from this process.
 */
internal data class TransportResponse(
  val status: Int?,
  val retryAfterSeconds: Double? = null,
  val body: ByteArray? = null,
  /**
   * A refusal on this side of the network that a retry cannot change: cleartext
   * HTTP the platform blocks, a URL it cannot open, a missing permission. Not
   * offline — read as offline it would be retried in silence until the events
   * aged out.
   */
  val failure: String? = null,
)

/** The network, as one call. An interface so the scenario runner can hold it offline and a test can answer 503. */
internal fun interface Transport {
  fun post(url: URL, writeKey: String, body: ByteArray, completion: (TransportResponse) -> Unit)
}

/**
 * `HttpURLConnection`, on its own thread so an upload never blocks the SDK's
 * thread. No cookies and no cache: an analytics upload has no use for either.
 */
internal class HttpTransport(private val timeoutMs: Int = 30_000) : Transport {
  private val pool = Executors.newSingleThreadExecutor(daemon("togo-upload"))

  override fun post(url: URL, writeKey: String, body: ByteArray, completion: (TransportResponse) -> Unit) {
    pool.execute { completion(send(url, writeKey, body)) }
  }

  private fun send(url: URL, writeKey: String, body: ByteArray): TransportResponse {
    var connection: HttpURLConnection? = null
    return try {
      connection = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        useCaches = false
        // A redirect is answered, never followed: followed, a POST can come
        // back as a GET's 200 from a page that never saw the batch.
        instanceFollowRedirects = false
        connectTimeout = timeoutMs
        readTimeout = timeoutMs
        setFixedLengthStreamingMode(body.size)
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("X-Write-Key", writeKey)
      }
      connection.outputStream.use { it.write(body) }
      val status = connection.responseCode
      val stream = if (status >= 400) connection.errorStream else connection.inputStream
      val answer = try {
        stream?.use { it.readBytes() }
      } catch (_: IOException) {
        null
      }
      TransportResponse(status, connection.getHeaderField("Retry-After")?.trim()?.toDoubleOrNull(), answer)
    } catch (e: Exception) {
      classify(e)
    } finally {
      connection?.disconnect()
    }
  }
}

/**
 * A thrown upload, sorted: offline — normal operation, retried later — or a
 * refusal no retry can change, which the engine reports. Never a throw into the
 * app: the events stay queued either way.
 */
internal fun classify(e: Exception): TransportResponse = when (e) {
  // Android's Network Security Config blocking cleartext HTTP
  // ("CLEARTEXT communication to … not permitted"), or a scheme with no handler.
  is java.net.UnknownServiceException -> TransportResponse(null, failure = "the platform refused the connection: ${e.message}")
  is java.net.MalformedURLException -> TransportResponse(null, failure = "the endpoint is not a URL: ${e.message}")
  // No INTERNET permission, or a policy that forbids the socket.
  is SecurityException -> TransportResponse(null, failure = "the platform refused the connection: ${e.message}")
  is IOException -> TransportResponse(null)
  // Anything else thrown on this side — an illegal argument, a platform bug —
  // is not the network being away, so it is said rather than retried quietly.
  else -> TransportResponse(null, failure = "the upload could not be made: $e")
}

/**
 * Where the engine's work runs. Every engine method is called on ONE serial
 * executor, which is what makes it safe without locks and keeps calls in the
 * order they were made.
 */
internal interface TogoExecutor {
  fun execute(work: () -> Unit)
  /** Run `work` after `seconds`. Returns a cancel function. */
  fun after(seconds: Double, work: () -> Unit): () -> Unit
  /** Whether the caller is already running on this executor — where waiting on it would deadlock. */
  val isCurrent: Boolean get() = false
}

/** One daemon thread, so the SDK never keeps a JVM alive and never runs two calls at once. */
internal class SerialExecutor(name: String) : TogoExecutor {
  @Volatile private var thread: Thread? = null
  private val service: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { runnable ->
      daemon(name).newThread(runnable).also { thread = it }
    }

  override val isCurrent: Boolean get() = Thread.currentThread() === thread

  override fun execute(work: () -> Unit) {
    service.execute(guarded(work))
  }

  override fun after(seconds: Double, work: () -> Unit): () -> Unit {
    val delayMs = (seconds.coerceIn(0.0, 86_400.0) * 1000).toLong()
    val future = service.schedule(guarded(work), delayMs, TimeUnit.MILLISECONDS)
    return { future.cancel(false) }
  }

  /** Run on this executor and wait for the answer. Never call it from this executor. */
  fun <T> await(work: () -> T): T = service.submit<T> { work() }.get()

  /**
   * A throwing callback — an app's `onError` handler — must not kill the
   * thread the SDK runs on; the executor would replace it and lose its order.
   */
  private fun guarded(work: () -> Unit) = Runnable {
    try {
      work()
    } catch (e: Throwable) {
      if (e is VirtualMachineError) throw e
      System.err.println("[togo] a callback threw and was ignored: $e")
    }
  }
}

internal fun daemon(name: String) = ThreadFactory { runnable ->
  Thread(runnable, name).apply { isDaemon = true }
}
