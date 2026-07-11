package com.github.kr328.clash.design.model

import java.util.UUID

data class Profile(
    val uuid: UUID,
    val name: String,
    val type: Type,
    val source: String,
    val active: Boolean,
    val interval: Long,
    val upload: Long,
    val download: Long,
    val total: Long,
    val expire: Long,
    val updatedAt: Long,
    val imported: Boolean,
    val pending: Boolean,
    val ageSecretKey: String? = null,
) {
    enum class Type {
        File,
        Url,
        External,
    }
}
