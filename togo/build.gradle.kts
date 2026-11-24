import java.util.Collections
import java.util.Properties
import java.util.zip.ZipFile

plugins {
  id("com.android.library")
  `maven-publish`
}

/** The oldest compileSdk an app that adds the SDK may use. */
val MIN_CONSUMER_COMPILE_SDK = 35

android {
  namespace = "ai.togohq"
  compileSdk = 36
  defaultConfig {
    minSdk = 21
    // The SDK compiles against 36 but uses nothing newer than 35, so an app may
    // compile against 35: a Solar2D build, whose compileSdk the game cannot
    // change, is one. Without this the AAR would ask for the library's own 36
    // and AGP's checkAarMetadata would fail that app. `aarMetadataCheck` holds it.
    aarMetadata { minCompileSdk = MIN_CONSUMER_COMPILE_SDK }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  sourceSets["main"].kotlin.directories += "../core/src/main/kotlin"
  testOptions { unitTests.isIncludeAndroidResources = true }
  lint {
    warningsAsErrors = true
    // The versions are pinned on purpose: compileSdk follows Google Play's
    // target requirement, not the newest platform, and the build tools move
    // with a deliberate commit.
    disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
  }
  // What a Maven repository needs beside the library: its sources and its API
  // documentation. The documentation is generated at build time and is never
  // on the library's runtime classpath.
  publishing {
    singleVariant("release") {
      withSourcesJar()
      withJavadocJar()
    }
  }
}

kotlin {
  explicitApi()
  compilerOptions { allWarningsAsErrors.set(true) }
}

dependencies {
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.robolectric:robolectric:4.18.1")
}

// The one dependency an app gets with this SDK is the Kotlin standard library
// (and the annotations jar it brings), which almost every Android app already
// ships — never AndroidX, Google Play services or Firebase, whose minimum
// versions would reach into the customer's dependency graph. Enforced where the
// artifact is resolved: a dependency added in any spelling — a coordinate,
// kotlin("reflect"), a version catalog alias, a transitive one — fails `check`
// here, which a reading of this file cannot.
val allowedRuntimeModules = setOf("org.jetbrains.kotlin:kotlin-stdlib", "org.jetbrains:annotations")

/** The groups named in the failure, because they are the ones a later change is likeliest to bring. */
fun forbiddenFamily(module: String): String? = when {
  module.startsWith("com.google.android.gms:") -> "Google Play services"
  module.startsWith("com.google.firebase:") -> "Firebase"
  module.startsWith("androidx.") -> "AndroidX"
  else -> null
}

val runtimeDependencyCheck = tasks.register("runtimeDependencyCheck") {
  group = "verification"
  description = "Fails if the release runtime classpath holds anything but the Kotlin standard library."
  val classpath = configurations.named("releaseRuntimeClasspath")
  doLast {
    val modules = classpath.get().incoming.resolutionResult.allComponents
      .mapNotNull { it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
      .map { "${it.group}:${it.module}" }
      .toSet()
    val extra = modules - allowedRuntimeModules
    if (extra.isNotEmpty()) {
      val families = extra.mapNotNull(::forbiddenFamily).distinct()
      throw GradleException(
        "sdk-android's runtime classpath must hold only the Kotlin standard library; found ${extra.sorted()}" +
          if (families.isEmpty()) "" else " — ${families.joinToString(" and ")}, which the SDK never depends on",
      )
    }
    if ("org.jetbrains.kotlin:kotlin-stdlib" !in modules) {
      // Guard the guard: a classpath that resolved to nothing checks nothing.
      throw GradleException("the release runtime classpath did not resolve the Kotlin standard library: $modules")
    }
  }
}

// The one permission the library may declare is INTERNET. A library's manifest
// is merged into every app that adds it, so a permission here is a permission
// in the customer's app — and the advertising ID permission in particular would
// make every one of them declare advertising ID use in Play Console.
val mergedManifest = objects.fileProperty()
androidComponents {
  onVariants(selector().withBuildType("release")) { variant ->
    mergedManifest.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST))
  }
}

@Suppress("UNCHECKED_CAST")
val declaredPermissions = rootProject.extra["declaredPermissions"] as (File) -> Set<String>

val manifestPermissionCheck = tasks.register("manifestPermissionCheck") {
  group = "verification"
  description = "Fails if the library's merged manifest declares any permission but INTERNET."
  val manifest = mergedManifest
  inputs.file(manifest)
  doLast {
    val permissions = declaredPermissions(manifest.get().asFile)
    if (permissions != setOf("android.permission.INTERNET")) {
      val adId = "com.google.android.gms.permission.AD_ID"
      throw GradleException(
        "sdk-android's merged manifest must declare only android.permission.INTERNET; it declares ${permissions.sorted()}" +
          if (adId in permissions) " — including $adId, which would make every app using the SDK declare advertising ID use" else "",
      )
    }
  }
}


// The compileSdk the AAR asks of an app, read from the built AAR, where AGP's
// checkAarMetadata in the app will read it.
val aarMetadataCheck = tasks.register("aarMetadataCheck") {
  group = "verification"
  description = "Fails if the release AAR asks apps for a compileSdk other than $MIN_CONSUMER_COMPILE_SDK."
  dependsOn("assembleRelease")
  val aar = layout.buildDirectory.file("outputs/aar/togo-release.aar")
  val expected = MIN_CONSUMER_COMPILE_SDK
  doLast {
    val file = aar.get().asFile
    if (!file.isFile) throw GradleException("no release AAR at $file")
    val zip = ZipFile(file)
    val text = try {
      val entry = zip.getEntry("META-INF/com/android/build/gradle/aar-metadata.properties")
        ?: throw GradleException("the release AAR has no aar-metadata.properties")
      zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
    } finally {
      zip.close()
    }
    val properties = Properties().apply { load(text.reader()) }
    val found = properties.getProperty("minCompileSdk")
    if (found != expected.toString()) {
      throw GradleException(
        "the release AAR asks apps to compile against ${found ?: "(nothing)"}, not $expected; an app on compileSdk $expected — a Solar2D build — would fail checkAarMetadata",
      )
    }
  }
}

tasks.named("check") { dependsOn(runtimeDependencyCheck, manifestPermissionCheck, aarMetadataCheck) }

// The published coordinates: ai.togohq:sdk-android at the version every event
// carries as sdk.version, read from the one place it is written, so the two can
// never differ.
val sdkVersion: String = Regex("""internal const val SDK_VERSION = "(\d+\.\d+\.\d+)"""")
  .find(rootProject.file("core/src/main/kotlin/ai/togohq/core/Envelope.kt").readText())
  ?.groupValues?.get(1)
  ?: throw GradleException("core/src/main/kotlin/ai/togohq/core/Envelope.kt defines no SDK_VERSION")

val bundleDir = layout.buildDirectory.dir("maven-bundle")

afterEvaluate {
  publishing {
    publications {
      create<MavenPublication>("release") {
        from(components["release"])
        groupId = "ai.togohq"
        artifactId = "sdk-android"
        version = sdkVersion
        pom {
          name.set("ai.togohq:sdk-android")
          description.set(
            "The Togo AI Android SDK: track, identify, person properties, consent, sessions and an offline queue, from Kotlin or Java.",
          )
          url.set("https://github.com/togohq/sdk-android")
          licenses {
            license {
              name.set("The Apache License, Version 2.0")
              url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
          }
          developers {
            developer {
              name.set("Togo AI")
              email.set("support@togohq.ai")
              organization.set("Togo AI")
              organizationUrl.set("https://togohq.ai")
            }
          }
          scm {
            connection.set("scm:git:https://github.com/togohq/sdk-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/togohq/sdk-android.git")
            url.set("https://github.com/togohq/sdk-android")
          }
        }
      }
    }
    // A plain directory in Maven's layout: the release signs what is here and
    // uploads it. Nothing in the build ever publishes anywhere else.
    repositories { maven { name = "bundle"; url = uri(bundleDir) } }
  }
}

// Starts the bundle empty, so it never holds a version from an earlier build.
val cleanBundle = tasks.register<Delete>("cleanBundle") { delete(bundleDir) }
tasks.matching { it.name == "publishReleasePublicationToBundleRepository" }.configureEach { dependsOn(cleanBundle) }

// The publication a Maven repository requires, checked on every `check` rather
// than on release day: the library, its sources, its documentation, the Gradle
// module and the POM, each with checksums; a POM with every field a repository
// requires and no dependency but the Kotlin standard library; and the version
// the source says.
val publicationCheck = tasks.register("publicationCheck") {
  group = "verification"
  description = "Builds the Maven bundle and fails if it lacks anything a Maven repository requires."
  dependsOn("publishReleasePublicationToBundleRepository")
  val dir = bundleDir
  val version = sdkVersion
  doLast {
    val home = dir.get().asFile.resolve("ai/togohq/sdk-android/$version")
    if (!home.isDirectory) throw GradleException("the bundle has no ai/togohq/sdk-android/$version")
    val base = "sdk-android-$version"
    val artifacts = listOf("$base.aar", "$base.pom", "$base.module", "$base-sources.jar", "$base-javadoc.jar")
    val missing = artifacts.flatMap { listOf(it, "$it.md5", "$it.sha1") }.filterNot { home.resolve(it).isFile }
    if (missing.isNotEmpty()) throw GradleException("the bundle lacks $missing")
    val unexpected = home.listFiles()!!.map { it.name }.filterNot { name -> artifacts.any { name.startsWith(it) } }
    if (unexpected.isNotEmpty()) throw GradleException("the bundle holds files the release does not expect: $unexpected")

    fun entries(jar: File): List<String> {
      val zip = ZipFile(jar)
      try {
        return Collections.list(zip.entries()).map { it.name }
      } finally {
        zip.close()
      }
    }
    val sources = entries(home.resolve("$base-sources.jar"))
    for (source in listOf("ai/togohq/Togo.kt", "ai/togohq/core/Engine.kt")) {
      if (sources.none { it.endsWith(source) }) throw GradleException("the sources jar lacks $source")
    }
    if (entries(home.resolve("$base-javadoc.jar")).none { it.endsWith("index.html") }) {
      throw GradleException("the javadoc jar holds no generated documentation")
    }

    val pom = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(home.resolve("$base.pom"))
    fun text(path: String): String {
      var nodes = listOf(pom.documentElement as org.w3c.dom.Node)
      for (part in path.split("/")) {
        nodes = nodes.flatMap { node ->
          (0 until node.childNodes.length).map { node.childNodes.item(it) }.filter { it.nodeName == part }
        }
      }
      return nodes.joinToString(",") { it.textContent.trim() }
    }
    val required = listOf(
      "groupId", "artifactId", "version", "packaging", "name", "description", "url",
      "licenses/license/name", "licenses/license/url", "developers/developer/name",
      "scm/connection", "scm/developerConnection", "scm/url",
    )
    val empty = required.filter { text(it).isEmpty() }
    if (empty.isNotEmpty()) throw GradleException("the POM lacks $empty")
    val coordinates = listOf(text("groupId"), text("artifactId"), text("version"), text("packaging"))
    if (coordinates != listOf("ai.togohq", "sdk-android", version, "aar")) {
      throw GradleException("the POM is ${coordinates.joinToString(":")}, not ai.togohq:sdk-android:$version:aar")
    }
    val dependencies = text("dependencies/dependency/artifactId").split(",").filter { it.isNotEmpty() }
    if (dependencies != listOf("kotlin-stdlib")) {
      throw GradleException("the POM must depend on the Kotlin standard library alone; it lists $dependencies")
    }
  }
}

tasks.named("check") { dependsOn(publicationCheck) }
