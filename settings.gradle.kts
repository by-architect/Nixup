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

rootProject.name = "Seraph"
include(":app")
// Termux's terminal (Apache License 2.0), for the app's terminal.
include(":terminal-emulator", ":terminal-view")
