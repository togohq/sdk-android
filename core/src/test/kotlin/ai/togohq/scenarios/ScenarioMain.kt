package ai.togohq.scenarios

import ai.togohq.core.JsonValue
import java.io.File
import kotlin.system.exitProcess

/**
 * Replay the shared scenarios through the Kotlin core.
 *
 *     ./gradlew :core:scenarios                          check every scenario against expected/
 *     ./gradlew :core:scenarios --args="<name>…"         check these
 *     ./gradlew :core:scenarios --args="--print <name>"  print the recorded batches, projected
 *     ./gradlew :core:scenarios --args="--native"        check every native scenario against expected-native/
 *     ./gradlew :core:scenarios --args="--send <native>" perform one native scenario now and upload it to
 *                                                        TOGO_ENDPOINT under TOGO_WRITE_KEY; prints the batches
 *                                                        and the edge's answers as JSON, to TOGO_OUTPUT when set,
 *                                                        and exits 1 unless every upload was a 202 with nothing
 *                                                        rejected (the stage journey's sender)
 *
 * It never writes expected/ or expected-native/: the browser SDK and the iOS
 * SDK record those goldens, and this runner holds a second SDK to them
 * (`Projection`).
 */
fun main(args: Array<String>) {
  if (args.firstOrNull() == "--send") {
    send(args.drop(1))
    return
  }
  if (args.firstOrNull() == "--native") {
    var failedNative = 0
    for (name in args.drop(1).ifEmpty { ScenarioFormat.nativeScenarioNames() }) {
      val golden = ScenarioFormat.readJson(File(ScenarioFormat.defaultRoot, "expected-native/$name.json"))
      val difference = Projection.difference(
        Projection.nativeExpected(golden), Projection.nativeRecorded(ScenarioDriver.recordNative(name)),
      )
      if (difference == null) println("ok   $name") else {
        failedNative++
        println("FAIL $name: $difference")
      }
    }
    if (failedNative > 0) exitProcess(1)
    return
  }
  val printOnly = args.firstOrNull() == "--print"
  val names = args.filter { !it.startsWith("--") }.ifEmpty { ScenarioFormat.scenarioNames() }
  var failed = 0
  for (name in names) {
    val recorded = Projection.recorded(ScenarioDriver.record(name))
    if (printOnly) {
      println(JsonValue.Arr(recorded.map { JsonValue.Obj(mapOf("client" to JsonValue.Str(it.client), "body" to it.body)) }).pretty())
      continue
    }
    val golden = ScenarioFormat.readJson(File(ScenarioFormat.defaultRoot, "expected/$name.json"))
    val difference = Projection.difference(Projection.expected(golden), recorded)
    if (difference == null) {
      println("ok   $name")
    } else {
      failed++
      println("FAIL $name: $difference")
    }
  }
  if (failed > 0) exitProcess(1)
}

private fun send(names: List<String>) {
  val natives = ScenarioFormat.nativeScenarioNames()
  val endpoint = System.getenv("TOGO_ENDPOINT").orEmpty()
  val writeKey = System.getenv("TOGO_WRITE_KEY").orEmpty()
  if (names.size != 1 || names[0] !in natives || endpoint.isEmpty() || writeKey.isEmpty()) {
    System.err.println(
      "scenarios: --send takes one native scenario (${natives.joinToString(", ")}) and needs TOGO_ENDPOINT and TOGO_WRITE_KEY",
    )
    exitProcess(2)
  }
  val uploads = ScenarioDriver.sendNative(
    names[0], endpoint, writeKey, System.currentTimeMillis(), System.getenv("TOGO_RUN_ID") ?: "send",
  )
  val problems = uploads.mapNotNull { upload ->
    val rejected = ((upload.response as? JsonValue.Obj)?.get("rejected") as? JsonValue.Arr)?.values?.size ?: 0
    if (upload.status != 202 || rejected > 0) "status ${upload.status ?: "none"}, $rejected rejected" else null
  } + if (uploads.isEmpty()) listOf("nothing was uploaded") else emptyList()
  val report = JsonValue.Arr(
    uploads.map {
      JsonValue.Obj(
        mapOf(
          "client" to JsonValue.Str(it.client),
          "status" to (it.status?.let { s -> JsonValue.Int(s.toLong()) } ?: JsonValue.Null),
          "body" to it.body,
          "response" to (it.response ?: JsonValue.Null),
        ),
      )
    },
  ).pretty()
  // Gradle writes its own lines to stdout, so a caller reads the report from a file.
  val output = System.getenv("TOGO_OUTPUT")
  if (output.isNullOrEmpty()) println(report) else File(output).writeText(report + "\n")
  for (problem in problems) System.err.println("scenarios: $problem")
  if (problems.isNotEmpty()) exitProcess(1)
}
