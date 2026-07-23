package com.github.kr328.clash.core.model

import android.os.Parcel
import android.os.Parcelable
import com.github.kr328.clash.core.util.Parcelizer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Connection(
    val id: String,
    val network: String,
    val type: String,
    val host: String,
    @SerialName("dstIp") val dstIp: String,
    @SerialName("dstPort") val dstPort: String,
    @SerialName("srcIp") val srcIp: String,
    @SerialName("srcPort") val srcPort: String,
    val inbound: String = "",
    val rule: String = "",
    val rulePayload: String = "",
    val chains: String = "",
    val upload: Long = 0,
    val download: Long = 0,
    /** Unix timestamp millis when this connection started. */
    val start: Long = 0,
    val process: String = "",
    @SerialName("package") val packageName: String = "",
    val uid: Int = 0,
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<Connection> {
            override fun createFromParcel(parcel: Parcel) =
                Parcelizer.decodeFromParcel(serializer(), parcel)

            override fun newArray(size: Int): Array<Connection?> = arrayOfNulls(size)
        }
    }
}

@Serializable
data class ConnectionSnapshot(
    val connections: List<Connection>,
    /** Unix timestamp millis when the snapshot was taken. */
    val time: Long,
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<ConnectionSnapshot> {
            override fun createFromParcel(parcel: Parcel) =
                Parcelizer.decodeFromParcel(serializer(), parcel)

            override fun newArray(size: Int): Array<ConnectionSnapshot?> = arrayOfNulls(size)
        }
    }
}