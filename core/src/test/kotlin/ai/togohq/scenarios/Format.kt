package ai.togohq.scenarios

import ai.togohq.core.JsonValue
import java.io.File

/**
 * The scenario format, read by the Kotlin core (the shared scenarios' README).
 *
 * A port of the browser SDK's scenario module — loading, `include` expansion
 * and the planner — and it has to be EXACT: the planner's seeded gaps and start
 * times decide every `clientEventTs` in `expected/`, so a rounding difference
 * here is a golden that never matches. `mulberry32` in unsigned 32-bit
 * arithmetic is the same in every runtime, which is why the format chose it.
 *
 * The browser runner's validator is the AUTHORITY on what a scenario may say;
 * this one refuses only what it cannot perform — an unknown verb, step key or
 * `init` option — so a verb added to the format before this SDK can perform it
 * fails here rather than being skipped.
 */
internal object ScenarioFormat {
  private const val SECOND = 1000L
  private const val MINUTE = 60 * SECOND
  private const val HOUR = 60 * MINUTE
  private const val DAY = 24 * HOUR
  const val WINDOW_MS = 6 * DAY + 12 * HOUR
  const val END_MARGIN_MS = MINUTE
  private const val GAP_MIN = 2 * SECOND
  private const val GAP_MAX = 20 * SECOND

  private val verbs = listOf("track", "identify", "setPersonProperties", "setConsent", "reset", "flush")
  private val stepKeys = (verbs + listOf("include", "properties", "at", "actor", "device", "expectRejected")).toSet()
  private val initKeys = setOf("consent", "onDenied", "sessionTimeoutSeconds", "defaultProperties")

  /**
   * The shared scenarios: `scenarios/` inside this library's directory where it
   * is its own repository (a release copies them in), else `../scenarios`
   * beside it, where the SDKs live together. Never neither: a run that found no
   * scenarios would check nothing, so with neither present the second path is
   * returned and the first read fails, naming it.
   */
  val defaultRoot: File by lazy {
    val library = File(System.getProperty("togo.library") ?: File("..").absolutePath).absoluteFile
    val own = File(library, "scenarios")
    if (File(own, "scenarios").isDirectory) own else File(library.parentFile, "scenarios")
  }

  fun scenarioNames(root: File = defaultRoot): List<String> =
    (File(root, "scenarios").list() ?: throw ScenarioError("no scenarios under ${root.path}"))
      .filter { it.endsWith(".json") }
      .map { it.removeSuffix(".json") }
      .sorted()

  fun loadScenario(name: String, root: File = defaultRoot): Scenario {
    val raw = readJson(File(root, "scenarios/$name.json"))
    val problems = ArrayList<String>()
    val init = (raw["init"] as? JsonValue.Obj)?.values ?: emptyMap()
    for (key in init.keys) if (key !in initKeys) problems += "init.$key is not an option this runner can pass"
    val steps = expand((raw["steps"] as? JsonValue.Arr)?.values ?: emptyList(), root, listOf(name), problems)
    for (step in steps) {
      val o = step.json.values
      val found = verbs.filter { o.containsKey(it) }
      if (found.size != 1) problems += "${step.origin}: exactly one verb this runner performs (found $found)"
      for (key in o.keys) if (key !in stepKeys) problems += "${step.origin}: unknown key \"$key\""
    }
    if (problems.isNotEmpty()) throw ScenarioError("scenario $name: ${problems.joinToString("; ")}")
    return Scenario(name, init, (raw["deviceClockOffsetMs"] as? JsonValue.Int)?.value ?: 0, steps)
  }

  /**
   * The native profile (`native/`): the device verbs plus the three only an
   * app has. One device, no `server` actor and no includes, so the steps are
   * one app's life and nothing else.
   */
  private val nativeVerbs = verbs + listOf("launch", "foreground", "background", "getExperiment", "cachedExperiment")
  private val nativeStepKeys = (nativeVerbs + listOf("properties", "at")).toSet()

  fun nativeScenarioNames(root: File = defaultRoot): List<String> =
    (File(root, "native").list() ?: throw ScenarioError("no native scenarios under ${root.path}"))
      .filter { it.endsWith(".json") }
      .map { it.removeSuffix(".json") }
      .sorted()

  /** A native scenario: the first step is a `launch`, every `launch` names its build, and `foreground`/`background` name a scene. */
  fun loadNativeScenario(name: String, root: File = defaultRoot): Scenario {
    val raw = readJson(File(root, "native/$name.json"))
    val problems = ArrayList<String>()
    val init = (raw["init"] as? JsonValue.Obj)?.values ?: emptyMap()
    for (key in init.keys) if (key !in initKeys) problems += "init.$key is not an option this runner can pass"
    val steps = ((raw["steps"] as? JsonValue.Arr)?.values ?: emptyList()).mapIndexed { index, step ->
      Step(step as? JsonValue.Obj ?: JsonValue.Obj(emptyMap()), "$name.steps[$index]")
    }
    steps.forEachIndexed { index, step ->
      val o = step.json.values
      val found = nativeVerbs.filter { o.containsKey(it) }
      if (found.size != 1) problems += "${step.origin}: exactly one verb this runner performs (found $found)"
      for (key in o.keys) if (key !in nativeStepKeys) problems += "${step.origin}: unknown key \"$key\""
      if (index == 0 && !o.containsKey("launch")) problems += "${step.origin}: a native scenario starts with a launch"
      val launch = o["launch"]
      if (launch != null && ((launch as? JsonValue.Obj)?.string("appVersion") == null || launch.string("appBuild") == null)) {
        problems += "${step.origin}: launch names its appVersion and appBuild"
      }
      for (verb in listOf("foreground", "background")) {
        if (o.containsKey(verb) && (o[verb] as? JsonValue.Str)?.value.isNullOrEmpty()) problems += "${step.origin}: $verb names a scene"
      }
      for (verb in listOf("getExperiment", "cachedExperiment")) {
        if (o.containsKey(verb) && (o[verb] as? JsonValue.Str)?.value.isNullOrEmpty()) problems += "${step.origin}: $verb names an experiment"
      }
    }
    if (problems.isNotEmpty()) throw ScenarioError("native scenario $name: ${problems.joinToString("; ")}")
    return Scenario(
      name, init, (raw["deviceClockOffsetMs"] as? JsonValue.Int)?.value ?: 0, steps,
      (raw["assignments"] as? JsonValue.Obj)?.values ?: emptyMap(),
    )
  }

  private fun expand(steps: List<JsonValue>, root: File, chain: List<String>, problems: MutableList<String>): List<Step> {
    val out = ArrayList<Step>()
    steps.forEachIndexed { index, step ->
      val origin = "${chain.last()}.steps[$index]"
      val o = step as? JsonValue.Obj ?: JsonValue.Obj(emptyMap())
      val include = o.string("include")
      if (include == null) {
        out += Step(o, origin)
        return@forEachIndexed
      }
      if (include in chain) {
        problems += "$origin: include \"$include\" is circular"
        return@forEachIndexed
      }
      val path = listOf(File(root, "fragments/$include.json"), File(root, "scenarios/$include.json")).firstOrNull { it.exists() }
      if (path == null) {
        problems += "$origin: no fragment or scenario named \"$include\""
        return@forEachIndexed
      }
      val included = readJson(path)
      out += expand((included["steps"] as? JsonValue.Arr)?.values ?: emptyList(), root, chain + include, problems)
    }
    return out
  }

  fun readJson(file: File): JsonValue.Obj = try {
    JsonValue.parse(file.readBytes()) as JsonValue.Obj
  } catch (e: Exception) {
    throw ScenarioError("${file.path}: $e")
  }

  // Time.

  /** `1d2h30m`, `40s`, `500ms` → milliseconds. */
  fun parseDuration(text: String): Long? {
    if (text.isEmpty()) return null
    var total = 0L
    var i = 0
    while (i < text.length) {
      val start = i
      while (i < text.length && text[i] in '0'..'9') i++
      if (i == start) return null
      val value = text.substring(start, i).toLongOrNull() ?: return null
      val unit = when {
        text.startsWith("ms", i) -> { i += 2; 1L }
        i < text.length && text[i] == 's' -> { i++; SECOND }
        i < text.length && text[i] == 'm' -> { i++; MINUTE }
        i < text.length && text[i] == 'h' -> { i++; HOUR }
        i < text.length && text[i] == 'd' -> { i++; DAY }
        else -> return null
      }
      total += value * unit
    }
    return total
  }

  sealed class At {
    data class Relative(val ms: Long) : At()
    data class Absolute(val ms: Long) : At()
  }

  fun parseAt(at: String): At? {
    if (at.startsWith("+")) return parseDuration(at.substring(1))?.let { At.Relative(it) }
    if (at == "now") return At.Absolute(0)
    if (!at.startsWith("now") || at.length <= 4) return null
    val sign = at[3]
    if (sign != '+' && sign != '-') return null
    val ms = parseDuration(at.substring(4)) ?: return null
    return At.Absolute(if (sign == '-') -ms else ms)
  }

  /** Each step's offset from the visitor's start, for the relative steps; an absolute step does not move the cursor. */
  private fun relativeOffsets(steps: List<Step>, gap: () -> Long): List<Long?> {
    var cursor = 0L
    return steps.mapIndexed { index, step ->
      val at = step.json.string("at")?.let(::parseAt)
      when {
        at is At.Absolute -> null
        at is At.Relative -> { cursor += at.ms; cursor }
        else -> {
          if (index > 0) cursor += gap()
          cursor
        }
      }
    }
  }

  // Plans.

  /** mulberry32 — small, seedable, and the same in every runtime. Kotlin's Int arithmetic wraps, as `Math.imul` does. */
  fun prng(seed: Int): () -> Double {
    var a = seed
    return {
      a += 0x6D2B79F5
      var t = a
      t = (t xor (t ushr 15)) * (t or 1)
      t = t xor (t + (t xor (t ushr 7)) * (t or 61))
      ((t xor (t ushr 14)).toLong() and 0xFFFF_FFFFL).toDouble() / 4_294_967_296.0
    }
  }

  fun visitorSeed(seed: Int, n: Int): Int = (seed * 0x9E3779B1.toInt()) xor (n * 0x85EBCA6B.toInt())

  /** One visitor's timeline: every step with its wall-clock time, device and actor, templates filled. */
  fun planVisitor(scenario: Scenario, seed: Int, n: Int, nowMs: Long, runId: String): Plan {
    val random = prng(visitorSeed(seed, n))
    val gap = { GAP_MIN + Math.floor(random() * (GAP_MAX - GAP_MIN)).toLong() }
    val offsets = relativeOffsets(scenario.steps, gap)
    val span = maxOf(0L, offsets.filterNotNull().maxOrNull() ?: 0L)
    val latestStart = nowMs - END_MARGIN_MS - span
    val earliestStart = nowMs - WINDOW_MS
    val start = latestStart - Math.floor(random() * (latestStart - earliestStart)).toLong()
    val userId = "u_${runId}_$n"
    val items = scenario.steps.mapIndexed { index, step ->
      val call = LinkedHashMap(step.json.values)
      val at = (call.remove("at") as? JsonValue.Str)?.value?.let(::parseAt)
      val actor = (call.remove("actor") as? JsonValue.Str)?.value ?: "client"
      val device = (call.remove("device") as? JsonValue.Str)?.value
      call.remove("expectRejected")
      val time = if (at is At.Absolute) nowMs + at.ms else start + offsets[index]!!
      PlanItem(time, actor, if (actor == "server") null else device ?: "browser", fill(JsonValue.Obj(call), userId, n) as JsonValue.Obj, index)
    }
    return Plan(
      scenario.name, n, userId, (fill(JsonValue.Obj(scenario.initOptions), userId, n) as JsonValue.Obj).values,
      scenario.deviceClockOffsetMs, items, scenario.assignments,
    )
  }

  /** `{{userId}}`, `{{n}}` and `{{chars:N}}` in every string. */
  private fun fill(value: JsonValue, userId: String, n: Int): JsonValue = when (value) {
    is JsonValue.Str -> JsonValue.Str(fillString(value.value, userId, n))
    is JsonValue.Arr -> JsonValue.Arr(value.values.map { fill(it, userId, n) })
    is JsonValue.Obj -> JsonValue.Obj(value.values.mapValues { fill(it.value, userId, n) })
    else -> value
  }

  private fun fillString(text: String, userId: String, n: Int): String {
    if (!text.contains("{{")) return text
    val out = StringBuilder()
    var rest = text
    while (true) {
      val open = rest.indexOf("{{")
      if (open < 0) break
      out.append(rest, 0, open)
      val after = rest.substring(open + 2)
      val close = after.indexOf("}}")
      if (close < 0) {
        out.append(rest.substring(open))
        return out.toString()
      }
      val name = after.substring(0, close)
      when {
        name == "userId" -> out.append(userId)
        name == "n" -> out.append(n)
        name.startsWith("chars:") && name.substring(6).toIntOrNull() != null -> out.append("x".repeat(name.substring(6).toInt()))
        else -> out.append("{{").append(name).append("}}")
      }
      rest = after.substring(close + 2)
    }
    return out.append(rest).toString()
  }
}

internal data class Step(val json: JsonValue.Obj, val origin: String)

internal data class Scenario(
  val name: String,
  val initOptions: Map<String, JsonValue>,
  val deviceClockOffsetMs: Long,
  val steps: List<Step>,
  /** What the assignment service answers this device, per experiment name (native scenarios only). */
  val assignments: Map<String, JsonValue> = emptyMap(),
)

/** One planned call. `order` is the step's position, so equal times keep the scenario's order. */
internal data class PlanItem(val timeMs: Long, val actor: String, val device: String?, val call: JsonValue.Obj, val order: Int)

internal data class Plan(
  val scenario: String,
  val n: Int,
  val userId: String,
  val initOptions: Map<String, JsonValue>,
  val deviceClockOffsetMs: Long,
  val items: List<PlanItem>,
  val assignments: Map<String, JsonValue> = emptyMap(),
) {
  /** In time order, stably. */
  val ordered: List<PlanItem> get() = items.sortedWith(compareBy<PlanItem> { it.timeMs }.thenBy { it.order })
}

internal class ScenarioError(message: String) : Exception(message)
