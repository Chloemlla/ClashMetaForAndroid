package com.github.kr328.clash

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.github.kr328.clash.design.R
import com.github.kr328.clash.util.AuditReportImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AuditReportActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            status.text = getString(R.string.audit_importing)
            runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    withContext(Dispatchers.IO) { AuditReportImporter.import(this@AuditReportActivity, input) }
                } ?: error("Unable to open report")
            }.onSuccess { summary ->
                status.text = getString(R.string.audit_report_summary, summary.packageName, summary.evidenceFiles.size, summary.limitations.size)
            }.onFailure { status.text = getString(R.string.audit_import_failed, it.message ?: "invalid report") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 64, 32, 32) }
        root.addView(TextView(this).apply { text = getString(R.string.audit_title); textSize = 20f })
        status = TextView(this).apply { text = getString(R.string.audit_select_report); setPadding(0, 24, 0, 24) }
        root.addView(status)
        root.addView(TextView(this).apply { text = getString(R.string.audit_import_action); setOnClickListener { picker.launch(arrayOf("application/zip", "application/jsonl", "application/json", "text/plain")) } })
        setContentView(root)
    }
}
