package com.github.kr328.clash.util

import com.github.kr328.clash.design.model.Profile
import com.github.kr328.clash.service.model.Profile as ServiceProfile

fun ServiceProfile.toDesignProfile(): Profile = Profile(
    uuid = uuid,
    name = name,
    type = type.toDesignType(),
    source = source,
    active = active,
    interval = interval,
    upload = upload,
    download = download,
    total = total,
    expire = expire,
    updatedAt = updatedAt,
    imported = imported,
    pending = pending,
    ageSecretKey = ageSecretKey,
)

private fun ServiceProfile.Type.toDesignType(): Profile.Type = when (this) {
    ServiceProfile.Type.File -> Profile.Type.File
    ServiceProfile.Type.Url -> Profile.Type.Url
    ServiceProfile.Type.External -> Profile.Type.External
}
