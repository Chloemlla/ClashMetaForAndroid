package com.github.kr328.clash.common.constants

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.github.kr328.clash.common.log.Log
import java.security.MessageDigest

/**
 * Known partner apps that should be auto-included in VPN access control
 * and allowed to query lightweight Clash running status.
 *
 * ### Registry rule (v2)
 *
 * `isPartner = hardcode ∪ (meta-data present ∧ trustedSigner)`, where a signer is trusted when
 * its certificate digest is pinned in [trustedSignerSha256] **or** it matches an already
 * installed hardcode partner / this app itself.
 *
 * The hardcoded [hardcodePackages] allowlist remains the trust root. Any other app is only
 * recognized as a partner when **both** of the following hold:
 * 1. it declares the [META_DATA_PARTNER_KEY] application meta-data flag, and
 * 2. it is signed with a pinned release certificate, with the same certificate as an already
 *    installed hardcode partner, or with the same certificate as this app itself.
 *
 * The meta-data flag alone is never trusted — an app cannot self-declare its way into partner
 * status without also presenting a trusted signer. This keeps discovery strictly additive: it
 * never widens what a recognized partner can do (still read-only status + VPN access-control
 * auto-include, never start/stop/toggle of the VPN — see F-12 in `SECURITY.md`), it only widens
 * *who* can reach that existing read-only surface.
 */
object PartnerApps {
    /** Application meta-data flag partner apps declare to opt into discovery. */
    const val META_DATA_PARTNER_KEY: String = "com.github.kr328.clash.partner"

    /**
     * Pinned SHA-256 digests (lowercase hex, no separators) of release signing certificates
     * accepted as partner identities.
     *
     * Every suite app is signed with its own key, so "shares a signing certificate with CMFA or
     * another installed hardcode partner" holds for no real-world install — the anti-spoofing
     * gate rejected exactly the genuine partners it was meant to protect. Pinning the release
     * certificates restores recognition without weakening the gate: an impostor squatting a
     * hardcode applicationId still needs the matching private key.
     *
     * Read a digest with `apksigner verify --print-certs <apk>` (`Signer #1 certificate SHA-256
     * digest`) and add it here when a partner's signing key is introduced or rotated.
     */
    val trustedSignerSha256: Set<String> = setOf(
        // CDict release key (CN=cdict)
        "8d9b6c640b027d7439e594f56682b9e31c38c7588a0c0cc02189da8c1fe91862",
        // PiliPlus release key
        "f81faa94443032b07c8f2bb4255d2896b547be95c201ebd6db3b88d1e9e5b89d",
        // NexAI release key
        "ffd1f37c27051acc7fa18745e107e6179a28572619b63fc6f74dac3da44ed7ce",
        // Project-Lumen release key
        "0fa11497243b9a4375035c540709ff8f7d59c6ac67624319027990707ffbcac4",
        // Aura release key
        "a54881fbaf114dc0ca4c40e6644ca2b4c289b8de0bfe44b706981dcda1a51374",
    )

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

    val auraPackages: Set<String> = setOf(
        "com.chloemlla.aura",
        "com.chloemlla.aura.debug",
        "com.chloemlla.aura.dev",
    )

    val cdictPackages: Set<String> = setOf(
        "com.chloemlla.cdict",
        "com.chloemlla.cdict.debug",
        "com.chloemlla.cdict.dev",
    )

    /** Statically known partner applicationIds (release + common suffixes); the trust root. */
    val hardcodePackages: Set<String> =
        piliPlusPackages + nexAiPackages + projectLumenPackages + zhihuPlusPackages +
            auraPackages + cdictPackages

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
     *
     * An installed app that borrows a hardcoded partner applicationId is trusted only when it
     * presents a pinned release certificate (see [trustedSignerSha256]) or shares a signing
     * certificate with CMFA or another installed hardcode partner (see [sharesTrustedSignature]);
     * a spoofed install under a known partner name must not read partner status for free. A
     * package that is not installed keeps static membership (used for deny-list exclusion) since
     * there is no app to impersonate at runtime.
     */
    fun isPartnerPackage(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        if (packageName in hardcodePackages) {
            if (isInstalled(pm, packageName) &&
                !hasPinnedSigner(pm, packageName) &&
                !sharesTrustedSignature(pm, packageName, context.packageName)
            ) {
                return false
            }
            return true
        }
        if (!declaresPartnerMetaData(pm, packageName)) {
            return false
        }
        if (hasPinnedSigner(pm, packageName)) {
            return true
        }
        val trustedSigners = installedHardcodePackages(pm) + context.packageName
        return trustedSigners.any { signer -> signaturesMatch(pm, packageName, signer) }
    }

    /** True when any signing certificate of [packageName] matches a pinned release certificate. */
    private fun hasPinnedSigner(pm: PackageManager, packageName: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return try {
                trustedSignerSha256.any { digest ->
                    pm.hasSigningCertificate(
                        packageName,
                        digest.hexToByteArray(),
                        PackageManager.CERT_INPUT_SHA256,
                    )
                }
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
        return matchesPinnedSigner(
            signingCertificatesOf(pm, packageName).map { sha256Hex(it.toByteArray()) },
        )
    }

    /**
     * True when [packageName] (an installed hardcode partner) shares at least one signing
     * certificate with CMFA itself or with any other installed hardcode partner. The package
     * itself is excluded from the anchor set so it cannot act as its own trust anchor.
     */
    private fun sharesTrustedSignature(pm: PackageManager, packageName: String, selfPackage: String): Boolean {
        val target = signingCertificatesOf(pm, packageName)
        if (target.isEmpty()) {
            return false
        }
        val anchors = installedHardcodePackages(pm).filter { it != packageName } + selfPackage
        return anchors.any { trusted ->
            val trustedCerts = signingCertificatesOf(pm, trusted)
            trustedCerts.isNotEmpty() && target.any { it in trustedCerts }
        }
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
            .filterTo(mutableSetOf()) { packageName ->
                hasPinnedSigner(pm, packageName) ||
                    sharesTrustedSignature(pm, packageName, context.packageName)
            }
        val trustedSigners = installedHardcode + context.packageName
        return mergePartnerPackages(
            installedHardcode = installedHardcode,
            candidateMetaDataPackages = declaredPartnerCandidates(pm),
            trustedSigners = trustedSigners,
            signatureMatches = { candidate, signer ->
                hasPinnedSigner(pm, candidate) || signaturesMatch(pm, candidate, signer)
            },
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
     * Pure certificate-digest membership test, extracted for unit testing without a real
     * [Signature]/PackageManager. Digests are compared case-insensitively against
     * [trustedSignerSha256].
     */
    internal fun matchesPinnedSigner(certificateDigests: Collection<String>): Boolean =
        certificateDigests.any { it.lowercase() in trustedSignerSha256 }

    private fun String.hexToByteArray(): ByteArray =
        ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }

    /** Lowercase hex SHA-256, matching the digest `apksigner verify --print-certs` reports. */
    internal fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

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
