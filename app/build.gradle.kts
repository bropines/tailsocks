plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.compose.compiler)
}

import java.util.Properties

// Получаем версию из git через современные провайдеры Gradle
val gitVersionCode = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
    workingDir = rootDir
}.standardOutput.asText.map { it.trim().toInt() + 10 }.getOrElse(10)

val baseVersion = providers.exec {
    commandLine("git", "describe", "--tags", "--always", "--abbrev=0")
    workingDir = rootDir
}.standardOutput.asText.map { it.trim().removePrefix("v") }.getOrElse("1.7.1")

val gitHash = providers.exec {
    commandLine("git", "rev-parse", "--short=6", "HEAD")
    workingDir = rootDir
}.standardOutput.asText.map { it.trim() }.getOrElse("unknown")

val isRelease = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
val gitVersionName = if (isRelease) baseVersion else "$baseVersion-$gitHash-dev"

println("-> Build VersionCode: $gitVersionCode")
println("-> Build VersionName: $gitVersionName")

android {
    namespace = "io.github.bropines.tailscaled"
    // Оставляем 36, так как core-ktx 1.17.0 этого требует
    compileSdk = 36

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_FILE")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "io.github.bropines.tailscaled"
        minSdk = 24
        targetSdk = 35
        versionCode = gitVersionCode
        versionName = gitVersionName

        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
        
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("boolean", "IS_DEV", "true")
        }
        create("dev") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".dev"
            buildConfigField("boolean", "IS_DEV", "true")
        }
        release {
            isMinifyEnabled = false 
            isShrinkResources = false 
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "IS_DEV", "false")
            
            if (System.getenv("KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":appctr"))
    implementation(libs.gson)
    
    // ВАЖНО: Библиотека для XML-тем (исправляет "resource style/Theme.Material3... not found")
    implementation(libs.material) 
    
    // Зависимости AndroidX и Compose
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.documentfile)
    implementation(libs.navigation.compose)
    
    implementation("androidx.compose.material:material-icons-extended:1.7.0")
    debugImplementation(libs.androidx.ui.tooling)
}