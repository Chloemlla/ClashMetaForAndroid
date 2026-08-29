package com.github.kr328.clash.common.constants

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.github.kr328.clash.common.log.Log
import java.security.MessageDigest

/** Observed signing-certificate digests of one package, lowercase hex without separators. */
data class PartnerSignerDigests(val sha256: String, val sha1: String)

/**
 * Why a caller is (not) recognized as a partner. Callers use this instead of a bare boolean so a
 * rejection can be explained to the user rather than surfacing as an unexplained "no status".
 */
enum class PartnerTrust {
    /** Neither a hardcoded applicationId nor declaring the partner meta-data flag. */
    NotPartner,

    /** Declares the partner flag but is not signed with the pinned release certificate. */
    DeclaredUnverified,

    /** Holds a hardcoded applicationId but is not signed with the pinned release certificate. */
    HardcodedUnverified,

    /** Signed with the pinned release certificate. */
    Verified,
}

/**
 * Known partner apps that should be auto-included in VPN access control
 * and allowed to query lightweight Clash running status.
 *
 * ### Registry rule (v4)
 *
 * ```
 * isPartner = (hardcode ∪ meta-data present) ∧ signedWith(trustedSignerSha1)
 * ```
 *
 * The whole suite is signed with one shared release key, so a single pinned fingerprint is the
 * entire trust root: an app that does not present that certificate is not a partner, whatever
 * applicationId it claims and whatever meta-data it declares. Neither the [hardcodePackages]
 * allowlist nor the [META_DATA_PARTNER_KEY] flag can stand in for it — both only decide *which*
 * apps are asked for the certificate.
 *
 * Recognition never widens what a partner can do (still read-only status + VPN access-control
 * auto-include, never start/stop/toggle of the VPN — see F-12 in `SECURITY.md`), it only decides
 * *who* reaches that existing read-only surface.
 */
object PartnerApps {
    /** Application meta-data flag partner apps declare to opt into discovery. */
    const val META_DATA_PARTNER_KEY: String = "com.github.kr328.clash.partner"

    /**
     * The one release signing certificate accepted as a partner identity, as a SHA-1 fingerprint
     * (lowercase hex, no separators).
     *
     * Read it with `keytool -list -v -keystore <keystore>` (`SHA1:`, colons removed) or
     * `apksigner verify --print-certs <apk>` (`Signer #1 certificate SHA-1 digest`). Replace the
     * value — do not append to it — when the shared release key is rotated: a second entry would
     * reintroduce exactly the multi-key trust this gate exists to remove.
     */
    val trustedSignerSha1: Set<String> = setOf(
        // Shared Chloemlla release key (CN=Chloemlla), used by CMFA and every partner app
        "295443559574b12e12a0e49f6c92692ca0dc307a",
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

    /**
     * Statically known partner applicationIds (release + common suffixes). This only nominates a
     * package for the certificate check — see [trustedSignerSha1] for the actual trust root.
     */
    val hardcodePackages: Set<String> =
        piliPlusPackages + nexAiPackages + projectLumenPackages + zhihuPlusPackages +
            auraPackages + cdictPackages

    fun isPiliPlusPackage(packageName: String): Boolean =
        packageName in piliPlusPackages

    /**
     * Hardcode-only membership check (no [Context], no dynamic discovery).
     *
     * This answers "is this a known applicationId", not "is this a partner": it cannot see the
     * certificate. Prefer [isPartnerPackage] with a [Context] anywhere the answer gates access.
     */
    fun isPartnerPackage(packageName: String): Boolean =
        packageName in hardcodePackages

    /**
     * Full partner membership check used by the runtime (StatusProvider / TunService):
     * a recognized applicationId or meta-data flag **plus** the pinned release certificate. See
     * the class KDoc for the exact rule.
     */
    fun isPartnerPackage(context: Context, packageName: String): Boolean =
        trustOf(context, packageName) == PartnerTrust.Verified

    /**
     * Classifies [packageName] against the registry rule described in the class KDoc.
     *
     * A package that is not installed keeps static membership (used for deny-list exclusion) since
     * there is no app to impersonate at runtime. An installed app that borrows a hardcoded partner
     * applicationId without the pinned signer is reported as [PartnerTrust.HardcodedUnverified]
     * rather than silently rejected, so the caller can explain the refusal instead of reporting an
     * unexplained "no status".
     */
    fun trustOf(context: Context, packageName: String): PartnerTrust {
        val pm = context.packageManager
        val hardcoded = packageName in hardcodePackages
        if (!isInstalled(pm, packageName)) {
            return if (hardcoded) PartnerTrust.Verified else PartnerTrust.NotPartner
        }
        val declared = hardcoded || declaresPartnerMetaData(pm, packageName)
        if (!declared) {
            return PartnerTrust.NotPartner
        }
        if (hasPinnedSigner(pm, packageName)) {
            return PartnerTrust.Verified
        }
        return if (hardcoded) PartnerTrust.HardcodedUnverified else PartnerTrust.DeclaredUnverified
    }

    /**
     * Observed signing-certificate digests of [packageName], or null when the package is absent or
     * exposes no certificate. When an APK carries several signers the lexicographically smallest
     * SHA-256 is chosen so the value is stable across queries (grants are keyed by it).
     */
    fun signerDigestsOf(context: Context, packageName: String): PartnerSignerDigests? {
        val canonical = signingCertificatesOf(context.packageManager, packageName)
            .map { it.toByteArray() }
            .minByOrNull { sha256Hex(it) }
            ?: return null
        return PartnerSignerDigests(sha256 = sha256Hex(canonical), sha1 = sha1Hex(canonical))
    }

    /** True when any signing certificate of [packageName] is the pinned release certificate. */
    private fun hasPinnedSigner(pm: PackageManager, packageName: String): Boolean =
        matchesPinnedSignerSha1(
            signingCertificatesOf(pm, packageName).map { sha1Hex(it.toByteArray()) },
        )

    /**
     * Merged registry of partner packages currently installed on the device: every installed
     * hardcode applicationId and every app declaring [META_DATA_PARTNER_KEY], kept only when it
     * presents the pinned release certificate. Safe to call from access-control / status paths —
     * failures during discovery are swallowed and only shrink the result.
     */
    fun installedPartnerPackages(context: Context): Set<String> {
        val pm = context.packageManager
        return mergePartnerPackages(
            installedHardcode = installedHardcodePackages(pm),
            candidateMetaDataPackages = declaredPartnerCandidates(pm),
            isTrustedSigner = { candidate -> hasPinnedSigner(pm, candidate) },
        )
    }

    /** Backward-compatible alias used by older call sites. */
    fun installedPiliPlusPackages(context: Context): Set<String> =
        installedPartnerPackages(context)

    /**
     * Installed apps that *claim* partner status: the installed subset of [hardcodePackages] plus
     * every app declaring [META_DATA_PARTNER_KEY], regardless of how they are signed.
     *
     * Unlike [installedPartnerPackages] this deliberately includes claimants that fail the
     * certificate check, because the partner list UI has to show them — an app presenting the wrong
     * key is exactly what needs to be surfaced.
     */
    fun installedCandidatePackages(context: Context): Set<String> {
        val pm = context.packageManager
        return installedHardcodePackages(pm) + declaredPartnerCandidates(pm)
    }

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
     * Pure certificate-fingerprint membership test, extracted for unit testing without a real
     * [Signature]/PackageManager. Fingerprints are compared case-insensitively against
     * [trustedSignerSha1].
     */
    internal fun matchesPinnedSignerSha1(certificateDigests: Collection<String>): Boolean =
        certificateDigests.any { it.lowercase() in trustedSignerSha1 }

    /** Lowercase hex SHA-256, matching the digest `apksigner verify --print-certs` reports. */
    internal fun sha256Hex(bytes: ByteArray): String = hex("SHA-256", bytes)

    /** Lowercase hex SHA-1, matching the fingerprint `keytool -list -v` reports (colons removed). */
    internal fun sha1Hex(bytes: ByteArray): String = hex("SHA-1", bytes)

    private fun hex(algorithm: String, bytes: ByteArray): String =
        MessageDigest.getInstance(algorithm).digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    /**
     * Pure registry set-math, extracted for unit testing with fakes (no PackageManager
     * or android.content.pm.Signature needed). Mirrors the merge performed by
     * [installedPartnerPackages]: hardcode membership and the meta-data flag only nominate a
     * candidate, and [isTrustedSigner] alone decides whether it stays.
     */
    internal fun mergePartnerPackages(
        installedHardcode: Set<String>,
        candidateMetaDataPackages: Set<String>,
        isTrustedSigner: (candidate: String) -> Boolean,
    ): Set<String> =
        (installedHardcode + candidateMetaDataPackages).filterTo(mutableSetOf(), isTrustedSigner)
}
