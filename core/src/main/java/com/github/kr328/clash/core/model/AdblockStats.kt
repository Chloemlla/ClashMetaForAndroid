package com.github.kr328.clash.core.model

import android.os.Parcel
import android.os.Parcelable
import com.github.kr328.clash.core.util.Parcelizer
import kotlinx.serialization.Serializable

@Serializable
data class AdblockStats(
    val total: Long = 0,
    val blocked: Long = 0,
    val topDomains: List<AdblockTopDomain> = emptyList(),
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<AdblockStats> {
            override fun createFromParcel(parcel: Parcel) =
                Parcelizer.decodeFromParcel(serializer(), parcel)

            override fun newArray(size: Int): Array<AdblockStats?> = arrayOfNulls(size)
        }
    }
}

@Serializable
data class AdblockTopDomain(
    val domain: String = "",
    val count: Long = 0,
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<AdblockTopDomain> {
            override fun createFromParcel(parcel: Parcel) =
                Parcelizer.decodeFromParcel(serializer(), parcel)

            override fun newArray(size: Int): Array<AdblockTopDomain?> = arrayOfNulls(size)
        }
    }
}
