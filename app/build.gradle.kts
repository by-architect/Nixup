// The app: builds the phone config with the Magisk module, and opens the config folder.
plugins {
    id("com.android.application")
}

android {
    namespace = "dev.andronix.manager"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.andronix.manager"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
}
