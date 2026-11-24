package ai.togohq.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test

class QueueCostTest {
  private fun entry(index: Int, padding: Int = 400) = EventQueue.Entry(
    RawEvent(
      "screen_viewed", 1_792_584_000_000, "e$index", "anon", "dev", "user_1", "s1",
      mapOf("screen" to str("home"), "index" to JsonValue.Int(index.toLong()), "note" to str("x".repeat(padding))),
      null, "Europe/Berlin",
    ),
    AppInfo("1.2.0"), null,
  )

  private fun backlog(count: Int) = (0 until count).joinToString("") { entry(it).line + "\n" }.toByteArray()

  /**
   * A backlog of 5,000 events in a FILE, as on a device: one more `track()` is
   * an append of one line, not a rewrite — and a sync — of megabytes on the
   * thread the getters wait on.
   */
  @Test fun oneMoreEventOnAFullBacklogFileIsAnAppend() {
    val dir = Files.createTempDirectory("togo").toFile()
    try {
      val file = File(dir, "queue.jsonl")
      file.writeBytes(backlog(EventQueue.MAX_EVENTS - 100))
      val sizeBefore = file.length()
      assertTrue("a backlog of megabytes", sizeBefore > 2_000_000)
      val counting = CountingStore(FileQueueStore(file))
      val queue = EventQueue(counting) { _, _ -> }
      queue.hydrate()
      repeat(5) { queue.add(entry(100_000 + it)) } // warm up
      val start = System.nanoTime()
      repeat(50) { queue.add(entry(200_000 + it)) }
      val perAddMs = (System.nanoTime() - start) / 50 / 1e6
      // Well under a millisecond per append on a laptop; a rewrite and sync of
      // the file each time is tens to hundreds. The bound sits far from both.
      // The structural half, which no fast disk can hide: not one byte of
      // the backlog was rewritten for an add.
      assertEquals("rewrites during adds", 0L, counting.rewritten)
      assertEquals(55, counting.appends)
      assertTrue("per add: $perAddMs ms", perAddMs < 5)
      val added = (0 until 5).sumOf { entry(100_000 + it).lineBytes + 1L } + (0 until 50).sumOf { entry(200_000 + it).lineBytes + 1L }
      assertEquals("each add grew the file by its line, nothing more", sizeBefore + added, file.length())
      // Read back by a new process, the file is the same queue.
      val reread = EventQueue(FileQueueStore(file)) { _, _ -> }
      reread.hydrate()
      assertEquals(queue.entries, reread.entries)
    } finally {
      dir.deleteRecursively()
    }
  }

  /** In memory too, one more event on a full backlog does not re-encode it. */
  @Test fun oneMoreEventOnAFullBacklogDoesNotReEncodeIt() {
    val store = MemoryQueueStore(backlog(EventQueue.MAX_EVENTS - 30))
    val queue = EventQueue(store) { _, _ -> }
    queue.hydrate()
    repeat(5) { queue.add(entry(EventQueue.MAX_EVENTS + it)) }
    val start = System.nanoTime()
    repeat(20) { queue.add(entry(EventQueue.MAX_EVENTS + 5 + it)) }
    val perAddMs = (System.nanoTime() - start) / 20 / 1e6
    assertTrue("per add: $perAddMs ms", perAddMs < 40)
    val reread = EventQueue(store) { _, _ -> }
    reread.hydrate()
    assertEquals(queue.entries, reread.entries)
  }

  /** A line cut off by a kill mid-append is skipped; every whole line before it is kept. */
  @Test fun aTornLastLineIsSkippedAndTheRestKept() {
    val whole = backlog(3)
    val torn = whole + entry(99).line.substring(0, 40).toByteArray()
    val store = MemoryQueueStore(torn)
    val queue = EventQueue(store) { _, _ -> }
    queue.hydrate()
    assertEquals(listOf("e0", "e1", "e2"), queue.entries.map { it.event.eventId })
    assertEquals("the torn line is gone from the file", String(whole), String(store.read()!!))
    queue.add(entry(3))
    val reread = EventQueue(store) { _, _ -> }
    reread.hydrate()
    assertEquals(listOf("e0", "e1", "e2", "e3"), reread.entries.map { it.event.eventId })
  }

  /**
   * A whole last line that lost its newline (a write cut just before it) is
   * kept, and the file is rewritten so the next append starts a line of its
   * own rather than joining the two into one that reads as neither.
   */
  @Test fun aLastLineWithNoNewlineIsKeptAndNotJoinedToTheNext() {
    val whole = backlog(2)
    val store = MemoryQueueStore(whole.copyOf(whole.size - 1))
    val queue = EventQueue(store) { _, _ -> }
    queue.hydrate()
    assertEquals(listOf("e0", "e1"), queue.entries.map { it.event.eventId })
    queue.add(entry(2))
    val reread = EventQueue(store) { _, _ -> }
    reread.hydrate()
    assertEquals(listOf("e0", "e1", "e2"), reread.entries.map { it.event.eventId })
  }

  /**
   * An append to a queue file that is there but will not open leaves it as it
   * is: never replaced by a file holding only the new line.
   */
  @Test fun anAppendThatCannotOpenTheFileNeverReplacesTheQueue() {
    val dir = Files.createTempDirectory("togo").toFile()
    try {
      val file = File(dir, "queue.jsonl")
      val store = FileQueueStore(file)
      val queued = backlog(3)
      store.write(queued)
      // Read-only: opening it for writing fails, as it would for a process
      // out of file descriptors.
      file.setWritable(false)
      assumeFalse(
        "this user can write a read-only file (root?), so the case cannot be made",
        file.canWrite(),
      )
      store.append((entry(3).line + "\n").toByteArray())
      assertArrayEquals("the queue is untouched", queued, file.readBytes())
    } finally {
      dir.listFiles()?.forEach { it.setWritable(true) }
      dir.deleteRecursively()
    }
  }

  /** The positive half: with no file, an append creates it. */
  @Test fun anAppendWithNoFileCreatesIt() {
    val dir = Files.createTempDirectory("togo").toFile()
    try {
      val file = File(dir, "queue.jsonl")
      val line = (entry(0).line + "\n").toByteArray()
      FileQueueStore(file).append(line)
      FileQueueStore(file).append(line)
      assertArrayEquals(line + line, file.readBytes())
    } finally {
      dir.deleteRecursively()
    }
  }

  /** The count bound still holds when the queue only appends. */
  @Test fun theCountBoundStillEvictsWhenAppending() {
    val dropped = ArrayList<String>()
    val store = MemoryQueueStore(backlog(EventQueue.MAX_EVENTS))
    val queue = EventQueue(store) { events, _ -> dropped += events.map { it.eventId } }
    queue.hydrate()
    queue.add(entry(9_999))
    assertEquals(listOf("e0"), dropped)
    assertEquals(EventQueue.MAX_EVENTS, queue.count)
    val reread = EventQueue(store) { _, _ -> }
    reread.hydrate()
    assertEquals(EventQueue.MAX_EVENTS, reread.count)
  }

  /**
   * The events an upload took no longer count against the byte bound in
   * memory: near the 8 MiB bound, adds during the upload append, and no add
   * rewrites the file for a total the queue does not hold. Events of ~2 KB, so
   * the byte bound is reached before the 5,000-event count.
   */
  @Test fun addsDuringAnUploadNearTheByteBoundStillAppend() {
    val backlog = ArrayList<EventQueue.Entry>()
    var total = 0
    while (true) {
      val next = entry(backlog.size, padding = 2_000)
      if (total + next.lineBytes + 1 > EventQueue.MAX_BYTES - 20_000) break
      total += next.lineBytes + 1
      backlog.add(next)
    }
    val store = CountingStore(MemoryQueueStore(backlog.joinToString("") { it.line + "\n" }.toByteArray()))
    val queue = EventQueue(store) { _, _ -> }
    queue.hydrate()
    val taken = queue.take(50, 1_792_584_000_000)
    val takenBytes = taken.sumOf { it.lineBytes + 1 }
    val added = (0 until 100).map { entry(100_000 + it, padding = 100) }
    val addedBytes = added.sumOf { it.lineBytes + 1 }
    // Past the bound only if the taken events were still counted.
    assertTrue(total + addedBytes > EventQueue.MAX_BYTES)
    assertTrue(total - takenBytes + addedBytes < EventQueue.MAX_BYTES)
    for (e in added) queue.add(e)
    assertEquals("every add appended", 0L, store.rewritten)
    assertEquals(100, store.appends)
  }

  /** A store that counts what is rewritten and what is appended. */
  private class CountingStore(private val inner: QueueStore) : QueueStore by inner {
    var rewritten = 0L
    var appends = 0
    override fun write(data: ByteArray): Boolean {
      rewritten += data.size
      return inner.write(data)
    }
    override fun append(data: ByteArray) {
      appends++
      inner.append(data)
    }
  }
}
