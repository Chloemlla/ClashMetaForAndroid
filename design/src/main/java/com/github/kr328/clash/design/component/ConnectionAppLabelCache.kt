package com.github.kr328.clash.design.component

import android.content.Context
import com.github.kr328.clash.core.model.Connection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Best-effort package label cache; failures fall back to process/package/uid in aggregation. */
class ConnectionAppLabelCache(context: Context) {
    private val packageManager = context.applicationContext.packageManager
    private val resolvedPackages = mutableSetOf<String>()
    private val labels = mutableMapOf<String, String>()

    suspend fun resolve(connections: List<Connection>): Map<String, String> {
        val missing = connections.asSequence()
            .map { it.packageName }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot(resolvedPackages::contains)
            .toSet()

        if (missing.isNotEmpty()) {
            val discovered = withContext(Dispatchers.IO) {
                missing.mapNotNull { packageName ->
                    runCatching {
                        packageManager.getApplicationInfo(packageName, 0)
                            .loadLabel(packageManager)
                            .toString()
                            .takeIf { it.isNotBlank() }
                            ?.let { packageName to it }
                    }.getOrNull()
                }.toMap()
            }
            resolvedPackages += missing
            labels += discovered
        }

        return labels.toMap()
    }
}
