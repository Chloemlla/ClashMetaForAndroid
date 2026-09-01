package com.github.kr328.clash.common.constants

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.Bundle
import com.github.kr328.clash.common.log.Log
import java.security.MessageDigest

/** Observed signing-certificate digests of one package, lowercase hex without separators. */
data class PartnerSignerDigests(val sha256: String, val sha1: String)

/**
 * Why a caller is (not) recognized as a partner. Only [Verified] grants anything; the two
 * `*Unverified` values exist so a rejection can be explained to the user rather than surfacing as
 * an unexplained "no status".
 */
enum class PartnerTrust {
    /** Not signed with the pinned release certificate, and makes no partner claim either. */
    NotPartner,

    /** Declares the partner meta-data flag but is not signed with the pinned certificate. */
    DeclaredUnverified,

    /** Holds a hardcoded applicationId but is not signed with the pinned certificate. */
    HardcodedUnverified,

    /** Signed with the pinned release certificate. */
    Verified,
}

/**
 * Partner apps that are auto-included in VPN access control and allowed to query lightweight
 * Clash running status.
 *
 * ### Registry rule
 *
 * ```
 * isPartner = signedWith(trustedSignerSha256, fallback trustedSignerSha1) && packageName in hardcodePackages
 * ```
 *
 * The whole suite is signed with one shared release key. A partner is an installed package from a
 * known partner family ([hardcodePackages]) whose signing certificate is the pinned one; no app
 * without it is a partner whatever applicationId it claims and whatever meta-data it declares.
 * [hardcodePackages] and [META_DATA_PARTNER_KEY] survive only to label *claimants* in the partner
 * list — neither grants access.
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
     *
     * Kept as the *transitional fallback* until [trustedSignerSha256] is populated (B-132):
     * SHA-1 is collision-weak, so the trust comparison should move to SHA-256.
     */
    val trustedSignerSha1: Set<String> = setOf(
        // Shared Chloemlla release key (CN=Chloemlla), used by CMFA and every partner app
        "295443559574b12e12a0e49f6c92692ca0dc307a",
    )

    /**
     * The pinned release certificate as a SHA-256 digest (lowercase hex, no separators).
     *
     * TODO(B-132): populate with the real SHA-256 digest of the shared release certificate
     * (CN=Chloemlla), e.g. from `apksigner verify --print-certs` (`Signer #1 certificate SHA-256
     * digest`). The value is not available in this repository offline and MUST NOT be invented;
     * until it is filled, [hasPinnedSigner] falls back to [trustedSignerSha1]. When populated,
     * the SHA-1 set can be dropped.
     */
    val trustedSignerSha256: Set<String> = emptySet()

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
     * Applications the partner list labels as *claiming* partner status, so a wrongly signed app is
     * shown and explained instead of silently missing. Claiming grants nothing — see the class KDoc.
     */
    val hardcodePackages: Set<String> =
        piliPlusPackages + nexAiPackages + projectLumenPackages + zhihuPlusPackages +
            auraPackages + cdictPackages

    fun isPiliPlusPackage(packageName: String): Boolean =
        packageName in piliPlusPackages

    /**
     * Classifies [packageName] against the registry rule described in the class KDoc: the pinned
     * certificate alone decides [PartnerTrust.Verified].
     *
     * An installed app that presents a different key is reported as
     * [PartnerTrust.HardcodedUnverified] or [PartnerTrust.DeclaredUnverified] when it claims partner
     * status, so the caller can explain the refusal instead of reporting an unexplained "no status".
     */
    fun trustOf(context: Context, packageName: String): PartnerTrust {
        val pm = context.packageManager
        if (!isInstalled(pm, packageName)) {
            return PartnerTrust.NotPartner
        }
        if (hasPinnedSigner(pm, packageName)) {
            return PartnerTrust.Verified
        }
        return when {
            packageName in hardcodePackages -> PartnerTrust.HardcodedUnverified
            declaresPartnerMetaData(pm, packageName) -> PartnerTrust.DeclaredUnverified
            else -> PartnerTrust.NotPartner
        }
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
    private fun hasPinnedSigner(pm: PackageManager, packageName: String): Boolean {
        val certificates = signingCertificatesOf(pm, packageName)
        // B-132: prefer the collision-resistant SHA-256 pin. SHA-1 remains only as a
        // transitional fallback until the real SHA-256 digest is recorded in trustedSignerSha256.
        return matchesPinnedSignerSha256(certificates.map { sha256Hex(it.toByteArray()) }) ||
            matchesPinnedSignerSha1(certificates.map { sha1Hex(it.toByteArray()) })
    }

    /**
     * Installed partner packages: the known partner families ([hardcodePackages]) that are
     * installed AND signed with the pinned release certificate.
     *
     * Looked up per candidate package rather than with a full `getInstalledPackages` scan
     * (B-133): the candidate set is a small constant, a full enumeration is slow on the main
     * thread, and one binder failure used to empty the whole result. Partner apps must therefore
     * be registered in [hardcodePackages] to be discovered.
     */
    fun installedPartnerPackages(context: Context): Set<String> {
        val pm = context.packageManager
        val signerSha1ByPackage = hardcodePackages
            .filter { isInstalled(pm, it) }
            .associateWith { packageName ->
                signingCertificatesOf(pm, packageName).map { sha1Hex(it.toByteArray()) }
            }
        return partnerPackagesFrom(signerSha1ByPackage)
    }

    /**
     * Installed apps the partner list has to show: the installed partner families signed with the
     * pinned certificate, plus the claimants ([hardcodePackages] and [META_DATA_PARTNER_KEY]
     * declarers) that fail the certificate check — an app presenting the wrong key is exactly what
     * needs to be surfaced.
     *
     * [installedPartners] is the certificate-verified set ([installedPartnerPackages]); callers that
     * already computed it (e.g. a VPN tunnel build) pass it in so the per-package signing lookups
     * are not repeated (B-182).
     */
    fun installedCandidatePackages(
        context: Context,
        installedPartners: Set<String> = installedPartnerPackages(context),
    ): Set<String> {
        val pm = context.packageManager
        return installedPartners +
            installedHardcodePackages(pm) +
            declaredPartnerCandidates(pm)
    }

    private fun installedHardcodePackages(pm: PackageManager): Set<String> =
        hardcodePackages.filterTo(mutableSetOf()) { isInstalled(pm, it) }

    private fun isInstalled(pm: PackageManager, packageName: String): Boolean {
        return try {
            pm.getApplicationInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (t: RuntimeException) {
            // B-133: a transient binder failure must not crash the caller; treat the package as
            // not-installed for this query and let the caller surface a retry.
            Log.w("PartnerApps: failed to query package $packageName", t)
            false
        }
    }

    /** Application packages (any package, not just hardcode) that declare the partner flag. */
    private fun declaredPartnerCandidates(pm: PackageManager): Set<String> {
        return try {
            @Suppress("DEPRECATION")
            val apps: List<ApplicationInfo> = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            apps.asSequence()
                .filter { isTruthyPartnerMetaDataValue(partnerMetaDataValue(it.metaData)) }
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
            isTruthyPartnerMetaDataValue(partnerMetaDataValue(info.metaData))
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Reads the partner flag from [metaData] as a raw [Any?] — the value may legitimately be a
     * boolean, an int, or a string depending on how the app declared it, so the deprecated
     * untyped [android.os.Bundle.get] is exactly the accessor needed here.
     */
    @Suppress("DEPRECATION")
    private fun partnerMetaDataValue(metaData: Bundle?): Any? =
        metaData?.get(META_DATA_PARTNER_KEY)

    @Suppress("DEPRECATION")
    private fun signingCertificatesOf(pm: PackageManager, packageName: String): Set<Signature> {
        return try {
            signaturesOf(pm.getPackageInfo(packageName, signingCertificatesFlag()))
        } catch (_: PackageManager.NameNotFoundException) {
            // Package not installed: an ordinary absence, not a failure.
            emptySet()
        } catch (t: RuntimeException) {
            // B-133: a transient binder failure (e.g. TransactionTooLargeException) or a security
            // exception must not crash the caller nor be conflated with "package absent". Log it
            // and treat this one package as having no known certificate; the rest of the query
            // is unaffected.
            Log.w("PartnerApps: failed to read signing certificates for $packageName", t)
            emptySet()
        }
    }

    @Suppress("DEPRECATION")
    private fun signingCertificatesFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

    @Suppress("DEPRECATION")
    private fun signaturesOf(info: PackageInfo): Set<Signature> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return info.signatures?.toSet() ?: emptySet()
        }
        val signingInfo = info.signingInfo ?: return emptySet()
        val history = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory ?: signingInfo.apkContentsSigners
        }
        return history?.toSet() ?: emptySet()
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

    /**
     * Pure certificate-fingerprint membership test against [trustedSignerSha256]. Empty until the
     * real SHA-256 digest is recorded (B-132), so it currently never matches.
     */
    internal fun matchesPinnedSignerSha256(certificateDigests: Collection<String>): Boolean =
        certificateDigests.any { it.lowercase() in trustedSignerSha256 }

    /** Lowercase hex SHA-256, matching the digest `apksigner verify --print-certs` reports. */
    internal fun sha256Hex(bytes: ByteArray): String = hex("SHA-256", bytes)

    /** Lowercase hex SHA-1, matching the fingerprint `keytool -list -v` reports (colons removed). */
    internal fun sha1Hex(bytes: ByteArray): String = hex("SHA-1", bytes)

    private fun hex(algorithm: String, bytes: ByteArray): String =
        MessageDigest.getInstance(algorithm).digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    /**
     * Pure registry rule, extracted for unit testing with fakes (no PackageManager or
     * android.content.pm.Signature needed): of the candidate packages, keyed to the SHA-1
     * fingerprints of their signing certificates, the partners are exactly those presenting the
     * pinned (SHA-1 fallback) one — applicationId and meta-data play no part. Callers are
     * responsible for feeding only the known candidate families (see [installedPartnerPackages]).
     */
    internal fun partnerPackagesFrom(
        signerSha1ByPackage: Map<String, Collection<String>>,
    ): Set<String> = signerSha1ByPackage.filterValues(::matchesPinnedSignerSha1).keys
}
