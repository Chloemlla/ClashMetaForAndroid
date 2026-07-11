import java.io.File
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    kotlin("android")
    kotlin("kapt")
    id("com.android.application")
}

dependencies {
    compileOnly(project(":hideapi"))

    implementation(project(":core"))
    implementation(project(":service"))
    implementation(project(":design"))
    implementation(project(":common"))

    implementation(libs.kotlin.coroutine)
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.coordinator)
    implementation(libs.androidx.recyclerview)
    implementation(libs.google.material)
    implementation(libs.quickie.bundled)
    implementation(libs.androidx.activity.ktx)

    testImplementation(libs.test.junit)
}

tasks.getByName("clean", type = Delete::class) {
    delete(file("release"))
}

val geoFilesDownloadDir = "src/main/assets"
val geoReleaseRevision = "760137adc8c15c82f7ce4ed40a178c4548e87fd2"

data class GeoAsset(
    val sourceName: String,
    val outputName: String,
    val sha256: String,
)

val geoAssets = listOf(
    GeoAsset(
        "geoip.metadb",
        "geoip.metadb",
        "6755f9d96b1648dca9b4dc6faa7ba07bce701450560bfb0b8a0312fb39eb6722",
    ),
    GeoAsset(
        "geosite.dat",
        "geosite.dat",
        "2bc7500767053200de5b960cca168f2eb20a3359afd4afdb3dc93b3db514acd4",
    ),
    GeoAsset(
        "GeoLite2-ASN.mmdb",
        "ASN.mmdb",
        "08ee4281c0a53f4ea84adf556a183a9deb72c7721c8f0f10cb2662171c082ae1",
    ),
    GeoAsset(
        "BundleMRS.7z",
        "BundleMRS.7z",
        "a3e2cf34509805408b0fa3280b13bc17e3bbaf11e0463b69b488a1a6af9c3450",
    ),
)

fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

val downloadGeoFiles by tasks.registering {
    val outputFiles = geoAssets.map { file("$geoFilesDownloadDir/${it.outputName}") }
    outputs.files(outputFiles)
    outputs.upToDateWhen {
        geoAssets.all { asset ->
            file("$geoFilesDownloadDir/${asset.outputName}")
                .takeIf { it.isFile }
                ?.sha256() == asset.sha256
        }
    }

    doLast {
        geoAssets.forEach { asset ->
            val outputPath = file("$geoFilesDownloadDir/${asset.outputName}")
            if (outputPath.isFile && outputPath.sha256() == asset.sha256) {
                logger.lifecycle("Using verified Geo asset ${asset.outputName}")
                return@forEach
            }

            outputPath.parentFile.mkdirs()
            val temporaryPath = File(outputPath.parentFile, ".${asset.outputName}.part")
            val downloadUrl = URL(
                "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/" +
                    "$geoReleaseRevision/${asset.sourceName}"
            )

            try {
                val connection = downloadUrl.openConnection().apply {
                    connectTimeout = 30_000
                    readTimeout = 120_000
                }
                connection.getInputStream().use { input ->
                    Files.copy(
                        input,
                        temporaryPath.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }

                val actualSha256 = temporaryPath.sha256()
                check(actualSha256 == asset.sha256) {
                    "SHA-256 mismatch for ${asset.outputName}: " +
                        "expected ${asset.sha256}, got $actualSha256"
                }

                try {
                    Files.move(
                        temporaryPath.toPath(),
                        outputPath.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(
                        temporaryPath.toPath(),
                        outputPath.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                logger.lifecycle("Downloaded and verified Geo asset ${asset.outputName}")
            } finally {
                temporaryPath.delete()
            }
        }
    }
}

tasks.configureEach {
    if (name.startsWith("assemble") || name.startsWith("bundle")) {
        dependsOn(downloadGeoFiles)
    }
}

tasks.getByName("clean", type = Delete::class) {
    delete(file(geoFilesDownloadDir))
}
