package com.github.kr328.clash.design

import android.content.Context
import android.net.Uri
import android.view.View
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.model.OpenSourceDependency
import com.github.kr328.clash.design.preference.category
import com.github.kr328.clash.design.preference.clickable
import com.github.kr328.clash.design.preference.preferenceScreen
import com.github.kr328.clash.design.preference.tips
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root

class OpenSourceNoticeDesign(
    context: Context,
    openLink: (Uri) -> Unit,
) : Design<OpenSourceNoticeDesign.Request>(context) {
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

        val screen = preferenceScreen(context) {
            tips(R.string.open_source_notice_intro)
            tips(R.string.open_source_notice_free_forever)
            tips(R.string.open_source_notice_scam_warning)

            category(R.string.open_source_project_license)

            clickable(
                title = R.string.open_source_project_license_title,
                summary = R.string.open_source_project_license_summary,
            ) {
                clicked {
                    openLink(Uri.parse(context.getString(R.string.open_source_gpl3_url)))
                }
            }

            category(R.string.open_source_repository)

            clickable(
                title = R.string.open_source_repository_fork_title,
                summary = R.string.open_source_repository_fork_url,
            ) {
                clicked {
                    openLink(Uri.parse(context.getString(R.string.open_source_repository_fork_url)))
                }
            }

            clickable(
                title = R.string.open_source_repository_upstream_title,
                summary = R.string.meta_github_url,
            ) {
                clicked {
                    openLink(Uri.parse(context.getString(R.string.meta_github_url)))
                }
            }

            category(R.string.open_source_third_party)

            OpenSourceDependency.defaults().forEach { dependency ->
                val summary = buildString {
                    append(context.getString(R.string.open_source_dependency_author, dependency.author))
                    append('\n')
                    append(context.getString(dependency.descriptionRes))
                    append('\n')
                    append(context.getString(R.string.open_source_dependency_license, dependency.license))
                }

                clickable(title = R.string.application_name) {
                    title = dependency.name
                    this.summary = summary

                    val url = dependency.url
                    if (url != null) {
                        clicked {
                            openLink(Uri.parse(url))
                        }
                    }
                }
            }

            category(R.string.open_source_notice_action)

            clickable(
                title = R.string.open_source_notice_continue,
                summary = R.string.open_source_notice_continue_summary,
            ) {
                clicked {
                    requests.trySend(Request.Accept)
                }
            }
        }

        binding.content.addView(screen.root)
    }
}
