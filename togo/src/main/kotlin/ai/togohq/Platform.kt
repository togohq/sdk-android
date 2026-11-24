package ai.togohq

import ai.togohq.core.AppInfo
import ai.togohq.core.Clock
import ai.togohq.core.DeviceInfo
import android.content.Context
import android.os.Build
import java.util.TimeZone

/**
 * The device's clocks. The monotonic one is `elapsedRealtime()`, which counts
 * through deep sleep: a phone asleep in a pocket for six hours has been idle
 * for six hours, and `System.nanoTime()` (which stops in deep sleep) would keep
 * that session alive across them.
 */
internal object AndroidClock : Clock {
  override fun wallMs(): Long = System.currentTimeMillis()
  override fun monotonicMs(): Long = android.os.SystemClock.elapsedRealtime()
}

/** What the platform says about the app and the device, read once at `setup()`. */
internal object Platform {
  /** The app's version and build: the options when given, the package otherwise. */
  fun app(context: Context, version: String?, build: String?): AppInfo? {
    val info = try {
      @Suppress("DEPRECATION")
      context.packageManager.getPackageInfo(context.packageName, 0)
    } catch (_: Exception) {
      null
    }
    val code = info?.let {
      if (Build.VERSION.SDK_INT >= 28) {
        it.longVersionCode
      } else {
        @Suppress("DEPRECATION")
        it.versionCode.toLong()
      }
    }
    val app = AppInfo(version ?: info?.versionName, build ?: code?.toString())
    return if (app.version == null && app.build == null) null else app
  }

  /**
   * `deviceType` is `tablet` at a smallest width of 600 dp or more, otherwise
   * `mobile` — the vocabulary the web and iOS already send, so one breakdown
   * spans all three.
   */
  fun device(context: Context): DeviceInfo = DeviceInfo(
    os = "Android",
    osVersion = Build.VERSION.RELEASE,
    model = Build.MODEL,
    deviceType = if (context.resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "mobile",
    manufacturer = Build.MANUFACTURER,
  )

  /**
   * When the package was first installed on this device: what tells a new
   * install from an existing user's first launch of the release that added the
   * SDK. An update keeps it, including one Play applied before the first open.
   */
  fun firstInstallMs(context: Context): Long? = try {
    @Suppress("DEPRECATION")
    context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime.takeIf { it > 0 }
  } catch (_: Exception) {
    null
  }

  fun timezone(): String? = TimeZone.getDefault().id?.takeIf { it.isNotEmpty() }
}
