plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ayu.authentic"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ayu.authentic"
        minSdk = 24
        targetSdk = 34
        versionCode = 9
        versionName = "0.9.0-mvp9"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // 出包命名：编译后由脚本把 app-debug.apk 复制为 app-debug001.apk
    // （后续阶段依次 002 / 003 / 004），避免在 Gradle 里依赖 AGP 内部 API。

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
