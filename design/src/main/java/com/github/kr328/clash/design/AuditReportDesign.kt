package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class AuditReportDesign(
    context: Context,
    vpnAuthorized: Boolean,
) : Design<AuditReportDesign.Request>(context) {
    enum class Request {
        AuthorizeVpn,
        OpenVpnSettings,
        ImportReport,
    }

    private val authorization = TextView(context)
    private val reportStatus = TextView(context)
    override val root: View = ScrollView(context).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
            addView(TextView(context).apply {
                text = context.getString(R.string.audit_title)
                textSize = 20f
            })
            addView(TextView(context).apply {
                text = context.getString(R.string.audit_authorized_scope)
                setPadding(0, 16, 0, 8)
            })
            addView(authorization.apply {
                setPadding(0, 8, 0, 8)
            })
            addView(Button(context).apply {
                text = context.getString(R.string.audit_authorize_vpn)
                setOnClickListener { requests.trySend(Request.AuthorizeVpn) }
            })
            addView(Button(context).apply {
                text = context.getString(R.string.audit_open_vpn_settings)
                setOnClickListener { requests.trySend(Request.OpenVpnSettings) }
            })
            addView(TextView(context).apply {
                text = context.getString(R.string.audit_tool_guidance)
                setPadding(0, 16, 0, 16)
            })
            addView(reportStatus.apply {
                text = context.getString(R.string.audit_select_report)
                setPadding(0, 24, 0, 24)
            })
            addView(Button(context).apply {
                text = context.getString(R.string.audit_import_action)
                setOnClickListener { requests.trySend(Request.ImportReport) }
            })
        })
    }

    init {
        updateAuthorization(vpnAuthorized)
    }

    fun updateAuthorization(authorized: Boolean, settingsUnavailable: Boolean = false) {
        authorization.text = context.getString(
            when {
                settingsUnavailable -> R.string.audit_vpn_settings_unavailable
                authorized -> R.string.audit_vpn_authorized
                else -> R.string.audit_vpn_not_authorized
            },
        )
    }

    fun updateReport(message: CharSequence) {
        reportStatus.text = message
    }
}
