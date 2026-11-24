// The Togo AI Android SDK — published as `ai.togohq:sdk-android`.
//
// Nothing outside this directory: the litmus test is `./gradlew build check`
// from here, with the checked-in wrapper and no other install.
pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "sdk-android"

include(":core", ":togo", ":examples:app", ":examples:minified")
