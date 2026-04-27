plugins {
    id("com.android.application")
}

android {
    namespace = "app.homeadbguard"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.homeadbguard"
        minSdk = 36
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
