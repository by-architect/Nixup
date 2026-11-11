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

rootProject.name = "Nixup"
include(":app")
// Termux's terminal (Apache License 2.0), for the app's terminal.
include(":terminal-emulator", ":terminal-view")
// DAVx⁵'s sync libraries (GNU GPL 3.0 and Mozilla Public License 2.0), for the CalDAV/CardDAV sync (services.caldav).
include(":synctools", ":dav4jvm")
