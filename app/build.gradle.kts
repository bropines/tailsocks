import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.screenshot)
}

// The version lives in version.properties, where a release bumps it and F-Droid
// reads it. It used to be computed from git — the commit count for the code, the
// last tag plus the hash for the name — which no outside builder could predict.
val appVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val appVersionName: String = appVersion.getProperty("VERSION_NAME")
val appVersionCode: Int = appVersion.getProperty("VERSION_CODE").toInt()

// The commit, for the About screen and the diagnostics: two builds between
// releases carry the same version and differ only here.
val gitHash = providers.exec {
    commandLine("git", "rev-parse", "--short=6", "HEAD")
    workingDir = rootDir
}.standardOutput.asText.map { it.trim() }.getOrElse("unknown")

println("-> Build VersionCode: $appVersionCode")
println("-> Build VersionName: $appVersionName ($gitHash)")

val releaseKeystorePath: String? = System.getenv("KEYSTORE_FILE")

// The ABIs to build. A builder that packages one APK per ABI — F-Droid's
// per-ABI entries — passes -PtargetAbi=<abi> and gets that one (and a universal
// APK holding the same single ABI, see splits); unset, all four and the universal one.
val allAbis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
val targetAbi: String? = project.findProperty("targetAbi")?.toString()?.also {
    require(it in allAbis) { "-PtargetAbi must be one of $allAbis, not $it" }
}
val builtAbis = targetAbi?.let { listOf(it) } ?: allAbis

// Each ABI's APK has its own versionCode: VERSION_CODE plus 1 to 4, in the
// digits version.properties leaves free, so a store can offer a device the APK
// for its ABI. The universal APK keeps VERSION_CODE itself.
val abiVersionOffset = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86" to 3, "x86_64" to 4)

android {
    // Preview screenshot tests (src/screenshotTest): @Preview functions are
    // rendered on the build machine in every geometry we declare — phone,
    // landscape, an unfolded foldable, a tablet — so a layout can be judged
    // without a device. Run ./gradlew :app:updateDebugScreenshotTest.
    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    namespace = "io.github.bropines.tailscaled"
    // compileSdk = 37 (не 36): core-ktx 1.17.0 требует как минимум 36
    compileSdk = 37
    // The dependency metadata block in the APK is readable only by Google and
    // tells the user nothing; F-Droid and IzzyOnDroid ask for it to be left out.
    dependenciesInfo {
        includeInApk = false
    }

    // Pinned so every build — local, CI, an F-Droid builder — uses the same
    // NDK; otherwise AGP takes its own default and CI took whichever it found.
    ndkVersion = "28.2.13676358"

    // ./gradlew lintDebug -PlintNewApiOnly: fail on nothing but a call above
    // minSdk. CI runs this on every build. Android 10 users met three such
    // crashes in 4.4.3 that this check reports; the full lint run still has
    // hundreds of style findings and is not a gate.
    if (project.hasProperty("lintNewApiOnly")) {
        lint {
            checkOnly += "NewApi"
            abortOnError = true
            checkDependencies = false
        }
    }

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
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")

        // With one ABI the splits alone choose it: AGP refuses abiFilters
        // beside ABI splits that have no universal APK.
        if (targetAbi == null) {
            ndk {
                abiFilters.addAll(allAbis)
            }
        }
        externalNativeBuild {
            ndkBuild {
                arguments("APP_CFLAGS+=-DPKGNAME=io/github/bropines/tailscaled/core -DCLSNAME=TunVpnService -ffile-prefix-map=${rootDir}=.")
                arguments("APP_LDFLAGS+=-Wl,--build-id=none")
                // Build only what the app loads. hev-socks5-tunnel/Android.mk also
                // declares hev-socks5-tunnel-bin, a standalone program from
                // upstream's own packaging: it compiles the same sources a second
                // time for every ABI and nothing here runs it — TunVpnService
                // loads the shared library. Named here rather than edited out of
                // the makefile, because that file belongs to a submodule where the
                // change would live on one disk and nowhere else.
                targets("byedpi", "hev-socks5-tunnel")
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
            include(*builtAbis.toTypedArray())
            // Always, though F-Droid's -PtargetAbi build ships only its one ABI: the
            // per-ABI APK must be made the same way in both builds. AGP's manifest
            // merger moves every activity-alias to just after its target activity, which
            // reverses a run of aliases; a split whose versionCode differs from the
            // variant's is merged once more (reversed back) only when it is not the sole
            // output. Without the universal APK beside it, F-Droid's armeabi-v7a APK had the
            // launcher aliases in the opposite order to ours and 4.7.2 failed to verify.
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

    // Whether the app asks GitHub for a newer release on launch by default.
    // True for the builds published here; a store that updates the app itself
    // (F-Droid) builds with -PupdateCheckDefault=false, and the user can switch
    // it either way in Settings.
    defaultConfig.buildConfigField(
        "boolean", "UPDATE_CHECK_DEFAULT",
        (project.findProperty("updateCheckDefault") ?: "true").toString()
    )

    // Whether the app can update itself from GitHub at all: the launch check,
    // the About screen's check, download and install, and the
    // REQUEST_INSTALL_PACKAGES permission they need. F-Droid builds with
    // -PselfUpdate=false: it delivers updates itself, its policy has an app
    // download no executable code, and a GitHub APK would not install over its
    // signature anyway. The permission goes through a release manifest that
    // removes it.
    val selfUpdate = (project.findProperty("selfUpdate") ?: "true").toString().toBoolean()
    defaultConfig.buildConfigField("boolean", "SELF_UPDATE", selfUpdate.toString())
    if (!selfUpdate) {
        sourceSets["release"].manifest.srcFile("src/noSelfUpdate/AndroidManifest.xml")
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

    // Bytecode for Java 17, from whichever JDK runs Gradle (21 on CI and on
    // F-Droid's buildserver): Android compilations put android.jar, not the
    // JDK's classes, on the classpath, so the JDK does not change the output.
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }


    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Plain JVM tests (src/test): the Admin API layer against recorded
    // responses, the safety classifier, the credential migration. Android
    // stubs answer with defaults instead of throwing, so a stray Log call in
    // code under test does not fail it; nothing there may depend on them.
    testOptions {
        unitTests.isReturnDefaultValues = true
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

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.firstOrNull {
                it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI
            }?.identifier
            output.versionCode.set(appVersionCode + (abiVersionOffset[abi] ?: 0))
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
    // Custom Tabs: the login page opens over the app (ui/LoginTab.kt).
    implementation(libs.androidx.browser)
    implementation(libs.navigation.compose)
    
    implementation("androidx.compose.material:material-icons-extended:1.7.0")
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    
    // Jetpack AppFunctions API (Gemini On-Device Integration)
    implementation(libs.androidx.appfunctions)
    ksp(libs.androidx.appfunctions.compiler)
    
    implementation(libs.androidx.material3.adaptive)
    // QR codes for addresses and links, drawn by ui/QrCode.kt; display only.
    implementation(libs.qrcodegen)
    // ...and to scan (ui/QrScanActivity.kt): the camera through CameraX, its
    // preview drawn by camera-compose, the codes read by ZXing's core.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)
    implementation(libs.zxing.core)
    // The admin console's background checks for what needs attention (admin/notify/).
    implementation(libs.androidx.work.runtime.ktx)
    compileOnly(libs.guava)
    debugImplementation(libs.androidx.ui.tooling)
    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
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

// AGP's AAR-metadata check is disabled, and here is the reason it was missing:
// androidx.appfunctions:appfunctions:1.0.0-alpha10 declares it requires Android
// Gradle plugin 9.1.0 while this project builds on 8.13.2, so the check fails the
// release even though the library works — the app ships fourteen AppFunctions
// built against it. Re-enable this the moment AGP moves to 9.1.0 or the library
// relaxes the requirement; until then, know that nothing is verifying the
// metadata of any other dependency either.
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
        // R8 writes a wholesale-removed class as a bare name on its own line and
        // lists indented members only for classes that survived. Scanning for
        // " native " alone therefore missed the heaviest case — the class holding
        // the native methods removed entirely — so removed classes are collected
        // too and matched against the ones our sources declare externals in.
        val removedClasses = mutableListOf<String>()
        val externalOwners = srcDir.asFileTree.matching { include("**/*.kt") }.files
            .filter { it.readText().contains(Regex("""\bexternal\s+fun\s""")) }
            .flatMap { f ->
                val text = f.readText()
                val pkg = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
                Regex("""^\s*(?:internal\s+|private\s+|public\s+)?(?:object|class)\s+(\w+)""", RegexOption.MULTILINE)
                    .findAll(text).map { m -> if (pkg != null) "$pkg.${m.groupValues[1]}" else m.groupValues[1] }.toList()
            }.toSet()
        var cls = ""
        usage.forEachLine { line ->
            if (line.isNotEmpty() && !line[0].isWhitespace()) {
                cls = line.removeSuffix(":")
                if (!line.endsWith(":") && cls in externalOwners) removedClasses += cls
            }
            else if (" native " in line) removedNative += "$cls: ${line.trim()}"
        }
        if (notKept.isNotEmpty() || removedNative.isNotEmpty() || removedClasses.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("R8 broke the JNI surface; the release would crash at System.loadLibrary.")
                    if (notKept.isNotEmpty()) appendLine("  external funs not matched by any keep rule (missing from seeds.txt): $notKept")
                    if (removedNative.isNotEmpty()) appendLine("  native members removed by shrinking (usage.txt):\n    " + removedNative.joinToString("\n    "))
                    if (removedClasses.isNotEmpty()) appendLine("  whole classes holding native methods removed (usage.txt):\n    " + removedClasses.joinToString("\n    "))
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
    // Keyed on the task graph, not on the text of the command. Reading
    // startParameter.taskNames only saw what the user typed, so `./gradlew build`
    // and Android Studio's Run button — which resolve to release tasks by other
    // names — walked past the check that exists to stop exactly that.
    val releaseInGraph = objects.property(Boolean::class.java).convention(false)
    gradle.taskGraph.whenReady { releaseInGraph.set(allTasks.any { t -> t.name.contains("Release", ignoreCase = true) }) }
    doLast {
        val aar = appctrDir.file("tmp/appctr.aar").asFile
        if (!aar.exists()) {
            logger.warn("-> Go bridge check: appctr/tmp/appctr.aar missing, falling back to appctr/appctr.aar")
            return@doLast
        }
        // Tests are not in the APK; a new one must not fail a release.
        val sources = (appctrDir.asFile.listFiles { f -> f.isFile && f.name.endsWith(".go") && !f.name.endsWith("_test.go") } ?: emptyArray()) +
            (appctrDir.dir("patches").asFile.listFiles { f -> f.isFile } ?: emptyArray())
        val newest = sources.maxByOrNull { it.lastModified() }
        if (newest != null && newest.lastModified() > aar.lastModified()) {
            val msg = "Go bridge is STALE: ${newest.relativeTo(appctrDir.asFile)} is newer than appctr/tmp/appctr.aar. " +
                "Run appctr/build.sh (ANDROID_NDK_HOME set) before building; the APK would not contain the Go changes."
            if (releaseInGraph.get()) throw GradleException(msg) else logger.warn("-> WARNING: $msg")
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
// One deliberate exception: -PallowUnsignedRelease, for a builder that signs
// the APK itself afterwards (F-Droid). It has to be asked for by name, so an
// ordinary build still cannot slip out unsigned.
val allowUnsignedRelease = project.hasProperty("allowUnsignedRelease")
tasks.matching { it.name.startsWith("package") && it.name.endsWith("Release") }.configureEach {
    doFirst {
        if (allowUnsignedRelease) return@doFirst
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