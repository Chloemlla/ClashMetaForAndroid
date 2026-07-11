pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://raw.githubusercontent.com/MetaCubeX/maven-backup/main/releases")
    }
}

rootProject.name = "kaidl-compiler-patch"
