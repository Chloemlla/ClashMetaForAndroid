package com.github.kr328.clash.common.constants

import com.github.kr328.clash.common.util.packageName

object Migration {
    /**
     * Signature-protected permission guarding the migration provider.
     *
     * Derived from the applicationId so a fork and upstream ClashMetaForAndroid never collide on
     * the globally-unique custom permission name (the fixed upstream name causes
     * `INSTALL_FAILED_DUPLICATE_PERMISSION` when both are installed side by side).
     *
     * The actual trust control is the runtime `checkSignatures()` guard in
     * `MigrationProvider.enforceCaller`. The provider deliberately carries NO manifest-level
     * `android:permission` attribute: with an applicationId-derived name, a meta build would
     * hold "…meta.permission.MIGRATE_DATA" while the alpha provider would require
     * "…alpha.permission.MIGRATE_DATA" — different names, so the system-level signature check
     * would deny cross-flavor migration before the provider's own check ever ran. This constant
     * exists for documentation / tooling only; it is not wired to any manifest attribute.
     */
    val PERMISSION: String
        get() = "$packageName.permission.MIGRATE_DATA"

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