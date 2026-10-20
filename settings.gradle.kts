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

// Phase 0 modules only. The full layout is in muster's docs/design/android.md
// ("App repository layout"); the rest arrive with the phases that need them.
include(":app")
include(":core:model")
include(":feature:shell")
include(":feature:stepup")
include(":feature:notify")

// Throwaway Phase 0 spike app (applicationId dev.zacx.muster.spikes).
include(":spikes")
