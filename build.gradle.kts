@file:Suppress("UNUSED_VARIABLE")

import com.android.build.gradle.AppExtension
import com.android.build.gradle.BaseExtension
import org.gradle.api.GradleException
import java.io.File
import java.net.URL
import java.util.*

fun buildTimeIso(): String {
    val env = sequenceOf(
        System.getenv("BUILD_TIME"),
        System.getenv("SOURCE_DATE_EPOCH"),
    ).firstOrNull { !it.isNullOrBlank() }
    if (!env.isNullOrBlank()) {
        // SOURCE_DATE_EPOCH is unix seconds; BUILD_TIME may already be ISO.
        val epoch = env.toLongOrNull()
        if (epoch != null) {
            return java.time.Instant.ofEpochSecond(epoch).toString()
        }
        return env
    }
    return java.time.Instant.now().toString()
}

fun gitCommitHash(rootDir: File): String {
    val envHash = sequenceOf(
        System.getenv("GIT_COMMIT"),
        System.getenv("GITHUB_SHA"),
    ).firstOrNull { !it.isNullOrBlank() }
    if (!envHash.isNullOrBlank()) {
        return envHash.take(7)
    }
    return try {
        ProcessBuilder("git", "rev-parse", "--short=7", "HEAD")
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
            .inputStream
            .bufferedReader()
            .readText()
            .trim()
            .ifBlank { "unknown" }
    } catch (_: Exception) {
        "unknown"
    }
}

/**
 * The mihomo core version baked into the Go native library. The JNI bridge returns this from
 * `constant.Version`; exposing it as a BuildConfig field keeps the About dialog from loading the
 * `bridge` native library into the UI process just to read a string (B-78).
 */
fun goCoreVersion(rootDir: File): String {
    val versionFile = rootDir.resolve("core/src/foss/golang/clash/constant/version.go")
    return try {
        versionFile
            .readLines()
            .firstOrNull { it.trim().startsWith("Version") }
            ?.substringAfter("=")
            ?.substringAfter("\"")
            ?.substringBefore("\"")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "unknown"
    } catch (_: Exception) {
        "unknown"
    }
}

buildscript {
    repositories {
        mavenCentral()
        google()
        // STOP-G: MetaCubeX Maven mirror — raw GitHub content served as Maven repo,
        // no checksum or PGP verification. If this repo is hijacked or tampered with,
        // all builds silently fetch compromised artifacts (supply-chain injection).
        // Provides com.github.kr328.golang and com.github.kr328.kaidl artifacts not
        // available on Maven Central / Google. Migration path: republish these artifacts
        // to a controlled repository (GitHub Packages or Sonatype) with proper signing.
        maven("https://raw.githubusercontent.com/MetaCubeX/maven-backup/main/releases") {
            content {
                includeGroup("com.github.kr328.golang")
                includeGroup("com.github.kr328.kaidl")
            }
        }
    }
    dependencies {
        classpath(libs.build.android)
        classpath(libs.build.kotlin.common)
        classpath(libs.build.kotlin.serialization)
        classpath(libs.build.ksp)
        classpath(libs.build.golang)
        classpath(libs.build.kotlin.compose.compiler)
    }
}

subprojects {
    // Dependency repositories moved to settings.gradle.kts
    // (dependencyResolutionManagement) — B-137 / C-04.
    val isApp = name == "app"
    // Captured at subprojects scope: inside a productFlavor block `name` resolves to the
    // flavor name ("alpha"/"meta"), not the project name, so the flavor resValue guard must
    // use this captured value (see launch_name/application_name injection below).
    val isDesign = name == "design"

    apply(plugin = if (isApp) "com.android.application" else "com.android.library")

    fun queryConfigProperty(key: String): Any? {
        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localProperties.load(localPropertiesFile.inputStream())
        } else {
            return null
        }
        return localProperties.getProperty(key)
    }

    extensions.configure<BaseExtension> {
        buildFeatures.buildConfig = true
        defaultConfig {
            if (isApp) {
                val customApplicationId = queryConfigProperty("custom.application.id") as? String?
                applicationId = customApplicationId.takeIf { it?.isNotBlank() == true } ?: "com.github.metacubex.clash"
            }

            project.name.let { name ->
                namespace = if (name == "app") "com.github.kr328.clash"
                else "com.github.kr328.clash.$name"
            }

            // The only consumer of these libraries is :app, which ships minSdk 26, so a lower
            // library floor buys nothing and only makes lint flag API 23..25 calls that can
            // never run on an unsupported device.
            minSdk = 26
            // Android 17 (API 37). Runtime behaviors are handled via edge-to-edge,
            // predictive back, FGS specialUse, and INTERACT_ACROSS_USERS.
            targetSdk = 37

            versionName = "2.11.33"
            versionCode = 211033

            if (isApp) {
                val commitHash = gitCommitHash(rootProject.projectDir)
                val buildTime = buildTimeIso()
                val coreVersion = goCoreVersion(rootProject.projectDir)
                buildConfigField("String", "COMMIT_HASH", "\"$commitHash\"")
                buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
                // The mihomo core version, sourced from the Go source so the About dialog never
                // has to load the native library into the UI process (B-78).
                buildConfigField("String", "CORE_VERSION", "\"$coreVersion\"")
            }

            resValue("string", "release_name", "v$versionName")
            resValue("integer", "release_code", "$versionCode")

            // App module uses splits.abi below; AGP rejects ndk.abiFilters when
            // splits ABI filters are set. Libraries still need packaging filters.
            if (!isApp) {
                ndk {
                    abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                }
            }

            externalNativeBuild {
                cmake {
                    abiFilters("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                }
            }

            if (!isApp) {
                consumerProguardFiles("consumer-rules.pro")
            } else {
                setProperty("archivesBaseName", "cmfa-$versionName")
            }
        }

        ndkVersion = "29.0.14206865"

        // compileSdk must cover targetSdk; pin explicitly so library modules
        // cannot silently lag when target is bumped for platform work.
        // Why 37 (Android 17): targetSdk is 37 because the runtime behaviors this
        // app depends on (edge-to-edge enforcement, predictive back, FGS specialUse,
        // INTERACT_ACROSS_USERS) are gated by targetSdkVersion, not compileSdk.
        // AGP 8.13's tested ceiling is platform 36.1, so gradle.properties carries
        // android.suppressUnsupportedCompileSdk=37 to acknowledge the tooling gap.
        // Revisit when AGP officially supports 37 (C-03) — do not blindly downgrade,
        // that would silently change targetSdk-driven platform behavior.
        compileSdkVersion(37)

        if (isApp) {
            packagingOptions {
                resources {
                    excludes.add("DebugProbesKt.bin")
                    excludes.add("META-INF/*.kotlin_module")
                    excludes.add("META-INF/AL2.0")
                    excludes.add("META-INF/LGPL2.1")
                    excludes.add("META-INF/LICENSE*")
                    excludes.add("META-INF/NOTICE*")
                }
                // AGP 8 defaults to uncompressed page-aligned native libs + extractNativeLibs=false.
                // Several package installers (and older/custom ROMs) report that layout as
                // "package invalid" / INSTALL_FAILED_TEST_ONLY (-15) on sideload. Prefer the
                // legacy compressed+extract path for installability.
                jniLibs {
                    useLegacyPackaging = true
                }
            }
        }

        productFlavors {
            flavorDimensions("feature")

            val removeSuffix = (queryConfigProperty("remove.suffix") as? String)?.toBoolean() == true

            create("alpha") {
                isDefault = true
                dimension = flavorDimensionList[0]
                if (!removeSuffix) {
                    versionNameSuffix = ".Alpha"
                }


                buildConfigField("boolean", "PREMIUM", "Boolean.parseBoolean(\"false\")")

                // Only :app (manifest label, TileService) and :design (layouts, update/notice
                // dialogs) consume launch_name/application_name. Injecting them into every
                // module makes a standalone library build (e.g. :sdk:assemble, which does not
                // depend on :design) fail AAPT because launch_name_alpha lives only in :design.
                if (isApp || isDesign) {
                    resValue("string", "launch_name", "@string/launch_name_alpha")
                    resValue("string", "application_name", "@string/application_name_alpha")
                }

                if (isApp && !removeSuffix) {
                    applicationIdSuffix = ".alpha"
                }
            }

            create("meta") {

                dimension = flavorDimensionList[0]
                if (!removeSuffix) {
                    versionNameSuffix = ".Meta"
                }

                buildConfigField("boolean", "PREMIUM", "Boolean.parseBoolean(\"false\")")

                if (isApp || isDesign) {
                    resValue("string", "launch_name", "@string/launch_name_meta")
                    resValue("string", "application_name", "@string/application_name_meta")
                }

                if (isApp && !removeSuffix) {
                    applicationIdSuffix = ".meta"
                }
            }
        }

        sourceSets {
            getByName("meta") {
                java.srcDirs("src/foss/java")
            }
            getByName("alpha") {
                java.srcDirs("src/foss/java")
            }
        }

        val signingPropertiesFile = rootProject.file("signing.properties")
        val signingProperties = Properties()
        // Prefer signing.properties materialized from GitHub Actions secrets
        // (KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD).
        val releaseKeystore = if (isApp && signingPropertiesFile.isFile) {
            signingPropertiesFile.inputStream().use { signingProperties.load(it) }

            val requiredProperties = listOf(
                "keystore.file",
                "keystore.password",
                "key.alias",
                "key.password",
            )
            val missingProperties = requiredProperties.filter {
                signingProperties.getProperty(it).isNullOrBlank()
            }
            if (missingProperties.isNotEmpty()) {
                throw GradleException(
                    "Invalid signing.properties; missing: ${missingProperties.joinToString()}"
                )
            }

            rootProject.file(signingProperties.getProperty("keystore.file")).also {
                if (!it.isFile) {
                    throw GradleException("Release keystore does not exist: ${it.absolutePath}")
                }
            }
        } else {
            null
        }

        if (isApp) {
            val requestedTasks = gradle.startParameter.taskNames
            val releaseBuildRequested = requestedTasks.any { requested ->
                val taskName = requested.substringAfterLast(':').lowercase(Locale.ROOT)
                // Only tasks that actually build the :app module may require release signing:
                // a bare root-level task (e.g. `assemble`, which configures :app too) or an
                // explicit :app:... task. A non-app task such as `:sdk:assemble` (the CI SDK
                // facade compile) must never trip this gate — the app module is not being built.
                val appTargeted = requested == "build" || requested == "assemble" ||
                    requested == "bundle" || requested.startsWith(":app:")
                appTargeted && (
                    taskName == "build" || taskName == "assemble" || taskName == "bundle" ||
                        (taskName.contains("release") &&
                            (taskName.startsWith("assemble") ||
                                taskName.startsWith("bundle") ||
                                taskName.startsWith("package") ||
                                taskName.startsWith("publish") ||
                                taskName.startsWith("sign")))
                )
            }
            val onGithubActions = System.getenv("GITHUB_ACTIONS") == "true"
            if (releaseBuildRequested && releaseKeystore == null) {
                throw GradleException(
                    if (onGithubActions) {
                        "Release signing is required. Release APKs on CI must be signed " +
                            "with GitHub secrets (KEYSTORE_BASE64, KEYSTORE_PASSWORD, " +
                            "KEY_ALIAS, KEY_PASSWORD) via prepare-signing.sh."
                    } else {
                        "Release signing is required. Create signing.properties with " +
                            "keystore.file, keystore.password, key.alias, and key.password."
                    }
                )
            }

            signingConfigs {
                if (releaseKeystore != null) {
                    create("release") {
                        storeFile = releaseKeystore
                        storePassword = signingProperties.getProperty("keystore.password")
                        keyAlias = signingProperties.getProperty("key.alias")
                        keyPassword = signingProperties.getProperty("key.password")
                    }
                }
            }
        }

        buildTypes {
            named("release") {
                isMinifyEnabled = isApp
                isShrinkResources = isApp
                if (isApp) {
                    // Only attach when secret-backed signing.properties is present.
                    // Missing credentials are rejected above when a release task is requested.
                    signingConfig = signingConfigs.findByName("release")
                }
                // Package installers reject android:testOnly=true with code -15.
                // Keep release/debug publishable artifacts installable without adb -t.
                isDebuggable = false
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            }
            named("debug") {
                versionNameSuffix = ".debug"
                // Still installable via package installer when not IDE-injected as testOnly.
                isDebuggable = true
            }
        }

        buildFeatures.apply {
            dataBinding {
                isEnabled = name != "hideapi" && name != "sdk"
            }
        }

        if (isApp) {
            this as AppExtension

            splits {
                abi {
                    isEnable = true
                    // Keep per-ABI APKs for size, but also emit a universal APK so sideload
                    // users who pick the wrong artifact still get a package that installs.
                    // INSTALL_FAILED_TEST_ONLY (-15) / "package invalid" often comes from
                    // incomplete ABI-split packages installed via package installer UI.
                    isUniversalApk = true
                    reset()
                    include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                }
            }
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }

        // Keep CI lint as a quality gate while suppressing known false positives / noise.
        // Configured via BaseExtension.lintOptions (still the supported API on BaseExtension).
        // All issue suppressions live in root lint.xml with a per-issue rationale line, so
        // this block only carries the on/off switches (B-162).
        lintOptions {
            isAbortOnError = true
            isCheckReleaseBuilds = true
            lintConfig = rootProject.file("lint.xml")
        }
    }
}

task("clean", type = Delete::class) {
    delete(rootProject.buildDir)
}

tasks.wrapper {
    distributionType = Wrapper.DistributionType.ALL

    doLast {
        val sha256 = URL("$distributionUrl.sha256").openStream()
            .use { it.reader().readText().trim() }

        // Idempotent (B-163): re-running `wrapper` (e.g. when upgrading Gradle) must not
        // append a duplicate distributionSha256Sum line — two differing sums would make
        // Gradle use the later one while the earlier misleading one points debugging astray.
        val propertiesFile = file("gradle/wrapper/gradle-wrapper.properties")
        val existing = propertiesFile.readLines().filterNot { it.startsWith("distributionSha256Sum=") }
        propertiesFile.writeText(
            (existing + "distributionSha256Sum=$sha256").joinToString("\n") + "\n"
        )
    }
}
