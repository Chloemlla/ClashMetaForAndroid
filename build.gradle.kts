@file:Suppress("UNUSED_VARIABLE")

import com.android.build.gradle.AppExtension
import com.android.build.gradle.BaseExtension
import org.gradle.api.GradleException
import java.net.URL
import java.util.*

buildscript {
    repositories {
        mavenCentral()
        google()
        maven("https://raw.githubusercontent.com/MetaCubeX/maven-backup/main/releases")
    }
    dependencies {
        classpath(libs.build.android)
        classpath(libs.build.kotlin.common)
        classpath(libs.build.kotlin.serialization)
        classpath(libs.build.ksp)
        classpath(libs.build.golang)
    }
}

subprojects {
    repositories {
        mavenCentral()
        google()
        maven("https://raw.githubusercontent.com/MetaCubeX/maven-backup/main/releases")
    }

    val isApp = name == "app"

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

            minSdk = 21
            targetSdk = 35

            versionName = "2.11.32"
            versionCode = 211032

            resValue("string", "release_name", "v$versionName")
            resValue("integer", "release_code", "$versionCode")

            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
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

        compileSdkVersion(defaultConfig.targetSdk!!)

        if (isApp) {
            packagingOptions {
                resources {
                    excludes.add("DebugProbesKt.bin")
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

                resValue("string", "launch_name", "@string/launch_name_alpha")
                resValue("string", "application_name", "@string/application_name_alpha")

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

                resValue("string", "launch_name", "@string/launch_name_meta")
                resValue("string", "application_name", "@string/application_name_meta")

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
            val requestedTasks = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }
            val releaseBuildRequested = requestedTasks.any {
                val taskName = it.lowercase(Locale.ROOT)
                taskName == "build" ||
                    taskName == "assemble" ||
                    taskName == "bundle" ||
                    (taskName.contains("release") &&
                        (taskName.startsWith("assemble") ||
                            taskName.startsWith("bundle") ||
                            taskName.startsWith("package") ||
                            taskName.startsWith("publish") ||
                            taskName.startsWith("sign")))
            }
            if (releaseBuildRequested && releaseKeystore == null) {
                throw GradleException(
                    "Release signing is required. Create signing.properties with " +
                        "keystore.file, keystore.password, key.alias, and key.password."
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
                    signingConfig = signingConfigs.findByName("release")
                }
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            }
            named("debug") {
                versionNameSuffix = ".debug"
            }
        }

        buildFeatures.apply {
            dataBinding {
                isEnabled = name != "hideapi"
            }
        }

        if (isApp) {
            this as AppExtension

            splits {
                abi {
                    isEnable = true
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
        // Issue suppressions also live in root lint.xml for merge/path-based ignores.
        lintOptions {
            isAbortOnError = true
            isCheckReleaseBuilds = true
            lintConfig = rootProject.file("lint.xml")
            disable("RestrictedApi")
            disable("MissingTranslation")
            disable("ExtraTranslation")
            disable("GradleDependency")
            disable("AndroidGradlePluginVersion")
            disable("UseTomlInstead")
            disable("IconLauncherShape")
            disable("IconDipSize")
            disable("IconDuplicatesConfig")
            disable("IconLocation")
            disable("IconDensities")
            disable("IconMissingDensityFolder")
            disable("UnusedResources")
            disable("VectorPath")
            disable("VectorRaster")
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

        file("gradle/wrapper/gradle-wrapper.properties")
            .appendText("distributionSha256Sum=$sha256")
    }
}
