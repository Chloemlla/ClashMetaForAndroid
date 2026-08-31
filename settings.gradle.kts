rootProject.name = "ClashMetaForAndroid"

include(":app")
include(":core")
include(":service")
include(":design")
include(":common")
include(":hideapi")
include(":sdk")

includeBuild("kaidl-compiler-patch")

pluginManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Project dependency repositories (B-137 / C-04).
// Moved out of the root `subprojects {}` block so a missing/expired token cannot
// be injected into every subproject's resolution, and to shrink the cross-project
// configuration that blocks configuration cache. A full convention-plugin migration
// (C-04) is tracked separately; this only relocates repository declarations.
dependencyResolutionManagement {
    // PREFER_SETTINGS so the declarations below are authoritative: AGP also injects
    // google()/mavenCentral() into each project, which would otherwise shadow these.
    repositoriesMode = RepositoriesMode.PREFER_SETTINGS
    repositories {
        mavenCentral()
        google()
        // STOP-G: MetaCubeX Maven mirror — see buildscript block in build.gradle.kts for
        // the supply-chain risk note. Provides com.github.kr328.golang and
        // com.github.kr328.kaidl artifacts not available on Maven Central / Google.
        maven("https://raw.githubusercontent.com/MetaCubeX/maven-backup/main/releases") {
            content {
                includeGroup("com.github.kr328.golang")
                includeGroup("com.github.kr328.kaidl")
            }
        }
        // README option C: no-auth release assets synced under ./local-maven.
        // Declared before GitHub Packages so the no-auth mirror wins when present.
        maven {
            name = "LocalMavenLumenCrash"
            url = rootDir.resolve("local-maven")
        }
        // B-137: only attach the GitHub Packages repo when credentials actually exist,
        // and narrow it to the lumen-crash group. With empty credentials this repo used
        // to 401 on every resolution and masked the real "dependency not found" error.
        val gprUser = providers.gradleProperty("gpr.user").orNull
            ?: System.getenv("GITHUB_ACTOR")
        val gprKey = providers.gradleProperty("gpr.key").orNull
            ?: System.getenv("GITHUB_TOKEN")
        if (!gprUser.isNullOrBlank() && !gprKey.isNullOrBlank()) {
            maven {
                name = "GitHubPackagesProjectLumen"
                url = uri("https://maven.pkg.github.com/Chloemlla/Project-Lumen")
                credentials {
                    username = gprUser
                    password = gprKey
                }
                content {
                    includeGroup("com.chloemlla.lumen")
                }
            }
        }
    }
}
