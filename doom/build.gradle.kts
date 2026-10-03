plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.nakara.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nakara.android"
        minSdk = 23
        targetSdk = 34
        versionCode = 43
        versionName = "1.0.42-releasefix"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // signingConfig = signingConfigs.getByName("release")
        }
    }

    flavorDimensions += "data"
    productFlavors {
        // Dev build: Nk.ipk3 is injected into the APK after Gradle packaging.
        create("bundled") {
            dimension = "data"
            buildConfigField("boolean", "DATA_LESS", "false")
        }
        // Public launcher: contains no game data. The user supplies their own
        // legally obtained Nk.ipk3 at first run; the app applies the Android
        // compatibility patch to their copy at import time.
        create("launcher") {
            dimension = "data"
            applicationId = "com.nakara.launcher"
            versionName = "0.1.0"
            buildConfigField("boolean", "DATA_LESS", "true")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/libs")
        }
    }

    // Don't recompress the 651MB Nk.ipk3 (it's already a zip).
    // Also avoids exhausting /tmp during asset compression.
    aaptOptions {
        noCompress("ipk3")
    }

    packaging {
        jniLibs {
            // Store .so uncompressed and page-aligned so they can be mmap'd directly on
            // 16 KB-page devices (Android 15+). Combined with the 16 KB ELF LOAD alignment
            // (see jni/Application.mk), this makes the app 16 KB-compatible.
            useLegacyPackaging = false
        }
    }

    buildFeatures {
        resValues = true
        compose = true
        buildConfig = true // BuildConfig.VERSION_NAME feeds the idgames API User-Agent
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "../libs", "include" to listOf("*.jar"))))
    implementation(project(":touchcontrols"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.compose)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // PNG->WAD generator (native libpng2wad.so via CMake) used by the map editor.
    implementation(project(":png2wad-sdk"))

    testImplementation(libs.junit)
}
