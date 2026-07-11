# kaidl compiler patch

This included build repackages kaidl 1.15 with a source-level compatibility fix for Android 13's typed `Parcel.readSerializable` API.

Only `ParcelKt.class` is replaced. The remaining compiler classes and the runtime stay on kaidl 1.15 so existing Binder wire formats and the repository's Kotlin/KSP toolchain remain unchanged.
