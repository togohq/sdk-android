plugins { id("org.jetbrains.kotlin.jvm") }

kotlin {
  jvmToolchain(17)
  explicitApi()
  compilerOptions { allWarningsAsErrors.set(true) }
}

dependencies { testImplementation("junit:junit:4.13.2") }

// The shared scenarios are found from the library's directory: `scenarios/`
// inside it once it is its own repository, `../scenarios` beside it here.
val library: String = rootProject.projectDir.absolutePath

tasks.test { systemProperty("togo.library", library) }

// The scenario runner, one line per scenario: `./gradlew :core:scenarios`.
tasks.register<JavaExec>("scenarios") {
  group = "verification"
  description = "Replays the shared scenarios through the core and compares them with expected/."
  classpath = sourceSets["test"].runtimeClasspath
  mainClass.set("ai.togohq.scenarios.ScenarioMainKt")
  systemProperty("togo.library", library)
}
