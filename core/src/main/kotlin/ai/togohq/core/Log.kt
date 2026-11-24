package ai.togohq.core

/**
 * The SDK's console. Every line is prefixed `[togo] `. Injected so a test or the
 * scenario runner can read what a developer would see; the Android module
 * writes to logcat.
 */
internal fun interface Logger {
  fun log(message: String)
}

internal val standardErrorLogger = Logger { message -> System.err.println("[togo] $message") }
