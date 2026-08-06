pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // STOP-G: MetaCubeX Maven mirror — raw GitHub content, no checksum/PGP.
        // Provides com.github.kr328.kaidl artifacts not on Maven Central.
        // Migration path: republish to a controlled repository (GitHub Packages or Sonatype).
        maven("https://raw.githubusercontent.com/MetaCubeX/maven-backup/main/releases") {
            content {
                includeGroup("com.github.kr328.kaidl")
            }
        }
    }
}

rootProject.name = "kaidl-compiler-patch"
