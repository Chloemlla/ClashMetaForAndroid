plugins {
    kotlin("android")
    id("com.android.library")
}

dependencies {
    api(project(":service"))
    api(project(":core"))
    api(project(":common"))

    implementation(libs.kotlin.coroutine)
    implementation(libs.androidx.core)
    implementation(libs.kaidl.runtime)
}

// Ensure KSP-generated Binder stubs from :service are visible to consumers at compile time
// via the service AAR; no KSP needed in this facade module.