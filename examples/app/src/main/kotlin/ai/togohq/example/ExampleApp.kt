package ai.togohq.example

import android.app.Application

/**
 * The example app: one screen, one button. The `withTogo` flavor sets the SDK up
 * here and sends an event from the button; the `baseline` flavor is the same app
 * with no SDK, so the size gate charges the SDK exactly what it adds.
 */
class ExampleApp : Application() {
  override fun onCreate() {
    super.onCreate()
    Analytics.setup(this)
  }
}
