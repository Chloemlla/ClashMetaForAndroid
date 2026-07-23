package com.github.kr328.clash.design.model

import android.content.Context
import com.github.kr328.clash.design.R
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * Immersive post-update notes (Trellis: android/update-notes).
 * Loaded from res/raw/update_notes.json and overlaid with BuildConfig identity.
 */
data class UpdateNotesDocument(
    val schemaVersion: Int,
    val identity: UpdateBuildIdentity,
    val highlights: List<UpdateHighlight>,
    val commits: List<UpdateCommitEntry>,
    val modules: List<UpdateModuleNote>,
) {
    companion object {
        fun loadFromRaw(
            context: Context,
            runtimeIdentity: UpdateBuildIdentity,
        ): UpdateNotesDocument {
            val raw = runCatching {
                context.resources.openRawResource(R.raw.update_notes)
                    .bufferedReader(StandardCharsets.UTF_8)
                    .use { it.readText() }
            }.getOrNull()

            if (raw.isNullOrBlank()) {
                return fallback(runtimeIdentity)
            }

            return runCatching { parse(raw, runtimeIdentity) }.getOrElse { fallback(runtimeIdentity) }
        }

        private fun parse(raw: String, runtimeIdentity: UpdateBuildIdentity): UpdateNotesDocument {
            val root = JSONObject(raw)
            val identityJson = root.optJSONObject("identity")
            val assetBuildTime = identityJson?.optString("buildTime").orEmpty()

            return UpdateNotesDocument(
                schemaVersion = root.optInt("schemaVersion", 1),
                identity = runtimeIdentity.copy(
                    buildTime = assetBuildTime.ifBlank { runtimeIdentity.buildTime },
                ),
                highlights = root.optJSONArray("highlights").toHighlightList(),
                commits = root.optJSONArray("commits").toCommitList(),
                modules = root.optJSONArray("modules").toModuleList(),
            )
        }

        private fun fallback(identity: UpdateBuildIdentity): UpdateNotesDocument {
            return UpdateNotesDocument(
                schemaVersion = 1,
                identity = identity,
                highlights = listOf(
                    UpdateHighlight(
                        id = "build",
                        title = "Build ${identity.versionName}",
                        body = "commit ${identity.commitHash} · ${identity.buildTime}",
                    )
                ),
                commits = emptyList(),
                modules = emptyList(),
            )
        }

        private fun JSONArray?.toHighlightList(): List<UpdateHighlight> {
            if (this == null) return emptyList()
            return buildList {
                for (i in 0 until length()) {
                    val o = optJSONObject(i) ?: continue
                    val title = o.optString("title").orEmpty()
                    if (title.isBlank()) continue
                    add(
                        UpdateHighlight(
                            id = o.optString("id").orEmpty().ifBlank { "h$i" },
                            title = title,
                            body = o.optString("body").orEmpty(),
                        )
                    )
                }
            }
        }

        private fun JSONArray?.toCommitList(): List<UpdateCommitEntry> {
            if (this == null) return emptyList()
            return buildList {
                for (i in 0 until length()) {
                    val o = optJSONObject(i) ?: continue
                    val subject = o.optString("subject").orEmpty()
                    if (subject.isBlank()) continue
                    add(
                        UpdateCommitEntry(
                            hash = o.optString("hash").orEmpty(),
                            subject = subject,
                            type = o.optString("type").orEmpty().ifBlank { "chore" },
                        )
                    )
                }
            }
        }

        private fun JSONArray?.toModuleList(): List<UpdateModuleNote> {
            if (this == null) return emptyList()
            return buildList {
                for (i in 0 until length()) {
                    val o = optJSONObject(i) ?: continue
                    val module = o.optString("module").orEmpty()
                    if (module.isBlank()) continue
                    add(
                        UpdateModuleNote(
                            module = module,
                            summary = o.optString("summary").orEmpty(),
                        )
                    )
                }
            }
        }
    }
}

data class UpdateBuildIdentity(
    val commitHash: String,
    val buildTime: String,
    val versionName: String,
    val versionCode: Int,
) {
    fun gateKey(): String = "$versionCode|$commitHash"
}

data class UpdateHighlight(
    val id: String,
    val title: String,
    val body: String,
)

data class UpdateCommitEntry(
    val hash: String,
    val subject: String,
    val type: String,
)

data class UpdateModuleNote(
    val module: String,
    val summary: String,
)
