package ai.togohq.core

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutorTest {
  /** Work runs one at a time, in the order it was given, and a throw does not lose what comes after it. */
  @Test fun runsInOrderAndSurvivesAThrowingCallback() {
    val executor = SerialExecutor("togo-test")
    val seen = Collections.synchronizedList(ArrayList<Int>())
    val done = CountDownLatch(1)
    executor.execute { seen.add(1) }
    executor.execute { throw IllegalStateException("an app's onError handler threw") }
    executor.execute { seen.add(2) }
    executor.execute { done.countDown() }
    assertTrue(done.await(5, TimeUnit.SECONDS))
    assertEquals(listOf(1, 2), seen)
  }

  @Test fun aCancelledTimerNeverFires() {
    val executor = SerialExecutor("togo-test")
    var fired = false
    val cancel = executor.after(0.05) { fired = true }
    cancel()
    Thread.sleep(150)
    assertEquals(false, executor.await { fired })
  }

  @Test fun knowsWhenTheCallerIsOnIt() {
    val executor = SerialExecutor("togo-test")
    assertFalse(executor.isCurrent)
    assertTrue(executor.await { executor.isCurrent })
  }

  /** Daemon threads: the SDK never keeps a JVM — a test runner, a CLI — alive. */
  @Test fun runsOnADaemonThread() {
    val executor = SerialExecutor("togo-test")
    assertTrue(executor.await { Thread.currentThread().isDaemon })
  }
}
