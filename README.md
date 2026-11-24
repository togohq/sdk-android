# Togo AI Android SDK

The Kotlin SDK for Android apps: `track`, `identify`, `setPersonProperties`, `reset`, consent,
sessions, and an offline queue that survives the app being killed. Callable from Kotlin and Java.

Published as `ai.togohq:sdk-android` to Togo's Maven repository, `https://maven.togohq.ai`. Add the
repository beside the ones you use, then the library:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
    maven("https://maven.togohq.ai")
  }
}

// app/build.gradle.kts
dependencies {
  implementation("ai.togohq:sdk-android:1.0.0")
}
```

Every file there is signed by the release key, and a published version is never replaced.

Android 5.0 (API 21) or later. The Kotlin standard library is its only dependency: no AndroidX, no
coroutines, no OkHttp, no Google Play services.

## Use

```kotlin
import ai.togohq.Togo
import ai.togohq.TogoConfig

class App : Application() {
  override fun onCreate() {
    super.onCreate()
    // A test key in debug builds: verify_tracking sees the emulator's events.
    val key = if (BuildConfig.DEBUG) "togo_pkt_…" else "togo_pkl_…"
    Togo.setup(this, TogoConfig.Builder(key).build())
  }
}

Togo.track("plan_selected", mapOf("plan_id" to "pro", "seats" to 5))
Togo.identify("account_8412")
Togo.setPersonProperties(email = "ada@example.com", name = "Ada")
Togo.reset() // at logout
```

From Java the same calls are static: `Togo.setup(this, new TogoConfig.Builder(key).build())`,
`Togo.track("plan_selected", properties)` with a `Map<String, Object>`, and
`Togo.flush(() -> { … })`.

Every call can be made from any thread and never waits on the network. No call throws: a refused
call logs one `[togo]` line saying why, and `TogoConfig.Builder.onError` receives it too. A property
value is a string, a number, a boolean or null; anything else — a map, a list, an object — is left
out of the event and reported.

Call `Togo.setup()` in `Application.onCreate()`. Set up inside an activity's `onCreate()` or
`onStart()`, it still sees that activity when it resumes. Set up any later — after the activity's
`onResume()` — it cannot: no `$app_opened` is sent and events carry no session until another activity
starts or the app comes back from the background, and the SDK logs one line saying so.

## What it stores

Two files in the app's no-backup directory, which Android's Auto Backup never copies whatever the
app's backup rules say, so neither follows a backup onto another phone: the identity (a device id
the SDK generates, the anonymous id, the signed-in user, the session and the last app version seen)
and the queue. The SDK never reads `ANDROID_ID` or the advertising ID. Uninstalling the app or
clearing its data deletes both files, so a reinstall is a new device.

Events are sent in batches of 50 or every 5 seconds, and when the app goes to the background. An
event that cannot be sent — offline, or the server busy — stays queued and is sent later, for up to
seven days.

Before the user first unlocks the phone after a reboot, the app's storage cannot be read. A
component that runs then (a direct-boot-aware push handler, say) can still call `track()`: the SDK
holds up to 100 events in memory, creates no ids, and sends them under the stored ones once the
phone is unlocked.

## App lifecycle events

Sent with no code of yours:

- `$app_installed` at the first launch of a new install. A launch with no stored identity whose
  install is more than seven days old — an existing user's first launch of the release that added
  Togo, or cleared data — sends nothing, so adding Togo does not count every existing user as an
  install. An install first opened more than a week after it was installed is not counted either.
- `$app_updated` at the first launch of a new version or build, with `previous_app_version` and
  `previous_app_build`.
- `$app_opened` when the app comes to the front, with `from_background` (false the first time in a
  process). A rotation, moving between your activities or one split-screen window closing is not
  leaving the app: the SDK waits 700 ms after the last activity stops before it counts the app as
  in the background.

A process Android starts for a broadcast, a job, a push or a content provider shows no activity, so
it sends no `$app_opened` and its events open no session.

## One process

The SDK runs in the app's main process only. `Togo.setup()` called in another process
(`android:process`) logs one line and every call there does nothing, because two processes writing
the same files would corrupt them.

## Consent

`TogoConfig.Builder.consent(TogoConsent.PENDING)` holds events in memory and stores and sends
nothing until `Togo.setConsent(TogoConsent.GRANTED)`. `TogoConsent.DENIED` deletes what the SDK
stored on the device. The default, `NOT_REQUIRED`, sends from the first event.

## Sessions

A session ends after 30 minutes without an event (`sessionTimeoutSeconds`) or after 24 hours,
whichever comes first.
`sessionTimeoutSeconds(0)` turns sessions off: events carry no session id at all.

## Development

```bash
./gradlew build check
```

That is the whole test: the Gradle wrapper is checked in, and it needs two JDKs and the Android SDK
with platform 36 (`ANDROID_HOME`, or `sdk.dir` in `local.properties`). **JDK 17** compiles the
code: `jvmToolchain(17)` makes Gradle look for an installed 17 — a newer JDK does not count, and
Gradle downloads none. **JDK 21** runs Gradle, so `JAVA_HOME` points at it: the Robolectric tests
run on Gradle's JVM, and Robolectric refuses Android SDK 36 on anything older than Java 21. The core
(`core/`) is plain Kotlin with no Android import and is tested on the JVM; the library (`togo/`)
compiles the core into itself and is tested with Robolectric. Nothing here needs an emulator.

## License

Apache-2.0. See [LICENSE](LICENSE).
