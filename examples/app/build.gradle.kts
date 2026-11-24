import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ScopedArtifacts

// A minimal Kotlin app, built twice: the `withTogo` flavor with the SDK and the
// `baseline` flavor without it. Its release builds are minified by R8 with no
// consumer rules from the SDK, the way a customer's would be. The size gate
// measures the difference; the minified-build test runs what R8 kept.
plugins { id("com.android.application") }

// Where the withTogo flavor sends its events and with which key, for a run on an
// emulator or a phone without editing the source:
//   ./gradlew :examples:app:installWithTogoRelease \
//     -Ptogo.example.endpoint=http://10.0.2.2:8787 -Ptogo.example.key=togo_pkt_...
// Unset, the app uses the SDK's own endpoint and the placeholder keys in
// Analytics.kt — which is what the size gate and the minified-build test build.
// An unset property is the empty string in BuildConfig.
fun exampleSetting(name: String): String {
  val value = providers.gradleProperty(name).orNull ?: return "\"\""
  if (value.isBlank() || value.any { it == '"' || it == '\\' || it.isISOControl() }) {
    throw GradleException("-P$name must be non-empty, with no quote, backslash or control character")
  }
  return "\"$value\""
}

android {
  namespace = "ai.togohq.example"
  // 35, below the library's 36, so AGP's checkAarMetadata runs on every `check`
  // in the case a Solar2D app is in: an app on compileSdk 35 adding the SDK.
  // targetSdk follows it, as an app's does.
  compileSdk = 35
  defaultConfig {
    applicationId = "ai.togohq.example"
    minSdk = 21
    targetSdk = 35
    versionCode = 1
    versionName = "1.0"
    buildConfigField("String", "TOGO_ENDPOINT", exampleSetting("togo.example.endpoint"))
    buildConfigField("String", "TOGO_WRITE_KEY", exampleSetting("togo.example.key"))
  }
  buildFeatures { buildConfig = true }
  flavorDimensions += "sdk"
  productFlavors {
    create("withTogo") { dimension = "sdk" }
    create("baseline") { dimension = "sdk" }
  }
  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      // Signed with the debug key so the real-device check can install the minified build
      // (./gradlew :examples:app:installWithTogoRelease). The example is never published, and
      // signing changes no dex byte the size gate measures.
      signingConfig = signingConfigs.getByName("debug")
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  lint {
    warningsAsErrors = true
    // An example with no launcher icon, no translations and pinned versions, on purpose.
    disable += setOf(
      "GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable",
      "MissingApplicationIcon", "SetTextI18n", "HardcodedText", "OldTargetApi",
    )
  }
}

kotlin { compilerOptions { allWarningsAsErrors.set(true) } }

dependencies { "withTogoImplementation"(project(":togo")) }

/**
 * The withTogo release build's classes run through R8 again, with the rules the
 * release build used, but written as class files instead of dex so a JVM test can
 * execute them (the `:examples:minified` module). The rules are the release
 * build's own merged configuration — `configuration.txt`, written beside its
 * mapping — plus the keep rules for the manifest's components, which the build
 * gets from aapt and this task writes from the merged manifest the same way. No
 * rules come from the SDK, because it ships none. The Kotlin standard library
 * is passed as a classpath rather than shrunk with the app, so the test can run
 * against the real one: what is under test is the app and the SDK surviving R8.
 */
abstract class MinifiedClassfiles : DefaultTask() {
  @get:InputFiles abstract val jars: ListProperty<RegularFile>
  @get:InputFiles abstract val directories: ListProperty<Directory>
  @get:InputFile abstract val manifest: RegularFileProperty
  @get:InputFile abstract val mapping: RegularFileProperty
  @get:InputFiles abstract val bootClasspath: ListProperty<RegularFile>
  @get:OutputFile abstract val output: RegularFileProperty

  @TaskAction fun run() {
    val configuration = mapping.get().asFile.resolveSibling("configuration.txt")
    check(configuration.isFile) { "the release build wrote no R8 configuration beside ${mapping.get().asFile}" }
    val components = componentClasses(manifest.get().asFile)
    check(components.isNotEmpty()) { "the merged manifest names no component, so nothing would be kept" }
    val rules = temporaryDir.resolve("manifest-rules.pro")
    rules.writeText(components.joinToString("\n") { "-keep class $it { <init>(); }" } + "\n")
    val (classpath, program) = jars.get().map { it.asFile.toPath() }
      .partition { it.fileName.toString().let { n -> n.startsWith("kotlin-stdlib") || n.startsWith("annotations-") } }
    val out = output.get().asFile.also { it.parentFile.mkdirs(); it.delete() }
    com.android.tools.r8.R8.run(
      com.android.tools.r8.R8Command.builder(quietInfo)
        .addProgramFiles(program + directories.get().flatMap { dir -> dir.asFile.walk().filter { it.name.endsWith(".class") }.map { it.toPath() }.toList() })
        .addClasspathFiles(classpath)
        .addLibraryFiles(bootClasspath.get().map { it.asFile.toPath() })
        .addProguardConfigurationFiles(configuration.toPath(), rules.toPath())
        .setMode(com.android.tools.r8.CompilationMode.RELEASE)
        .setProgramConsumer(com.android.tools.r8.ClassFileConsumer.ArchiveConsumer(out.toPath()))
        .build(),
    )
  }

  // R8's notes on options it ignores when writing class files are not findings;
  // warnings and errors still print, and an error still fails the task.
  private val quietInfo = object : com.android.tools.r8.DiagnosticsHandler {
    override fun info(info: com.android.tools.r8.Diagnostic) {}
  }

  /** The fully qualified names of the application and every component the manifest declares. */
  private fun componentClasses(manifest: File): List<String> {
    val ns = "http://schemas.android.com/apk/res/android"
    val document = javax.xml.parsers.DocumentBuilderFactory.newInstance()
      .apply { isNamespaceAware = true }.newDocumentBuilder().parse(manifest)
    val pkg = document.documentElement.getAttribute("package")
    return listOf("application", "activity", "service", "receiver", "provider").flatMap { tag ->
      val nodes = document.getElementsByTagName(tag)
      (0 until nodes.length).mapNotNull { (nodes.item(it) as org.w3c.dom.Element).getAttributeNS(ns, "name").ifEmpty { null } }
    }.map { if (it.startsWith(".")) pkg + it else it }
  }
}

val minifiedClassfiles = tasks.register<MinifiedClassfiles>("minifiedClassfiles") {
  description = "Runs the withTogo release build's classes through R8 to class files, for the minified-build test."
  bootClasspath.set(androidComponents.sdkComponents.bootClasspath)
  output.set(layout.buildDirectory.file("minified/withTogoRelease.jar"))
}

// The size gate: what the SDK adds to this app's minified release APK.
val apks = mutableMapOf<String, Provider<Directory>>()
val manifests = mutableMapOf<String, Provider<RegularFile>>()
androidComponents {
  onVariants(selector().withBuildType("release")) { variant ->
    apks[variant.flavorName!!] = variant.artifacts.get(SingleArtifact.APK)
    manifests[variant.flavorName!!] = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
    if (variant.flavorName == "withTogo") {
      variant.artifacts.forScope(ScopedArtifacts.Scope.ALL)
        .use(minifiedClassfiles)
        .toGet(ScopedArtifact.CLASSES, MinifiedClassfiles::jars, MinifiedClassfiles::directories)
      minifiedClassfiles.configure {
        manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        mapping.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
      }
    }
  }
}

val sizeCheck = tasks.register<Exec>("sizeCheck") {
  group = "verification"
  description = "Fails if the SDK adds more than its ceiling to the example app's minified release APK."
  // Configured after onVariants has run, so these providers carry the tasks that build the APKs.
  val withTogo = apks.getValue("withTogo")
  val baseline = apks.getValue("baseline")
  inputs.dir(withTogo)
  inputs.dir(baseline)
  inputs.file(rootProject.file("scripts/check-size.sh"))
  workingDir = rootProject.projectDir
  executable = "sh"
  argumentProviders.add(CommandLineArgumentProvider {
    fun apk(dir: Directory) = dir.asFile.listFiles { f -> f.name.endsWith(".apk") }!!.single().absolutePath
    listOf("scripts/check-size.sh", apk(withTogo.get()), apk(baseline.get()))
  })
}

// What adding the SDK changes in a customer's app, read from the app rather than
// the library: the permissions its merged manifest gains (INTERNET, nothing
// else — never the advertising ID permission), and the modules its release
// runtime classpath gains (the Kotlin standard library at most — never AndroidX,
// Google Play services or Firebase). A transitive dependency or a manifest
// merged in from one would show here even if the library's own checks missed it.
@Suppress("UNCHECKED_CAST")
val declaredPermissions = rootProject.extra["declaredPermissions"] as (File) -> Set<String>

val sdkFootprintCheck = tasks.register("sdkFootprintCheck") {
  group = "verification"
  description = "Fails if adding the SDK gives the app a permission but INTERNET or a dependency but the Kotlin standard library."
  val withTogoManifest = manifests.getValue("withTogo")
  val baselineManifest = manifests.getValue("baseline")
  inputs.file(withTogoManifest)
  inputs.file(baselineManifest)
  val withTogoClasspath = configurations.named("withTogoReleaseRuntimeClasspath")
  val baselineClasspath = configurations.named("baselineReleaseRuntimeClasspath")
  doLast {
    val added = declaredPermissions(withTogoManifest.get().asFile) - declaredPermissions(baselineManifest.get().asFile)
    if (added != setOf("android.permission.INTERNET")) {
      throw GradleException(
        "adding sdk-android must give an app the INTERNET permission and nothing else; it adds ${added.sorted()}",
      )
    }
    fun modules(classpath: Configuration) = classpath.incoming.resolutionResult.allComponents
      .mapNotNull { it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
      .map { "${it.group}:${it.module}" }
      .toSet()
    val brought = modules(withTogoClasspath.get()) - modules(baselineClasspath.get()) -
      setOf("org.jetbrains.kotlin:kotlin-stdlib", "org.jetbrains:annotations")
    if (brought.isNotEmpty()) {
      throw GradleException(
        "adding sdk-android must bring an app no dependency but the Kotlin standard library; it brings ${brought.sorted()}",
      )
    }
  }
}


tasks.named("check") { dependsOn(sizeCheck, sdkFootprintCheck) }
