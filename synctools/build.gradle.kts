// DAVx⁵'s synctools (GNU General Public License 3.0, see LICENSE), from github.com/bitfireAT/davx5-ose (4.5.21, commit
// f07633b): turns iCalendar and vCard into the rows of Android's calendar, contacts and tasks providers and back. The
// app's CalDAV/CardDAV sync (services.caldav) is built on it. Only its build file is Nixup's: the module's own uses
// DAVx⁵'s build logic and version catalog.
plugins {
    id("com.android.library")
}

android {
    namespace = "at.bitfire.synctools"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
        buildConfigField("String", "version_ical4j", "\"4.3.0\"")
        // These rules go into the app's (R8's) rules too.
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = true
    }

    // ical4j uses java.time and java.nio beyond what every Android has (as in DAVx⁵).
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    sourceSets {
        getByName("main") {
            java.directories += "opentasks-contract/src/main/java"
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("com.google.guava:guava:33.7.2-android")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    // ical4j and ez-vcard; ical4j logs through slf4j, which goes to java.util.logging here.
    api("org.mnode.ical4j:ical4j:4.3.0")
    implementation("org.slf4j:slf4j-jdk14:2.0.20")
    api("com.googlecode.ez-vcard:ez-vcard:0.12.2")

    compileOnly("com.github.spotbugs:spotbugs-annotations:4.10.4")

    // Newer Apache Commons need a newer Java than Android has: these versions, as DAVx⁵ holds them.
    implementation("commons-codec:commons-codec") { version { strictly("1.17.1") } }
    implementation("org.apache.commons:commons-lang3") { version { strictly("3.15.0") } }
}
