package com.github.kr328.clash.service.document

import java.util.*

object Paths {
    const val CONFIGURATION_ID = "config.yaml"
    const val PROVIDERS_ID = "providers"

    fun resolve(path: String): Path {
        val segments = path.split("/").filter { it.isNotBlank() }

        // Reject rather than filter: quietly dropping a traversal segment rewrites the caller's
        // input into a *different* valid path and hands that back as if it were what was asked for.
        // An unknown scope below already throws, so this is also what the rest of this function does.
        // Blank segments stay filtered — the root documentId is literally "/".
        require(segments.none { it == "." || it == ".." }) {
            "invalid path $path"
        }

        return when (segments.size) {
            0 -> Path(
                uuid = null,
                scope = null,
                relative = null,
            )
            1 -> Path(
                uuid = UUID.fromString(segments[0]),
                scope = null,
                relative = null,
            )
            2 -> Path(
                uuid = UUID.fromString(segments[0]),
                scope = when (segments[1]) {
                    CONFIGURATION_ID -> Path.Scope.Configuration
                    PROVIDERS_ID -> Path.Scope.Providers
                    else -> throw IllegalArgumentException("unknown scope ${segments[1]}")
                },
                relative = null,
            )
            else -> Path(
                uuid = UUID.fromString(segments[0]),
                scope = when (segments[1]) {
                    CONFIGURATION_ID -> Path.Scope.Configuration
                    PROVIDERS_ID -> Path.Scope.Providers
                    else -> throw IllegalArgumentException("unknown scope ${segments[1]}")
                },
                relative = segments.drop(2),
            )
        }
    }
}