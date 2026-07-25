plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.compose.compiler)
}

// Получаем версию из git через современные провайдеры Gradle
val gitVersionCode = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
    workingDir = rootDir
}.standardOutput.asText.map { it.trim().toInt() + 500 }.getOrElse(500)

val baseVersion = providers.exec {
    commandLine("git", "describe", "--tags", "--always", "--abbrev=0")
    workingDir = rootDir
}.standardOutput.asText.map { it.trim().removePrefix("v") }.getOrElse("1.7.1")

val gitHash = providers.exec {
    commandLine("git", "rev-parse", "--short=6", "HEAD")
    workingDir = rootDir
}.standardOutput.asText.map { it.trim() }.getOrElse("unknown")

println("-> Build VersionCode: $gitVersionCode")
println("-> Build VersionName: v$baseVersion-$gitHash")

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
        versionName = "v$baseVersion-$gitHash"

        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
        externalNativeBuild {
            ndkBuild {
                arguments("APP_CFLAGS+=-DPKGNAME=io/github/bropines/tailscaled/core -DCLSNAME=TunVpnService -ffile-prefix-map=${rootDir}=.")
                arguments("APP_LDFLAGS+=-Wl,--build-id=none")
            }
        }
        
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    if (!file("src/main/jniLibs/arm64-v8a/libhev-socks5-tunnel.so").exists()) {
        externalNativeBuild {
            ndkBuild {
                path = file("src/main/jni/Android.mk")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            buildConfigField("boolean", "IS_DEV", "true")
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = false 
            isShrinkResources = false 
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "IS_DEV", "false")
            versionNameSuffix = ".release"
            
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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation(libs.androidx.appcompat)
    
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
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    debugImplementation(libs.androidx.ui.tooling)
}