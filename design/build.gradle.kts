plugins {
    kotlin("android")
    kotlin("kapt")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    implementation(project(":common"))
    implementation(project(":core"))
    implementation(libs.kotlin.coroutine)
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.coordinator)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.viewpager)
    implementation(libs.google.material)

    // undraw dynamic-color illustrations (Compose ImageVector islands in ViewBinding empty states)
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")

    testImplementation(libs.test.junit)
}

android {
    buildFeatures {
        compose = true
    }
}
