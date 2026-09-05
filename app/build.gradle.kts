plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlinSerialization)
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

val releaseKeystorePath: String? = System.getenv("KEYSTORE_FILE")

android {
    namespace = "io.github.bropines.tailscaled"
    // compileSdk = 37 (не 36): core-ktx 1.17.0 требует как минимум 36
    compileSdk = 37

    signingConfigs {
        create("release") {
            if (releaseKeystorePath != null) {
                storeFile = file(releaseKeystorePath)
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "IS_DEV", "false")
            versionNameSuffix = ".release"
            
            // Deliberately left unsigned when no keystore is supplied.
            //
            // Falling back to the debug key here produced a release APK that
            // installs once and can then never be updated by a properly signed
            // build: Android refuses any update whose certificate differs, so the
            // only way out is uninstalling and losing the app's state. Use
            // assembleDebug for a locally installable build — it carries the
            // .dev suffix and coexists with the real one.
            if (releaseKeystorePath != null) {
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
    implementation(libs.kotlinx.serialization.json)
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
    
    // Jetpack AppFunctions API (Gemini On-Device Integration)
    implementation(libs.androidx.appfunctions)
    ksp(libs.androidx.appfunctions.compiler)
    
    debugImplementation(libs.androidx.ui.tooling)
}

ksp {
    arg("appfunctions:aggregateAppFunctions", "true")
}

// Bundle the repository's CHANGELOG.md into the APK as assets/CHANGELOG.md so the
// app can show "What's new" after an update. The file is copied at build time
// into a generated assets directory; nothing is committed under src/. Assets are
// not touched by resource shrinking, so R8/shrinkResources cannot drop it.
val changelogAssetsDir = layout.buildDirectory.dir("generated/changelog/assets")
// Sync, not Copy: if CHANGELOG.md is ever renamed, a stale copy must not keep shipping.
val copyChangelogAsset by tasks.registering(Sync::class) {
    group = "build"
    description = "Copies ../CHANGELOG.md into the generated assets directory"
    from(layout.projectDirectory.file("../CHANGELOG.md"))
    into(changelogAssetsDir)
}
android.sourceSets["main"].assets.srcDir(changelogAssetsDir)
tasks.named("preBuild") {
    dependsOn(copyChangelogAsset)
}
// The asset merger reads the directory directly; declare the producer so Gradle
// never sees an undeclared task-output dependency (and every variant gets it).
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(copyChangelogAsset)
}

tasks.matching { it.name.contains("AarMetadata") }.configureEach {
    enabled = false
}

// R8 cannot see JNI. hev-socks5-tunnel registers its whole method table with
// RegisterNatives inside JNI_OnLoad, so if even one `external fun` is shrunk
// away, System.loadLibrary throws NoSuchMethodError and the process dies — this
// happened once (TProxyGetStats was unused from Kotlin, R8 dropped it, and every
// stop crashed the app). Cross-check R8's own reports right after minification
// and fail the build instead of shipping it:
//  - seeds.txt lists everything matched by a keep rule; each external fun must be
//    there, or nothing guarantees its name and body survive;
//  - usage.txt lists everything removed; no native member may appear in it.
val verifyReleaseNativeMethods by tasks.registering {
    group = "verification"
    description = "Fails if R8 removed or did not keep any JNI (external) method"
    val srcDir = layout.projectDirectory.dir("src/main/java")
    val mappingDir = layout.buildDirectory.dir("outputs/mapping/release")
    // No inputs/outputs declared on purpose: the task must always run, and
    // declaring the mapping dir as an input made Gradle fail with a generic
    // "directory does not exist" before the explanatory check below could.
    doLast {
        val seeds = mappingDir.get().file("seeds.txt").asFile
        val usage = mappingDir.get().file("usage.txt").asFile
        if (!seeds.exists() || !usage.exists()) {
            throw GradleException("R8 reports not found in ${mappingDir.get()}; run minifyReleaseWithR8 first.")
        }
        val externals = srcDir.asFileTree.matching { include("**/*.kt") }.files
            .flatMap { f -> Regex("""\bexternal\s+fun\s+(\w+)\s*\(""").findAll(f.readText()).map { it.groupValues[1] }.toList() }
            .toSortedSet()
        val seedText = seeds.readText()
        val notKept = externals.filterNot { name -> Regex("""^[\w.$]+: .*\b$name\(""", RegexOption.MULTILINE).containsMatchIn(seedText) }
        val removedNative = mutableListOf<String>()
        var cls = ""
        usage.forEachLine { line ->
            if (line.isNotEmpty() && !line[0].isWhitespace()) cls = line.removeSuffix(":")
            else if (" native " in line) removedNative += "$cls: ${line.trim()}"
        }
        if (notKept.isNotEmpty() || removedNative.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("R8 broke the JNI surface; the release would crash at System.loadLibrary.")
                    if (notKept.isNotEmpty()) appendLine("  external funs not matched by any keep rule (missing from seeds.txt): $notKept")
                    if (removedNative.isNotEmpty()) appendLine("  native members removed by shrinking (usage.txt):\n    " + removedNative.joinToString("\n    "))
                    appendLine("Fix app/proguard-rules.pro (plain -keep, not -keepclasseswithmembernames, for native <methods>).")
                }
            )
        }
        logger.lifecycle("-> JNI check: ${externals.size} external funs (${externals.joinToString()}) all kept by R8")
    }
}
// The Go bridge (appctr/tmp/appctr.aar) and the daemon binaries in jniLibs are
// prebuilt by appctr/build.sh; Gradle only packages them. Nothing used to notice
// when they were older than the Go sources, and a whole day of Go fixes once
// shipped in an APK that did not contain them. A release must not be built from
// a stale bridge; a debug build warns.
val verifyGoBridgeFresh by tasks.registering {
    group = "verification"
    description = "Fails a release if appctr/tmp/appctr.aar is older than appctr/*.go or the patches"
    val appctrDir = layout.projectDirectory.dir("../appctr")
    val failOnStale = gradle.startParameter.taskNames.any { it.contains("Release") }
    doLast {
        val aar = appctrDir.file("tmp/appctr.aar").asFile
        if (!aar.exists()) {
            logger.warn("-> Go bridge check: appctr/tmp/appctr.aar missing, falling back to appctr/appctr.aar")
            return@doLast
        }
        val sources = (appctrDir.asFile.listFiles { f -> f.isFile && f.name.endsWith(".go") } ?: emptyArray()) +
            (appctrDir.dir("patches").asFile.listFiles { f -> f.isFile } ?: emptyArray())
        val newest = sources.maxByOrNull { it.lastModified() }
        if (newest != null && newest.lastModified() > aar.lastModified()) {
            val msg = "Go bridge is STALE: ${newest.relativeTo(appctrDir.asFile)} is newer than appctr/tmp/appctr.aar. " +
                "Run appctr/build.sh (ANDROID_NDK_HOME set) before building; the APK would not contain the Go changes."
            if (failOnStale) throw GradleException(msg) else logger.warn("-> WARNING: $msg")
        } else {
            logger.lifecycle("-> Go bridge check: appctr.aar is newer than every Go source and patch")
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyGoBridgeFresh) }

tasks.matching { it.name == "minifyReleaseWithR8" }.configureEach {
    finalizedBy(verifyReleaseNativeMethods)
}
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn(verifyReleaseNativeMethods)
}

// Refuse to package a release that nothing can sign, instead of emitting an
// artifact that looks finished and turns out to be uninstallable or, worse,
// signed with a throwaway key.
tasks.matching { it.name.startsWith("package") && it.name.endsWith("Release") }.configureEach {
    doFirst {
        val path = System.getenv("KEYSTORE_FILE")
            ?: throw GradleException(
                """
                Release builds require a signing keystore.

                Set KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD, e.g.

                  KEYSTORE_FILE="${'$'}PWD/tailsocks.jks" KEYSTORE_PASSWORD=... \
                  KEY_ALIAS=... KEY_PASSWORD=... ./gradlew app:assembleRelease

                For a build you just want to install locally, use ./gradlew app:assembleDebug —
                it carries the .dev application id and installs alongside the real app.
                """.trimIndent()
            )

        if (!file(path).exists()) {
            throw GradleException("KEYSTORE_FILE points at a missing file: $path")
        }
        for (v in listOf("KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")) {
            if (System.getenv(v).isNullOrBlank()) {
                throw GradleException("KEYSTORE_FILE is set but $v is empty; release signing needs all four values.")
            }
        }
    }
}