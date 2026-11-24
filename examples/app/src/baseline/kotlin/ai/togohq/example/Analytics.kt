package ai.togohq.example

import android.content.Context

/** The same app without the SDK: what the size gate subtracts. */
object Analytics {
  @Suppress("UNUSED_PARAMETER")
  fun setup(context: Context) {}

  fun buttonTapped() {}
}
