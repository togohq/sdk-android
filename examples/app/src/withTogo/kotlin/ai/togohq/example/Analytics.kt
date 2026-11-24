package ai.togohq.example

import ai.togohq.Togo
import ai.togohq.TogoConfig
import android.content.Context

/** What the app does with the SDK: set it up at launch, track a tap, send it now. */
object Analytics {
  // A test key in debug builds, so verify_tracking sees the emulator's events,
  // and the live key in release builds. Replace both with your project's keys,
  // or pass one to the build with -Ptogo.example.key.
  private val writeKey: String =
    BuildConfig.TOGO_WRITE_KEY.ifEmpty { if (BuildConfig.DEBUG) "togo_pkt_example" else "togo_pkl_example" }

  fun setup(context: Context) {
    val config = TogoConfig.Builder(writeKey)
    // Where to send, for a check on an emulator or a phone: the minified-build
    // test's local server, or -Ptogo.example.endpoint given to the build. An app
    // never sets this.
    config.endpoint(System.getProperty("togo.example.endpoint") ?: BuildConfig.TOGO_ENDPOINT.ifEmpty { null })
    Togo.setup(context, config.build())
  }

  fun buttonTapped() {
    Togo.track("button_tapped", mapOf("screen" to "main"))
    Togo.flush()
  }
}
