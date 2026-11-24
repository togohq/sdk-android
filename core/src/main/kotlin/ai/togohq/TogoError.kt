package ai.togohq

/**
 * Why an event — or part of one — will not be recorded. The same codes as the
 * browser and iOS SDKs: the diagnostic catalog's where one exists, and a few
 * only an SDK can see. A class rather than an enum because a code from the edge
 * may be one added after this SDK was built; treat an unknown code as a drop.
 */
public data class TogoErrorCode(public val value: String) {
  override fun toString(): String = value

  public companion object {
    @JvmField public val EVENT_NAME_RESERVED: TogoErrorCode = TogoErrorCode("event_name_reserved")
    @JvmField public val EVENT_NAME_TOO_LONG: TogoErrorCode = TogoErrorCode("event_name_too_long")
    @JvmField public val EVENT_NAME_EMPTY: TogoErrorCode = TogoErrorCode("event_name_empty")
    @JvmField public val PROPERTY_RESERVED: TogoErrorCode = TogoErrorCode("property_reserved")
    @JvmField public val PERSON_PROPERTY_RESERVED: TogoErrorCode = TogoErrorCode("person_property_reserved")
    @JvmField public val PROPERTY_NAME_INVALID: TogoErrorCode = TogoErrorCode("property_name_invalid")
    @JvmField public val PERSON_PROPERTY_NAME_INVALID: TogoErrorCode = TogoErrorCode("person_property_name_invalid")
    /**
     * A property value that is not a string, a number, a boolean or null — a
     * map, a list, an object. Only an SDK sees it: the edge would refuse the
     * whole batch the value rode in, so the key is dropped here.
     */
    @JvmField public val PROPERTY_VALUE_INVALID: TogoErrorCode = TogoErrorCode("property_value_invalid")
    /**
     * A string value over 1,024 characters, cut to its first 1,024 — under a
     * test key, sent whole for the server to cut. The event is kept.
     */
    @JvmField public val ENVELOPE_PROPERTY_VALUE_TOO_LONG: TogoErrorCode =
      TogoErrorCode("envelope_property_value_too_long")
    /**
     * A map over 256 properties, which kept its first 256 in UTF-16 code-unit
     * order and dropped the rest — under a test key, sent whole for the server to
     * drop the same ones. The event is kept.
     */
    @JvmField public val ENVELOPE_PROPERTIES_TOO_MANY: TogoErrorCode =
      TogoErrorCode("envelope_properties_too_many")
    @JvmField public val TIMESTAMP_TOO_OLD: TogoErrorCode = TogoErrorCode("timestamp_too_old")
    @JvmField public val QUEUE_FULL: TogoErrorCode = TogoErrorCode("queue_full")
    /** An event whose JSON is over 256 KiB, dropped at `track()` and never sent. */
    @JvmField public val EVENT_TOO_LARGE: TogoErrorCode = TogoErrorCode("event_too_large")
    /**
     * `identify()` given an empty id or one over 128 characters. Only an SDK
     * sees it: the edge would refuse the whole batch the id rode in.
     */
    @JvmField public val USER_ID_INVALID: TogoErrorCode = TogoErrorCode("user_id_invalid")
    @JvmField public val WRITE_KEY_INVALID: TogoErrorCode = TogoErrorCode("write_key_invalid")
    @JvmField public val WRITE_KEY_REJECTED: TogoErrorCode = TogoErrorCode("write_key_rejected")
    @JvmField public val BATCH_TOO_LARGE: TogoErrorCode = TogoErrorCode("batch_too_large")
    @JvmField public val BATCH_REJECTED: TogoErrorCode = TogoErrorCode("batch_rejected")
    /**
     * The endpoint cannot be reached from this app however often it is tried:
     * not a URL, cleartext HTTP the platform blocks, a refused permission. Only
     * an SDK sees it. The client stops for this process and keeps the queue.
     */
    @JvmField public val ENDPOINT_INVALID: TogoErrorCode = TogoErrorCode("endpoint_invalid")
  }
}

/** What `onError` receives. Never thrown: no call on this SDK throws. */
public data class TogoError(
  public val code: TogoErrorCode,
  /** What the log shows, without the `[togo] ` prefix: the problem and the fix. */
  public val message: String,
  /**
   * The events this is about, by id: the events lost, or for
   * [TogoErrorCode.ENVELOPE_PROPERTY_VALUE_TOO_LONG] and [TogoErrorCode.ENVELOPE_PROPERTIES_TOO_MANY]
   * the event that was kept with a value cut or properties dropped. Empty when it is about the
   * client.
   */
  public val eventIds: List<String> = emptyList(),
  /** The raw event name, when it concerns one event. */
  public val eventName: String? = null,
  /**
   * The property name when it concerns one property — a reserved or unusable name, a value
   * that is not a primitive, or a value cut to the length cap — as the message shows it: its
   * first 64 characters, with an email address in it replaced. Never the value.
   */
  public val property: String? = null,
)

/** `TogoConfig.Builder.onError`'s callback, callable from Java as a lambda. */
public fun interface TogoErrorListener {
  public fun onError(error: TogoError)
}

/** `Togo.flush()`'s completion: runs after one upload attempt, on the SDK's callback thread. */
public fun interface FlushCallback {
  public fun onComplete()
}

/**
 * The consent state. Four states, and `setup()` is never delayed. The default
 * is [NOT_REQUIRED], NOT [PENDING]: a pending default would hold events forever
 * and send nothing for every app that never calls `setConsent`.
 */
public enum class TogoConsent(public val value: String) {
  NOT_REQUIRED("not_required"),
  PENDING("pending"),
  GRANTED("granted"),
  DENIED("denied");

  /** May the SDK store on this device and send from it? */
  internal val permitted: Boolean get() = this == NOT_REQUIRED || this == GRANTED
}
