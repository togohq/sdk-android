package ai.togohq.core

import java.io.File
import java.io.IOException

// Where the SDK keeps what it remembers on the device.
//
// Interfaces, so the core never names a platform. The Android library supplies
// the files; the memory stores are what the tests and the scenario runner use.

/** The session record, written as a unit. Wall-clock milliseconds, because it outlives the process. */
internal data class SessionRecord(val id: String, val startedAtMs: Long, val lastActivityAtMs: Long)

/**
 * Everything the SDK remembers about this install, as one record so it is
 * written as one file. `null` fields are not yet minted: identifiers are minted
 * lazily, at the first write consent permits, never at `setup()`.
 */
internal data class IdentityRecord(
  val deviceId: String? = null,
  val deviceIdMode: String? = null,
  val anonymousId: String? = null,
  val userId: String? = null,
  val session: SessionRecord? = null,
  /** The app version and build the last launch ran: what `$app_updated` compares against. */
  val appVersion: String? = null,
  val appBuild: String? = null,
)

/**
 * What a load found. **[Unavailable] is not [Absent]**: before the first unlock
 * after a reboot the identity file cannot be read, and treating that as "no
 * identity" would mint a second one for the same install. The core holds events
 * in memory while it is unavailable and never mints.
 */
internal sealed class IdentityLoad {
  object Absent : IdentityLoad()
  object Unavailable : IdentityLoad()
  data class Found(val record: IdentityRecord) : IdentityLoad()
}

/** The store owns write coalescing, so `save` may be called on every event. */
internal interface IdentityStore {
  fun load(): IdentityLoad
  fun save(record: IdentityRecord)
  /** Write anything pending now: the process may be killed with no callback. */
  fun persistNow() {}
  /**
   * Consent withdrawal: delete what is stored, not merely stop. A store that
   * cannot delete yet (a locked device) owes the deletion and makes it at
   * [retryErase].
   */
  fun erase()
  /** The device was unlocked: make a deletion [erase] could not. */
  fun retryErase() {}
}

/** Identity in memory. A test hands the same instance to a second client to stand for a relaunch. */
internal class MemoryIdentityStore(record: IdentityRecord? = null) : IdentityStore {
  @Volatile var stored: IdentityRecord? = record
    private set

  /** When true, `load()` answers [IdentityLoad.Unavailable], as a locked device would. */
  @Volatile var locked: Boolean = false

  override fun load(): IdentityLoad {
    if (locked) return IdentityLoad.Unavailable
    return stored?.let { IdentityLoad.Found(it) } ?: IdentityLoad.Absent
  }

  override fun save(record: IdentityRecord) {
    stored = record
  }

  override fun erase() {
    stored = null
  }
}

/** A device id and where it came from — `device_id_mode`, and so how long it lasts. */
internal data class DeviceIdentity(val id: String, val mode: String)

/**
 * Where `device_id` comes from. Asked once per install, at the first permitted
 * write; the answer is stored in the identity record and never asked again.
 * `null` means "not now", and the core then holds events rather than falling
 * back to another id.
 */
internal fun interface DeviceIdProvider {
  fun deviceId(): DeviceIdentity?
}

/**
 * A random UUID per install, mode `install`. Never `ANDROID_ID` and never the
 * advertising ID: a self-generated id is what the platform recommends for
 * analytics, and it never follows a backup onto other hardware.
 */
internal val generatedDeviceIds = DeviceIdProvider { DeviceIdentity(randomId(), "install") }

// The queue's bytes.

/**
 * The durable queue's persistence: one entry per line, read once, appended to
 * per event and rewritten whole when the queue changes otherwise.
 */
internal interface QueueStore {
  fun read(): ByteArray?
  /** `false` when the file could not be written. */
  fun write(data: ByteArray): Boolean
  fun append(data: ByteArray)
  /** `false` when a file is still there: it could not be deleted (a locked device). */
  fun remove(): Boolean
}

internal class MemoryQueueStore(data: ByteArray? = null) : QueueStore {
  @Volatile private var data: ByteArray? = data
  override fun read(): ByteArray? = data
  /** When true, the store refuses to delete or replace its data, as a locked device's file system does. */
  @Volatile var stuck = false
  override fun write(data: ByteArray): Boolean {
    if (stuck) return false
    this.data = data
    return true
  }
  override fun append(data: ByteArray) {
    this.data = (this.data ?: ByteArray(0)) + data
  }
  override fun remove(): Boolean {
    if (stuck) return data == null
    data = null
    return true
  }
}

/**
 * The queue as a file, written atomically — to a sibling and renamed over the
 * original — so a process killed mid-write leaves the old file or the new one,
 * never half of either. It survives the app being killed, for days.
 */
internal class FileQueueStore(val file: File) : QueueStore {
  override fun read(): ByteArray? = try {
    if (file.exists()) file.readBytes() else null
  } catch (_: IOException) {
    null
  }

  override fun write(data: ByteArray): Boolean = try {
    atomicWrite(file, data)
    true
  } catch (_: IOException) {
    // A full disk is expected operation on a device, so not an error: the
    // events stay in memory for this process.
    false
  }

  /**
   * An append is not synced to the disk: a killed process loses nothing the
   * kernel already holds, and syncing every event is the cost the append
   * exists to avoid. A power cut can lose the last few; a rewrite, once per
   * batch, is synced.
   *
   * Opening in append mode creates a file that is not there and fails on one
   * that is there and will not open, leaving it as it is — never replacing the
   * queue with the new line alone. A write that fails part-way (a full disk)
   * is cut back to where it started, so the next append does not land on a
   * broken line and take the event it carries down with it. Either way the
   * events stay in memory for this process, and the next rewrite puts them in
   * the file.
   */
  override fun append(data: ByteArray) {
    try {
      file.parentFile?.mkdirs()
      java.io.FileOutputStream(file, true).use { out ->
        val start = out.channel.size()
        try {
          out.write(data)
        } catch (e: IOException) {
          try {
            out.channel.truncate(start)
          } catch (_: IOException) {
            // Nothing more can be done here: the next append in this process
            // joins the broken line and is lost from the file with it, until
            // the next rewrite puts both back from memory. A read before that
            // skips the joined line and keeps the rest.
          }
          throw e
        }
      }
    } catch (_: IOException) {
      // As for a write: the events stay in memory for this process.
    }
  }

  override fun remove(): Boolean = file.delete() || !file.exists()
}

/** Write to `<name>.tmp` beside the file, flush it to the disk, and rename it over the original. */
internal fun atomicWrite(file: File, data: ByteArray) {
  file.parentFile?.mkdirs()
  val temp = File(file.parentFile, file.name + ".tmp")
  java.io.FileOutputStream(temp).use { out ->
    out.write(data)
    out.fd.sync()
  }
  if (!temp.renameTo(file)) {
    temp.delete()
    throw IOException("could not replace ${file.name}")
  }
}
