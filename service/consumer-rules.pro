# --- B-70: Room persistence -------------------------------------------------
# Room generates Database_Impl and entity accessors; keep the database subclass and
# the @Entity models so runtime queries cannot hit missing fields/constructors.
-keep class com.github.kr328.clash.service.data.Database { *; }
-keep @androidx.room.Entity class com.github.kr328.clash.service.data.** { *; }

# --- B-70: kotlinx.serialization models ------------------------------------
# service models (Profile, Scene, Pending, Imported, CaptureStore) are serialized
# to JSON and parceled to :app / :sdk embedders. Keep generated serializers.
-keep,includedescriptorclasses class com.github.kr328.clash.service.**$$serializer { *; }
-keepclassmembers class com.github.kr328.clash.service.** {
    *** Companion;
}
-keepclasseswithmembers class com.github.kr328.clash.service.** {
    kotlinx.serialization.KSerializer serializer(...);
}
