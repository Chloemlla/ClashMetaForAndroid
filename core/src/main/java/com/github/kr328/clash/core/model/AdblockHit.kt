package com.github.kr328.clash.core.model

import android.os.Parcel
import android.os.Parcelable
import com.github.kr328.clash.core.util.Parcelizer
import kotlinx.serialization.Serializable

@Serializable
data class AdblockHit(
    val time: Long = 0,
    val network: String = "",
    val domain: String = "",
    val ruleType: String = "",
    val payload: String = "",
    val source: String = "",
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<AdblockHit> {
            override fun createFromParcel(parcel: Parcel) =
                Parcelizer.decodeFromParcel(serializer(), parcel)

            override fun newArray(size: Int): Array<AdblockHit?> = arrayOfNulls(size)
        }
    }
}
