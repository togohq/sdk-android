package ai.togohq.wrapper

import ai.togohq.TogoConfig

/**
 * For an SDK built over this one — the Solar2D plugin — and nothing else.
 *
 * Its events name the wrapper (`sdk-solar2d`) instead of `sdk-android`, so the
 * wrapper is what a breakdown by SDK counts; the server reads the platform from
 * the batch's device, which this SDK still fills. In its own package so an app
 * never meets it beside `Togo` and `TogoConfig`. A name or version the server
 * would not read is ignored at `Togo.setup()` with one log line, and events name
 * `sdk-android`.
 */
public object TogoWrapper {
  /**
   * Name the wrapper on a builder before `build()`: `name` is `sdk-<runtime>` in
   * lowercase (at most 64 characters), `version` the wrapper's own (at most 32).
   * A null builder does nothing. A null name or version — a build field that was
   * never filled — is refused at `Togo.setup()` like any other, with its one log
   * line, so events name this SDK and logcat says why.
   */
  @JvmStatic
  public fun set(builder: TogoConfig.Builder?, name: String?, version: String?) {
    if (builder == null || (name == null && version == null)) return
    builder.wrapperName = name ?: ""
    builder.wrapperVersion = version ?: ""
  }
}
