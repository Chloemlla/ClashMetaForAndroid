package com.github.kr328.clash.core.model

import android.os.Parcel
import android.os.Parcelable
import com.github.kr328.clash.core.util.Parcelizer
import kotlinx.serialization.Serializable

@Serializable
data class DashboardSummary(
    val mode: TunnelState.Mode = TunnelState.Mode.Rule,
    val hasProviders: Boolean = false,
    val selectedNow: String = "",
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<DashboardSummary> {
        override fun createFromParcel(parcel: Parcel): DashboardSummary {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<DashboardSummary?> = arrayOfNulls(size)
    }
}
