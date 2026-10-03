plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "dev.ondevice.gemma.fixture"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.ondevice.gemma.fixture"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0-test"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
