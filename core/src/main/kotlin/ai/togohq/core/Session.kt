package ai.togohq.core

/**
 * The session, decided HERE. The server stores what it is given and has no
 * sessionization of its own. A port of the browser SDK's sessions with a
 * native app's two clocks.
 *
 * `timeoutSeconds <= 0` DISABLES sessions: no `sessionId` at all. It must never
 * be read as instant expiry, which would mint one session per event. NaN, a
 * mis-read setting, gets the default rather than a cut that never fires.
 */
internal class Sessions(timeoutSeconds: Double?, log: Logger) {
  /** `sdk` or `disabled`, sent per batch as `sessionIdMode`. */
  val mode: String
  private val timeoutMs: Long

  /**
   * The session this process last touched, with the MONOTONIC time of that
   * touch. Idle within one process is measured on it, so a wall clock moved
   * mid-session neither extends nor cuts the session; a record restored after a
   * relaunch has no baseline and falls back to its persisted wall clock, since
   * nothing monotonic survives a reboot.
   */
  private var baseline: Pair<String, Long>? = null

  init {
    var seconds = timeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS
    if (seconds.isNaN()) seconds = DEFAULT_TIMEOUT_SECONDS
    if (seconds > HARD_CAP_MS / 1000) {
      // Past the cap the value changes nothing, so it is probably milliseconds.
      log.log("sessionTimeout is seconds, max 86400")
    }
    timeoutMs = if (seconds <= 0) {
      // Negative infinity included: any value at or below zero disables.
      0
    } else {
      // Clamped BEFORE the conversion: past the cap the value changes nothing,
      // and a huge or infinite value must neither overflow nor turn sessions
      // off. A positive value under a millisecond is still a timeout, never "off".
      val capped = minOf(seconds, (HARD_CAP_MS / 1000).toDouble())
      maxOf(1L, (capped * 1000).toLong())
    }
    mode = if (timeoutMs <= 0) "disabled" else "sdk"
  }

  val disabled: Boolean get() = mode == "disabled"

  data class Touch(val record: SessionRecord?, val id: String?, val started: Boolean)

  /**
   * Called on EVERY event. Returns the record to keep, the id to stamp (`null`
   * for none) and whether this call created the session. `canStart` is false
   * when no human is present (a background start): such an event joins an open
   * session and never opens one.
   */
  fun touch(record: SessionRecord?, wallMs: Long, monotonicMs: Long, canStart: Boolean, mintId: () -> String): Touch {
    if (disabled) return Touch(record, null, false)
    if (record != null && !isOver(record, wallMs, monotonicMs)) {
      // `lastActivityAt` advances on every event with a human behind it.
      if (!canStart) return Touch(record, record.id, false)
      baseline = record.id to monotonicMs
      return Touch(record.copy(lastActivityAtMs = wallMs), record.id, false)
    }
    if (!canStart) return Touch(record, null, false)
    // Opaque and random. Never `deviceId:startTime`.
    val fresh = SessionRecord(mintId(), wallMs, wallMs)
    baseline = fresh.id to monotonicMs
    return Touch(fresh, fresh.id, true)
  }

  /**
   * The app went to the background with this session open: background time is
   * idle time, so the idle clock starts now. `null` when the session is over and
   * is left to end.
   */
  fun markActivity(record: SessionRecord, wallMs: Long, monotonicMs: Long): SessionRecord? {
    if (disabled || isOver(record, wallMs, monotonicMs)) return null
    baseline = record.id to monotonicMs
    return record.copy(lastActivityAtMs = wallMs)
  }

  /** Read-only: never mints, never extends, never reports a session that has ended. */
  fun current(record: SessionRecord?, wallMs: Long, monotonicMs: Long): String? {
    if (disabled || record == null || isOver(record, wallMs, monotonicMs)) return null
    return record.id
  }

  /** `reset()`: the next event opens a new session. */
  fun forget() {
    baseline = null
  }

  /** Three cuts. A NEGATIVE interval cuts too: a clock that moved backwards must not extend a session. */
  private fun isOver(record: SessionRecord, wallMs: Long, monotonicMs: Long): Boolean {
    val age = wallMs - record.startedAtMs
    if (age < 0 || age > HARD_CAP_MS) return true
    val b = baseline
    val idle = if (b != null && b.first == record.id) monotonicMs - b.second else wallMs - record.lastActivityAtMs
    return idle < 0 || idle > timeoutMs
  }

  companion object {
    /** 30 minutes, the same on every platform. */
    const val DEFAULT_TIMEOUT_SECONDS = 1800.0
    /** 24 hours, and NOT configurable: the idle timeout alone never fires for an app held in front. */
    const val HARD_CAP_MS = 86_400_000L
  }
}

/**
 * One event held while consent is pending or the identity cannot be read: in
 * MEMORY, with no device identifier, and discarded on termination — not
 * storage. The `userId` is the caller's assertion at the call, kept so a release
 * cannot write one user's profile to the next.
 */
internal data class HeldEvent(
  val eventName: String,
  val properties: Props,
  val clientEventMs: Long,
  val eventId: String,
  val userId: String?,
  val personProperties: PersonPropertyOps?,
  val timezone: String?,
  val app: AppInfo?,
  val device: DeviceInfo?,
  /**
   * Held before the identity record was read, with no `identify()` or
   * `reset()` before it in this process: whoever the record says is signed in
   * — the user from the last launch — is the user of this event. Read at the
   * release, once the record is.
   */
  val inheritsStoredUser: Boolean = false,
)
