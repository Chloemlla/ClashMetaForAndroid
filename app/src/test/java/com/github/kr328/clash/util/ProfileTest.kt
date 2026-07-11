package com.github.kr328.clash.util

import com.github.kr328.clash.design.model.Profile
import com.github.kr328.clash.service.model.Profile as ServiceProfile
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class ProfileTest {
    @Test
    fun toDesignProfile_preservesPresentationFieldsAndType() {
        val uuid = UUID.randomUUID()
        val source = ServiceProfile(
            uuid = uuid,
            name = "example",
            type = ServiceProfile.Type.External,
            source = "content://profile",
            active = true,
            interval = 900_000,
            upload = 1,
            download = 2,
            total = 3,
            expire = 4,
            updatedAt = 5,
            imported = true,
            pending = false,
            ageSecretKey = "AGE-SECRET-KEY-TEST",
        )

        val result = source.toDesignProfile()

        assertEquals(uuid, result.uuid)
        assertEquals(Profile.Type.External, result.type)
        assertEquals(source.name, result.name)
        assertEquals(source.source, result.source)
        assertEquals(source.ageSecretKey, result.ageSecretKey)
        assertEquals(source.download, result.download)
    }
}
