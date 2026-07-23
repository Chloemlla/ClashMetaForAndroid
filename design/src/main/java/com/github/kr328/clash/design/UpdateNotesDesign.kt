package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.model.UpdateNotesDocument
import com.github.kr328.clash.design.preference.category
import com.github.kr328.clash.design.preference.clickable
import com.github.kr328.clash.design.preference.preferenceScreen
import com.github.kr328.clash.design.preference.tips
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root

/**
 * Immersive "what's new" tutorial shown once per build identity (commit + version).
 * Trellis: .trellis/spec/android/update-notes.md
 */
class UpdateNotesDesign(
    context: Context,
    document: UpdateNotesDocument,
) : Design<UpdateNotesDesign.Request>(context) {
    enum class Request {
        Accept,
    }

    private val binding = DesignSettingsCommonBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    init {
        binding.surface = surface
        binding.activityBarLayout.applyFrom(context)
        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)

        val identity = document.identity
        val identitySummary = context.getString(
            R.string.update_notes_identity_summary,
            identity.versionName,
            identity.commitHash,
            identity.buildTime,
        )

        val screen = preferenceScreen(context) {
            tips(R.string.update_notes_intro)
            tips(R.string.update_notes_immersive_hint)

            category(R.string.update_notes_build_identity)
            clickable(
                title = R.string.update_notes_build_identity_title,
                summary = R.string.update_notes_build_identity_title,
            ) {
                title = context.getString(R.string.update_notes_build_identity_title)
                summary = identitySummary
            }

            if (document.highlights.isNotEmpty()) {
                category(R.string.update_notes_highlights)
                document.highlights.forEach { item ->
                    clickable(title = R.string.application_name) {
                        title = item.title
                        summary = item.body
                    }
                }
            }

            if (document.modules.isNotEmpty()) {
                category(R.string.update_notes_modules)
                tips(R.string.update_notes_modules_intro)
                document.modules.forEach { note ->
                    clickable(title = R.string.application_name) {
                        title = note.module
                        summary = note.summary
                    }
                }
            }

            if (document.commits.isNotEmpty()) {
                category(R.string.update_notes_commits)
                tips(R.string.update_notes_commits_intro)
                document.commits.take(25).forEach { entry ->
                    val label = if (entry.hash.isNotBlank()) {
                        "[${entry.type}] ${entry.hash} · ${entry.subject}"
                    } else {
                        "[${entry.type}] ${entry.subject}"
                    }
                    clickable(title = R.string.application_name) {
                        title = entry.hash.ifBlank { entry.type }
                        summary = label
                    }
                }
            }

            category(R.string.update_notes_action)
            clickable(
                title = R.string.update_notes_continue,
                summary = R.string.update_notes_continue_summary,
            ) {
                clicked {
                    requests.trySend(Request.Accept)
                }
            }
        }

        binding.content.addView(screen.root)
    }
}
