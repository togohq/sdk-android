package ai.togohq.core

import java.util.UUID

/**
 * The two clocks the SDK reads.
 *
 * - [wallMs] — the device's wall clock, epoch milliseconds. Every
 *   `clientEventTs` and `clientSentTs`, the 24-hour cap, and idle time across a
 *   process restart. The edge corrects it with four timestamps.
 * - [monotonicMs] — milliseconds from a fixed point, immune to the wall clock
 *   being moved. Idle time within one process, so an NTP correction or a manual
 *   change mid-session neither extends nor cuts it.
 *
 * An interface so the scenario runner and the tests can drive both.
 */
internal interface Clock {
  fun wallMs(): Long
  fun monotonicMs(): Long
}

internal object SystemClock : Clock {
  override fun wallMs(): Long = System.currentTimeMillis()

  /** `System.nanoTime()` is monotonic on the JVM and on Android. */
  override fun monotonicMs(): Long = System.nanoTime() / 1_000_000
}

/**
 * ISO-8601 UTC with milliseconds and a `Z` (`2026-10-21T12:00:00.000Z`) — the
 * one format stored times take. Arithmetic rather than a formatter, so the
 * output cannot vary with a locale or a calendar setting, and needs no
 * `java.time` (API 26, above this library's minimum).
 */
internal object IsoTime {
  fun format(epochMs: Long): String {
    val millis = Math.floorMod(epochMs, 1000L).toInt()
    val seconds = Math.floorDiv(epochMs, 1000L)
    val days = Math.floorDiv(seconds, 86_400L)
    val rest = Math.floorMod(seconds, 86_400L).toInt()
    val (year, month, day) = civil(days)
    return pad(year, 4) + "-" + pad(month, 2) + "-" + pad(day, 2) + "T" +
      pad(rest / 3600, 2) + ":" + pad((rest % 3600) / 60, 2) + ":" + pad(rest % 60, 2) + "." +
      pad(millis, 3) + "Z"
  }

  private fun pad(value: Long, width: Int): String = value.toString().padStart(width, '0')
  private fun pad(value: Int, width: Int): String = value.toString().padStart(width, '0')

  /**
   * `YYYY-MM-DDTHH:MM:SS(.fff)Z` back to epoch milliseconds; `null` for any
   * other shape (only this SDK's own output is ever parsed).
   */
  fun parse(text: String): Long? {
    if (text.length < 20 || !text.endsWith("Z")) return null
    fun num(from: Int, count: Int): Int? =
      if (from + count > text.length) null else text.substring(from, from + count).toIntOrNull()
    val year = num(0, 4) ?: return null
    val month = num(5, 2) ?: return null
    val day = num(8, 2) ?: return null
    val hour = num(11, 2) ?: return null
    val minute = num(14, 2) ?: return null
    val second = num(17, 2) ?: return null
    var millis = 0
    if (text.length > 20 && text[19] == '.') {
      val digits = text.length - 21
      if (digits < 1 || digits > 9) return null
      var scaled = num(20, digits) ?: return null
      repeat(maxOf(0, digits - 3)) { scaled /= 10 }
      repeat(maxOf(0, 3 - digits)) { scaled *= 10 }
      millis = scaled
    }
    val days = daysFromCivil(year.toLong(), month, day)
    return (days * 86_400L + hour * 3600L + minute * 60L + second) * 1000L + millis
  }

  // Howard Hinnant's civil-calendar algorithms: proleptic Gregorian, UTC.
  private fun civil(z0: Long): Triple<Long, Int, Int> {
    val z = z0 + 719_468
    val era = (if (z >= 0) z else z - 146_096) / 146_097
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val year = yoe + era * 400 + (if (month <= 2) 1 else 0)
    return Triple(year, month, day)
  }

  private fun daysFromCivil(y0: Long, month: Int, day: Int): Long {
    val y = if (month <= 2) y0 - 1 else y0
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val mp = if (month > 2) month - 3 else month + 9
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097 + doe - 719_468
  }
}

/**
 * A random, opaque id: a v4 UUID. Event ids, the anonymous id, session ids and
 * the install's device id. Never derived from a timestamp — an id with a
 * readable start time in it gets charted as one.
 */
internal fun randomId(): String = UUID.randomUUID().toString()
