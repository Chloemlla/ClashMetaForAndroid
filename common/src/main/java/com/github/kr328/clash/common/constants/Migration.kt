package com.github.kr328.clash.common.constants

object Migration {
    const val PERMISSION = "com.github.metacubex.clash.permission.MIGRATE_DATA"
    const val AUTHORITY_SUFFIX = ".migration"
    const val BUNDLE_PATH = "bundle"
    const val FORMAT_VERSION = 1

    const val MANIFEST_FILE = "manifest.json"
    const val SERVICE_PREFS_FILE = "service_prefs.json"
    const val UI_PREFS_FILE = "ui_prefs.json"
    const val APP_PREFS_FILE = "app_prefs.json"
    const val PROFILES_FILE = "profiles.json"
    const val IMPORTED_DIR = "imported"
    const val PENDING_DIR = "pending"

    fun authorityFor(packageName: String): String = packageName + AUTHORITY_SUFFIX

    fun bundleUri(packageName: String): String =
        "content://${authorityFor(packageName)}/$BUNDLE_PATH"

    fun alphaPackageCandidates(currentPackageName: String): List<String> {
        val candidates = linkedSetOf<String>()
        when {
            currentPackageName.endsWith(".meta") ->
                candidates += currentPackageName.removeSuffix(".meta") + ".alpha"
            currentPackageName.endsWith(".Meta") ->
                candidates += currentPackageName.removeSuffix(".Meta") + ".alpha"
        }
        candidates += "com.github.metacubex.clash.alpha"
        candidates.remove(currentPackageName)
        return candidates.toList()
    }

    fun isMetaPackage(packageName: String): Boolean {
        return packageName.endsWith(".meta") || packageName.endsWith(".Meta")
    }
}