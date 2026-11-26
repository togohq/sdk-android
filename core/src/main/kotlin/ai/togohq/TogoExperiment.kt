package ai.togohq

/** What `getExperiment()` answers. The same five statuses as the web, server and iOS SDKs. */
public enum class TogoExperimentStatus(public val value: String) {
  DRAFT("draft"),
  ACTIVE("active"),
  FINISHED("finished"),
  NOT_FOUND("not_found"),
  UNAVAILABLE("unavailable");

  internal companion object {
    fun from(value: String): TogoExperimentStatus? = entries.firstOrNull { it.value == value }
  }
}

/**
 * One experiment's answer for this device. A `null` [variant] means: run what you would run
 * without Togo. [participating] is true only when an exposure is recorded for this unit.
 */
public data class TogoExperiment(
  public val experiment: String,
  public val status: TogoExperimentStatus,
  public val variant: String?,
  public val participating: Boolean,
) {
  internal companion object {
    fun unavailable(name: String) = TogoExperiment(name, TogoExperimentStatus.UNAVAILABLE, null, false)
  }
}

/** `Togo.getExperiment()`'s callback, callable from Java as a lambda. Runs on the main thread. */
public fun interface ExperimentCallback {
  public fun onResult(result: TogoExperiment)
}
