// DAVx⁵'s dav4jvm (Mozilla Public License 2.0, see LICENSE), from github.com/bitfireAT/dav4jvm (4.1.0, commit
// 2527df1): WebDAV, CalDAV and CardDAV requests and their answers, over Ktor. The app's CalDAV/CardDAV sync
// (services.caldav) talks to the servers with it. Only its build file is Nixup's: the library's own builds a plain
// JVM library, this one an Android library (its code is the same).
plugins {
    id("com.android.library")
}

android {
    namespace = "at.bitfire.dav4jvm"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    api("io.ktor:ktor-client-core:3.5.2")
    implementation("io.ktor:ktor-client-auth:3.5.2")
    implementation("io.ktor:ktor-client-encoding:3.5.2")
    implementation("com.google.guava:guava:33.7.2-android")
    compileOnly("com.github.spotbugs:spotbugs-annotations:4.10.4")
    // (Its XML pull parser, xpp3, is Android's own here.)
}
