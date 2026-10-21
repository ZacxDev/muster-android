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

rootProject.name = "muster-android"

// The modules Phase 1 needs. The full layout is in muster's
// docs/design/android.md ("App repository layout"); the rest (core/data,
// feature/bubble, widget, live, update, build-logic) arrive with the phases
// that need them.
include(":app")
include(":core:model")
include(":core:net")
include(":core:auth")
include(":feature:shell")
include(":feature:stepup")
include(":feature:push")
include(":feature:notify")

// The Phase 0 spike app was removed in Phase 1. It is kept at
// https://github.com/ZacxDev/muster-android/tree/f652f3256bbe487e9babb467e5f509a41ef54068/spikes
// (the commit muster's design doc cites).
