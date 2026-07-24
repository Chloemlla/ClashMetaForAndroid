plugins {
    kotlin("android")
    id("kotlinx-serialization")
    id("com.android.library")
    id("com.google.devtools.ksp")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":common"))

    ksp("com.github.kr328.kaidl.patched:kaidl-compiler-patch:1.15.1")
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlin.coroutine)
    implementation(libs.kotlin.serialization.json)
    implementation(libs.androidx.core)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kaidl.runtime)
    implementation(libs.rikkax.multiprocess)

    testImplementation(libs.test.junit)
}

afterEvaluate {
    android {
        libraryVariants.forEach {
            sourceSets[it.name].kotlin.srcDir(buildDir.resolve("generated/ksp/${it.name}/kotlin"))
            sourceSets[it.name].java.srcDir(buildDir.resolve("generated/ksp/${it.name}/java"))
        }
    }
}
