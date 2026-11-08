pluginManagement {
    includeBuild("build-logic")
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
        // Kadb's SPAKE2 pairing dependency is published to JitPack.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "fingertipsmcp"

include(":app", ":helper", ":api-model")
