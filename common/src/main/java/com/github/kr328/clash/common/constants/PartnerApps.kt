package com.github.kr328.clash.common.constants

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.github.kr328.clash.common.log.Log

/**
 * Known partner apps that should be auto-included in VPN access control
 * and allowed to query lightweight Clash running status.
 *
 * ### Registry rule (v1)
 *
 * `isPartner = hardcode ∪ (meta-data present ∧ sharesSignatureWith(any installed hardcode
 * partner OR self))`
 *
 * The hardcoded [hardcodePackages] allowlist remains the trust root. Any other app is only
 * recognized as a partner when **both** of the following hold:
 * 1. it declares the [META_DATA_PARTNER_KEY] application meta-data flag, and
 * 2. it is signed with the same signing certificate as an already-installed hardcode partner,
 *    or the same certificate as this app itself (same-suite / same-signer builds).
 *
 * The meta-data flag alone is never trusted — an app cannot self-declare its way into partner
 * status without also sharing a signer with a package already on the static allowlist (or with
 * CMFA itself). This keeps discovery strictly additive: it never widens what a recognized
 * partner can do (still read-only status + VPN access-control auto-include, never
 * start/stop/toggle of the VPN — see F-12 in `SECURITY.md`), it only widens *who* can reach that
 * existing read-only surface.
 */
object PartnerApps {
    /** Application meta-data flag partner apps declare to opt into discovery. */
    const val META_DATA_PARTNER_KEY: String = "com.github.kr328.clash.partner"

    val piliPlusPackages: Set<String> = setOf(
        "com.chloemlla.piliplus",
        "com.chloemlla.piliplus.debug",
        "com.chloemlla.piliplus.dev",
    )

    val nexAiPackages: Set<String> = setOf(
        "com.chloemlla.nexai",
        "com.chloemlla.nexai.debug",
        "com.chloemlla.nexai.dev",
    )

    val projectLumenPackages: Set<String> = setOf(
        "com.chloemlla.projectlumen",
        "com.chloemlla.projectlumen.debug",
        "com.chloemlla.projectlumen.dev",
    )

    val zhihuPlusPackages: Set<String> = setOf(
        "com.chloemlla.zhplus",
        "com.chloemlla.zhplus.lite",
    )

    /** Statically known partner applicationIds (release + common suffixes); the trust root. */
    val hardcodePackages: Set<String> =
        piliPlusPackages + nexAiPackages + projectLumenPackages + zhihuPlusPackages

    /**
     * Backward-compatible alias for [hardcodePackages].
     *
     * This is the **static** allowlist only (no PackageManager access, no dynamic
     * meta-data/signature discovery). Kept so existing call sites that only need the
     * defensive "known package names, installed or not" set (e.g. deny-list exclusion)
     * keep working unchanged. Prefer [installedPartnerPackages] when a [Context] is
     * available and the full merged registry (including discovered partners) is needed.
     */
    val allPackages: Set<String> get() = hardcodePackages

    fun isPiliPlusPackage(packageName: String): Boolean =
        packageName in piliPlusPackages

    /**
     * Hardcode-only membership check (no [Context], no dynamic discovery).
     *
     * Prefer [isPartnerPackage] with a [Context] where available so meta-data-declared
     * partners sharing a trusted signer are also recognized.
     */
    fun isPartnerPackage(packageName: String): Boolean =
        packageName in hardcodePackages

    /**
     * Full partner membership check used by the runtime (StatusProvider / TunService):
     * hardcode allowlist OR discovered-and-signature-verified partner. See the class KDoc
     * for the exact rule.
     */
    fun isPartnerPackage(context: Context, packageName: String): Boolean {
        if (packageName in hardcodePackages) {
            return true
        }
        val pm = context.packageManager
        val trustedSigners = installedHardcodePackages(pm) + context.packageName
        return declaresPartnerMetaData(pm, packageName) &&
            trustedSigners.any { signer -> signaturesMatch(pm, packageName, signer) }
    }

    /**
     * Merged registry of partner packages currently installed on the device: the installed
     * subset of [hardcodePackages] plus any discovered-and-signature-verified partners.
     * Safe to call from access-control / status paths — failures during discovery are
     * swallowed and only shrink the discovered (never the hardcode) portion of the result.
     */
    fun installedPartnerPackages(context: Context): Set<String> {
        val pm = context.packageManager
        val installedHardcode = installedHardcodePackages(pm)
        val trustedSigners = installedHardcode + context.packageName
        return mergePartnerPackages(
            installedHardcode = installedHardcode,
            candidateMetaDataPackages = declaredPartnerCandidates(pm),
            trustedSigners = trustedSigners,
            signatureMatches = { candidate, signer -> signaturesMatch(pm, candidate, signer) },
        )
    }

    /** Backward-compatible alias used by older call sites. */
    fun installedPiliPlusPackages(context: Context): Set<String> =
        installedPartnerPackages(context)

    private fun installedHardcodePackages(pm: PackageManager): Set<String> =
        hardcodePackages.filterTo(mutableSetOf()) { isInstalled(pm, it) }

    private fun isInstalled(pm: PackageManager, packageName: String): Boolean {
        return try {
            pm.getApplicationInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** Application packages (any package, not just hardcode) that declare the partner flag. */
    private fun declaredPartnerCandidates(pm: PackageManager): Set<String> {
        return try {
            @Suppress("DEPRECATION")
            val apps: List<ApplicationInfo> = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            apps.asSequence()
                .filter { isTruthyPartnerMetaDataValue(it.metaData?.get(META_DATA_PARTNER_KEY)) }
                .map { it.packageName }
                .toSet()
        } catch (t: Throwable) {
            // Discovery is additive only; a PackageManager failure (e.g. transient binder
            // death, OEM restriction) must never block VPN startup or status queries.
            Log.w("PartnerApps: failed to enumerate installed applications for discovery", t)
            emptySet()
        }
    }

    private fun declaresPartnerMetaData(pm: PackageManager, packageName: String): Boolean {
        return try {
            val info = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            isTruthyPartnerMetaDataValue(info.metaData?.get(META_DATA_PARTNER_KEY))
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun signaturesMatch(pm: PackageManager, packageName: String, trustedPackageName: String): Boolean {
        if (packageName == trustedPackageName) {
            return true
        }
        val target = signingCertificatesOf(pm, packageName)
        if (target.isEmpty()) {
            return false
        }
        val trusted = signingCertificatesOf(pm, trustedPackageName)
        if (trusted.isEmpty()) {
            return false
        }
        return target.any { it in trusted }
    }

    @Suppress("DEPRECATION")
    private fun signingCertificatesOf(pm: PackageManager, packageName: String): Set<Signature> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = info.signingInfo ?: return emptySet()
                val history = if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory ?: signingInfo.apkContentsSigners
                }
                history?.toSet() ?: emptySet()
            } else {
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                info.signatures?.toSet() ?: emptySet()
            }
        } catch (_: PackageManager.NameNotFoundException) {
            emptySet()
        }
    }

    /**
     * Pure meta-data value coercion, extracted for unit testing without a real
     * [android.os.Bundle]/PackageManager. Accepts boolean `true`, the string/int `1`,
     * or the case-insensitive string `"true"`; anything else (including the flag being
     * merely present with an unrecognized value) is not truthy.
     */
    internal fun isTruthyPartnerMetaDataValue(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Int -> value == 1
        is String -> value == "1" || value.equals("true", ignoreCase = true)
        else -> false
    }

    /**
     * Pure registry set-math, extracted for unit testing with fakes (no PackageManager
     * or android.content.pm.Signature needed). Mirrors the merge performed by
     * [installedPartnerPackages]: candidates already in [installedHardcode] are skipped
     * (they are already included), and the rest are kept only when [signatureMatches]
     * reports a match against at least one entry of [trustedSigners].
     */
    internal fun mergePartnerPackages(
        installedHardcode: Set<String>,
        candidateMetaDataPackages: Set<String>,
        trustedSigners: Set<String>,
        signatureMatches: (candidate: String, trustedSigner: String) -> Boolean,
    ): Set<String> {
        val discovered = candidateMetaDataPackages.asSequence()
            .filter { candidate -> candidate !in installedHardcode }
            .filter { candidate -> trustedSigners.any { signer -> signatureMatches(candidate, signer) } }
            .toSet()
        return installedHardcode + discovered
    }
}
