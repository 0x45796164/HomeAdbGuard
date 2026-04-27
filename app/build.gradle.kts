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
        versionCode = 3
        versionName = "0.3.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
