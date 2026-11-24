// The minified-build test: the example app's withTogo release classes, as R8
// left them, run on the JVM under Robolectric — set up, track, flush — with no
// consumer rules from the SDK. Its own module, so the test classpath holds the
// minified classes and never the originals. The tests are Java, so the module
// compiles no Kotlin of its own beside the shrunk app.
plugins { id("com.android.library") }

android {
  namespace = "ai.togohq.example.minified"
  compileSdk = 36
  defaultConfig { minSdk = 21 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  lint {
    warningsAsErrors = true
    disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
  }
}

// Both variants would run the same test on the same shrunk jar, booting
// Robolectric's sandbox twice for nothing: the debug variant runs it alone.
tasks.matching { it.name == "testReleaseUnitTest" }.configureEach { enabled = false }

dependencies {
  testImplementation(
    files(rootProject.file("examples/app/build/minified/withTogoRelease.jar"))
      .builtBy(":examples:app:minifiedClassfiles"),
  )
  // The release build kept the standard library's names, so the real one runs beside the shrunk app.
  testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.robolectric:robolectric:4.18.1")
}
