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
    // The large-screen Proxy layout keeps the existing pager detail and adds a group rail.
    implementation(libs.androidx.slidingpanelayout)
    implementation(libs.google.material)
    // Encode-only QR for profile export (scanner stays on app via quickie).
    implementation(libs.zxing.core)

    // undraw dynamic-color illustrations (Compose ImageVector islands in ViewBinding empty states)
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)

    testImplementation(libs.test.junit)
}

android {
    buildFeatures {
        compose = true
    }
}
