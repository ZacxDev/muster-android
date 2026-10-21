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

// Throwaway Phase 0 spike app (applicationId dev.zacx.muster.spikes).
include(":spikes")
