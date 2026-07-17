package com.github.kr328.clash.design.model

import androidx.annotation.StringRes
import com.github.kr328.clash.design.R

/**
 * Curated third-party / upstream credits shown on the first-launch notice page.
 * Names and licenses stay English so they match upstream project metadata.
 */
data class OpenSourceDependency(
    val name: String,
    val author: String,
    @StringRes val descriptionRes: Int,
    val license: String,
    val url: String? = null,
) {
    companion object {
        fun defaults(): List<OpenSourceDependency> = listOf(
            OpenSourceDependency(
                name = "Clash Meta / mihomo",
                author = "MetaCubeX",
                descriptionRes = R.string.open_source_dep_clash_meta_core,
                license = "GPL-3.0",
                url = "https://github.com/MetaCubeX/mihomo",
            ),
            OpenSourceDependency(
                name = "Clash Meta for Android",
                author = "MetaCubeX / kr328",
                descriptionRes = R.string.open_source_dep_cmfa_upstream,
                license = "GPL-3.0",
                url = "https://github.com/MetaCubeX/ClashMetaForAndroid",
            ),
            OpenSourceDependency(
                name = "Clash for Android",
                author = "kr328",
                descriptionRes = R.string.open_source_dep_cfa,
                license = "GPL-3.0",
                url = "https://github.com/Kr328/ClashForAndroid",
            ),
            OpenSourceDependency(
                name = "Android Open Source Project / AndroidX",
                author = "Google / AOSP",
                descriptionRes = R.string.open_source_dep_androidx,
                license = "Apache-2.0",
                url = "https://developer.android.com/jetpack/androidx",
            ),
            OpenSourceDependency(
                name = "Material Components for Android",
                author = "Google",
                descriptionRes = R.string.open_source_dep_material,
                license = "Apache-2.0",
                url = "https://github.com/material-components/material-components-android",
            ),
            OpenSourceDependency(
                name = "Kotlin / kotlinx.coroutines",
                author = "JetBrains",
                descriptionRes = R.string.open_source_dep_kotlin,
                license = "Apache-2.0",
                url = "https://github.com/Kotlin/kotlinx.coroutines",
            ),
            OpenSourceDependency(
                name = "kotlinx.serialization",
                author = "JetBrains",
                descriptionRes = R.string.open_source_dep_serialization,
                license = "Apache-2.0",
                url = "https://github.com/Kotlin/kotlinx.serialization",
            ),
            OpenSourceDependency(
                name = "AndroidX Room",
                author = "Google",
                descriptionRes = R.string.open_source_dep_room,
                license = "Apache-2.0",
                url = "https://developer.android.com/jetpack/androidx/releases/room",
            ),
            OpenSourceDependency(
                name = "kaidl",
                author = "kr328",
                descriptionRes = R.string.open_source_dep_kaidl,
                license = "MIT",
                url = "https://github.com/Kr328/kaidl",
            ),
            OpenSourceDependency(
                name = "RikkaX MultiProcess Preference",
                author = "Rikka",
                descriptionRes = R.string.open_source_dep_rikkax,
                license = "MIT",
                url = "https://github.com/RikkaApps/RikkaX",
            ),
            OpenSourceDependency(
                name = "Quickie",
                author = "G00fY2",
                descriptionRes = R.string.open_source_dep_quickie,
                license = "Apache-2.0",
                url = "https://github.com/G00fY2/quickie",
            ),
            OpenSourceDependency(
                name = "gVisor (via core)",
                author = "Google",
                descriptionRes = R.string.open_source_dep_gvisor,
                license = "Apache-2.0",
                url = "https://github.com/google/gvisor",
            ),
            OpenSourceDependency(
                name = "meta-rules-dat",
                author = "MetaCubeX",
                descriptionRes = R.string.open_source_dep_meta_rules,
                license = "GPL-3.0",
                url = "https://github.com/MetaCubeX/meta-rules-dat",
            ),
            OpenSourceDependency(
                name = "Lumen Crash SDK",
                author = "Chloemlla",
                descriptionRes = R.string.open_source_dep_lumen_crash,
                license = "Apache-2.0",
                url = "https://github.com/Chloemlla/Project-Lumen",
            ),
        )
    }
}
