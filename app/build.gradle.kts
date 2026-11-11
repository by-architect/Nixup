// The app: builds the phone config with the Magisk module, and opens the config folder.
plugins {
    id("com.android.application")
}

android {
    namespace = "dev.nixup.manager"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.nixup.manager"
        minSdk = 30
        targetSdk = 36
        versionCode = 11
        versionName = "0.1.9"
    }

    // DAVx⁵'s synctools needs it (ical4j uses java.time and java.nio beyond what every Android has).
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources {
            // The licenses and notices that the sync libraries all carry at the same place (see NOTICE).
            excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.md", "LICENSE", "META-INF/INDEX.LIST")
        }
    }
}

dependencies {
    // The terminal: Termux's emulator and view.
    implementation(project(":terminal-view"))
    // Google Play, as Aurora Store reaches it (GPL-3.0): the engine downloads android.packages.playstore with it.
    implementation("com.auroraoss:gplayapi:4.0.0")
    // Its calls are suspend functions; the engine waits for them (runBlocking).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    // CalDAV/CardDAV (services.caldav), as DAVx⁵ syncs: its synctools (iCalendar and vCard to Android's calendar,
    // contacts and tasks, and back) and dav4jvm (WebDAV over Ktor, with OkHttp under it). Ktor 3.5, which dav4jvm
    // 4.1.0 is built with, takes the OkHttp gplayapi has (5.3; 5.5 needs Android 17's SDK to build).
    implementation(project(":synctools"))
    implementation(project(":dav4jvm"))
    implementation("io.ktor:ktor-client-core:3.5.2")
    implementation("io.ktor:ktor-client-okhttp:3.5.2")
    implementation("io.ktor:ktor-client-auth:3.5.2")
    implementation("io.ktor:ktor-client-encoding:3.5.2")
    implementation("com.google.guava:guava:33.7.2-android")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("commons-codec:commons-codec") { version { strictly("1.17.1") } }
    implementation("org.apache.commons:commons-lang3") { version { strictly("3.15.0") } }
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
