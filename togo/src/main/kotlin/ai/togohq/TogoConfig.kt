package ai.togohq

/** What to do once consent is refused. Only [DROP] exists, and the type says so rather than accepting a value it would ignore. */
public enum class TogoOnDenied { DROP }

/**
 * `Togo.setup()`'s options, built with [Builder] so Java and Kotlin read the same:
 *
 * ```kotlin
 * val config = TogoConfig.Builder(if (BuildConfig.DEBUG) "togo_pkt_…" else "togo_pkl_…")
 *   .consent(TogoConsent.PENDING)
 *   .build()
 * ```
 *
 * Deliberately absent: a batch size and flush interval (fixed on a device, 50
 * events or 5 seconds), the 24-hour session cap (not configurable on any
 * platform), the web's cookie and page options, and a device id (the SDK owns
 * its identifiers).
 */
public class TogoConfig private constructor(builder: Builder) {
  /** The project's write key, `togo_pkl_…` or `togo_pkt_…`. Use the test key in debug builds. */
  public val writeKey: String = builder.writeKey
  /** Ingest origin. Defaults to the production edge. */
  public val endpoint: String = builder.endpoint
  /**
   * The idle window, in seconds. Default 1800 (30 minutes). **`<= 0` disables
   * sessions** and sends no `sessionId` at all — never read as instant expiry.
   * Seconds rather than a `Duration`: `kotlin.time.Duration` is awkward to call
   * from Java, and `java.time.Duration` needs API 26, above this library's minimum.
   */
  public val sessionTimeoutSeconds: Long = builder.sessionTimeoutSeconds
  /** Default [TogoConsent.NOT_REQUIRED]. [TogoConsent.PENDING] holds events in memory, stores and sends nothing, until granted. */
  public val consent: TogoConsent = builder.consent
  public val onDenied: TogoOnDenied = builder.onDenied
  /** The pending buffer's bound, oldest dropped. Default 100. */
  public val consentBufferMaxEvents: Int = builder.consentBufferMaxEvents
  /** Properties on every `track()` event. An event's own value for a key wins, and the collision is logged once. */
  public val defaultProperties: Map<String, Any?> = builder.defaultProperties
  /**
   * Your app's version and build. Read from the package (`versionName`,
   * `versionCode`) when not set; set them when those are not your release numbers.
   */
  public val appVersion: String? = builder.appVersion
  public val appBuild: String? = builder.appBuild
  /**
   * Called once for every event — or part of one — that will not be recorded,
   * in production too. Never for consent denied. Called on the SDK's callback
   * thread, after the call that caused it returns.
   */
  public val onError: TogoErrorListener? = builder.onError
  /** A wrapper SDK's own name and version, set by `TogoWrapper.set()` alone. */
  internal val wrapperName: String? = builder.wrapperName
  internal val wrapperVersion: String? = builder.wrapperVersion

  /**
   * Every setter takes null, as a Java caller can pass it: a null write key is
   * refused at `setup()` as an invalid one, and any other null keeps the default.
   */
  public class Builder(writeKey: String?) {
    internal val writeKey: String = writeKey ?: ""
    internal var endpoint: String = DEFAULT_ENDPOINT
    internal var sessionTimeoutSeconds: Long = 1800
    internal var consent: TogoConsent = TogoConsent.NOT_REQUIRED
    internal var onDenied: TogoOnDenied = TogoOnDenied.DROP
    internal var consentBufferMaxEvents: Int = 100
    internal var defaultProperties: Map<String, Any?> = emptyMap()
    internal var appVersion: String? = null
    internal var appBuild: String? = null
    internal var onError: TogoErrorListener? = null
    internal var wrapperName: String? = null
    internal var wrapperVersion: String? = null

    public fun endpoint(endpoint: String?): Builder = apply { if (endpoint != null) this.endpoint = endpoint }
    public fun sessionTimeoutSeconds(seconds: Long): Builder = apply { sessionTimeoutSeconds = seconds }
    public fun consent(consent: TogoConsent?): Builder = apply { if (consent != null) this.consent = consent }
    public fun onDenied(onDenied: TogoOnDenied?): Builder = apply { if (onDenied != null) this.onDenied = onDenied }
    public fun consentBufferMaxEvents(max: Int): Builder = apply { consentBufferMaxEvents = max }
    public fun defaultProperties(properties: Map<String, Any?>?): Builder = apply { defaultProperties = properties?.let { HashMap(it) } ?: emptyMap() }
    public fun appVersion(version: String?): Builder = apply { appVersion = version }
    public fun appBuild(build: String?): Builder = apply { appBuild = build }
    public fun onError(listener: TogoErrorListener?): Builder = apply { onError = listener }
    public fun build(): TogoConfig = TogoConfig(this)
  }

  public companion object {
    public const val DEFAULT_ENDPOINT: String = "https://in.togohq.ai"
  }
}

/**
 * A profile update for `Togo.setPersonProperties()`: `email` and `name` are
 * Togo's typed fields; `set`, `setOnce` and `unset` take your own keys.
 */
public class TogoPersonProperties private constructor(builder: Builder) {
  internal val email: String? = builder.email
  internal val removeEmail: Boolean = builder.removeEmail
  internal val name: String? = builder.name
  internal val removeName: Boolean = builder.removeName
  internal val set: Map<String, Any?>? = builder.set
  internal val setOnce: Map<String, Any?>? = builder.setOnce
  internal val unset: List<String>? = builder.unset

  public class Builder {
    internal var email: String? = null
    internal var removeEmail = false
    internal var name: String? = null
    internal var removeName = false
    internal var set: Map<String, Any?>? = null
    internal var setOnce: Map<String, Any?>? = null
    internal var unset: List<String>? = null

    /** `null` leaves the email alone; [removeEmail] removes it. */
    public fun email(email: String?): Builder = apply { this.email = email; removeEmail = false }
    /** Removes the person's email address. */
    public fun removeEmail(): Builder = apply { email = null; removeEmail = true }
    /** `null` leaves the name alone; [removeName] removes it. */
    public fun name(name: String?): Builder = apply { this.name = name; removeName = false }
    /** Removes the person's name. */
    public fun removeName(): Builder = apply { name = null; removeName = true }
    /** Overwrites these keys. */
    public fun set(properties: Map<String, Any?>?): Builder = apply { set = properties?.let { HashMap(it) } }
    /** Sets these keys only where the person has no value yet. */
    public fun setOnce(properties: Map<String, Any?>?): Builder = apply { setOnce = properties?.let { HashMap(it) } }
    /** Removes these keys. */
    public fun unset(keys: List<String?>?): Builder = apply { unset = keys?.filterNotNull() }
    public fun build(): TogoPersonProperties = TogoPersonProperties(this)
  }
}
