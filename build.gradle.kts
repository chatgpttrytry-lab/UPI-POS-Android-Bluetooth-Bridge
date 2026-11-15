plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.4.0"
}
android {
    namespace = "com.upipos.bridge"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.upipos.bridge"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}
