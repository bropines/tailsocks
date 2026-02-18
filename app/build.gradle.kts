@file:Suppress("UNUSED_EXPRESSION")

import com.android.build.api.dsl.Packaging

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
}

android {
    namespace = "io.github.asutorufa.tailscaled"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.asutorufa.tailscaled"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    splits {
        abi {
            isEnable = true

            // Resets the list of ABIs that Gradle should create APKs for to none.
            reset()

            include("x86_64", "arm64-v8a")
        }
    }

    signingConfigs {
            create("releaseConfig") {
                val keystorePath = System.getenv("KEYSTORE_FILE") ?: "debug.keystore"
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }

        buildTypes {
            release {
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
                
                signingConfig = signingConfigs.getByName("releaseConfig")
            }
        }

    this.buildOutputs.all {
        val variantOutputImpl = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
        val variantName: String = variantOutputImpl.name
        variantOutputImpl.outputFileName = "tailscaled-${variantName}.apk"
    }

    buildTypes {
        release {
            // Enables code shrinking, obfuscation, and optimization for only
            // your project's release build type.
            isMinifyEnabled = true

            // Enables resource shrinking, which is performed by the
            // Android Gradle plugin.
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )


            if (System.getenv("KEYSTORE_PATH") != null)
                signingConfig = signingConfigs.getByName("releaseConfig")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(project(":appctr"))
    implementation(fileTree(mapOf("include" to listOf("*.aar", "*.jar"), "dir" to "libs")))
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation(libs.navigation.fragment.ktx)
    implementation(libs.navigation.ui.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
