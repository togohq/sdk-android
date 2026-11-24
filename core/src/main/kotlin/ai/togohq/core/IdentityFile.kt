package ai.togohq.core

import java.io.File
import java.io.IOException

/**
 * The identity file: `device_id` and its mode, `anonymous_id`, `user_id`, the
 * session record and the last-seen app version and build, as one small JSON
 * file beside the queue in the app's no-backup directory. Never
 * `SharedPreferences`, which Auto Backup copies onto a new device by default,
 * and never `ANDROID_ID` or the advertising ID.
 *
 * **Unavailable is not absent.** Before the user first unlocks the device after
 * a reboot, the app's credential-protected storage cannot be read, and a
 * component that runs then (an FCM handler marked direct-boot aware) must not
 * take that for "no identity" and mint a second one for the same install. So:
 *
 * - the device is locked → unavailable, whether or not the file is there;
 * - the file is there and does not read → unavailable, whatever the reason;
 * - the file is not there → absent;
 * - the file reads but is not a record → absent, with a log line: it was
 *   written atomically, so something else wrote it, and starting again is the
 *   only way forward.
 *
 * **Only the session's activity time is coalesced.** `lastActivityAt` changes
 * on every event, so a write per `save()` would be a disk write per event: a
 * change to it alone is written [DEBOUNCE_SECONDS] later, or at once by
 * [persistNow] — the move to the background. Every other change — an id
 * minted, a user identified or logged out, a new session, a version — is
 * written at once, before the event that caused it reaches the queue file. A
 * process killed in between must never leave events on the disk under ids
 * the identity file does not hold, or a logout that never reached it.
 *
 * Called on the engine's executor; `scheduler` is where the debounced write
 * runs, which on a device is that same executor.
 */
internal class FileIdentityStore(
  val file: File,
  private val scheduler: TogoExecutor,
  private val isUnlocked: () -> Boolean = { true },
  private val log: Logger = standardErrorLogger,
) : IdentityStore {
  /** What was last read or saved. Once set, the file is not read again in this process: the memory copy is newer. */
  private var current: IdentityRecord? = null
  private var dirty = false
  private var cancelWrite: (() -> Unit)? = null
  /**
   * A withdrawal could not delete the file (a locked device): deleted at
   * [retryErase]. The engine never reads the file again after a withdrawal in
   * this process, so nothing else has to guard it.
   */
  private var eraseOwed = false

  @Synchronized override fun load(): IdentityLoad {
    current?.let { return IdentityLoad.Found(it) }
    if (!isUnlocked()) return IdentityLoad.Unavailable
    if (!file.exists()) return IdentityLoad.Absent
    val bytes = try {
      file.readBytes()
    } catch (_: IOException) {
      return IdentityLoad.Unavailable
    }
    val record = (JsonValue.parseOrNull(String(bytes, Charsets.UTF_8)) as? JsonValue.Obj)?.let(::identityRecord)
    if (record == null) {
      log.log("the identity file could not be read and was replaced: this install starts as new.")
      return IdentityLoad.Absent
    }
    current = record
    return IdentityLoad.Found(record)
  }

  @Synchronized override fun save(record: IdentityRecord) {
    val previous = current
    current = record
    dirty = true
    if (previous == null || withoutActivityTime(previous) != withoutActivityTime(record)) {
      persistNow()
      return
    }
    if (cancelWrite != null) return
    cancelWrite = scheduler.after(DEBOUNCE_SECONDS) { persistNow() }
  }

  /** Write what is pending, now. The move to the background calls it: a backgrounded process can be killed with no callback. */
  @Synchronized override fun persistNow() {
    cancelWrite?.invoke()
    cancelWrite = null
    val record = current
    if (!dirty || record == null) return
    // Locked storage cannot be written; the record stays in memory and the
    // next save or background move tries again.
    if (!isUnlocked()) return
    try {
      atomicWrite(file, identityJson(record).serialized().toByteArray(Charsets.UTF_8))
      dirty = false
    } catch (e: IOException) {
      // A full disk is expected operation, so not an error: the record is
      // still in memory, and the next save or background move tries again.
      log.log("the identity file could not be written (${e.message}); retrying at the next change.")
    }
  }

  private fun withoutActivityTime(record: IdentityRecord) = record.copy(session = record.session?.copy(lastActivityAtMs = 0))

  /**
   * A write still scheduled finds nothing dirty and writes nothing. Locked
   * storage cannot be deleted: the deletion is owed, and made at [retryErase].
   */
  @Synchronized override fun erase() {
    current = null
    dirty = false
    eraseOwed = true
    retryErase()
  }

  @Synchronized override fun retryErase() {
    // A record written since replaces the file; nothing to delete then.
    if (!eraseOwed || current != null) return
    if (isUnlocked() && (file.delete() || !file.exists())) eraseOwed = false
  }

  companion object {
    const val DEBOUNCE_SECONDS = 1.0
  }
}

/** The file's shape, the same as the iOS SDK's. `v` lets a later SDK read an older file. */
internal fun identityJson(record: IdentityRecord): JsonValue.Obj {
  val o = LinkedHashMap<String, JsonValue>()
  o["v"] = JsonValue.Int(1)
  record.deviceId?.let { o["deviceId"] = JsonValue.Str(it) }
  record.deviceIdMode?.let { o["deviceIdMode"] = JsonValue.Str(it) }
  record.anonymousId?.let { o["anonymousId"] = JsonValue.Str(it) }
  record.userId?.let { o["userId"] = JsonValue.Str(it) }
  record.appVersion?.let { o["appVersion"] = JsonValue.Str(it) }
  record.appBuild?.let { o["appBuild"] = JsonValue.Str(it) }
  record.session?.let {
    o["session"] = JsonValue.Obj(
      linkedMapOf(
        "id" to JsonValue.Str(it.id),
        "startedAtMs" to JsonValue.Int(it.startedAtMs),
        "lastActivityAtMs" to JsonValue.Int(it.lastActivityAtMs),
      ),
    )
  }
  return JsonValue.Obj(o)
}

internal fun identityRecord(json: JsonValue.Obj): IdentityRecord? {
  if ((json["v"] as? JsonValue.Int)?.value != 1L) return null
  val session = (json["session"] as? JsonValue.Obj)?.let { s ->
    val id = s.string("id")
    val started = (s["startedAtMs"] as? JsonValue.Int)?.value
    val last = (s["lastActivityAtMs"] as? JsonValue.Int)?.value
    if (id != null && started != null && last != null) SessionRecord(id, started, last) else null
  }
  return IdentityRecord(
    deviceId = json.string("deviceId"), deviceIdMode = json.string("deviceIdMode"),
    anonymousId = json.string("anonymousId"), userId = json.string("userId"), session = session,
    appVersion = json.string("appVersion"), appBuild = json.string("appBuild"),
  )
}
