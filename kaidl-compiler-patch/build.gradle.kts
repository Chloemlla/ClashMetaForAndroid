import org.gradle.api.file.DuplicatesStrategy

plugins {
    kotlin("jvm") version "2.1.0"
}

group = "com.github.kr328.kaidl.patched"
version = "1.15.1"

val kaidlBase by configurations.creating

dependencies {
    kaidlBase("com.github.kr328.kaidl:kaidl:1.15") {
        isTransitive = false
    }

    compileOnly("com.github.kr328.kaidl:kaidl:1.15") {
        isTransitive = false
    }

    implementation("com.google.devtools.ksp:symbol-processing-api:2.1.0-1.0.29")
    implementation("com.squareup:kotlinpoet:1.9.0")
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from({ kaidlBase.map { zipTree(it) } }) {
        exclude("META-INF/MANIFEST.MF")
        exclude("com/github/kr328/kaidl/builder/ParcelKt.class")
    }

    from("LICENSE") {
        into("META-INF")
        rename { "LICENSE-kaidl" }
    }
}
