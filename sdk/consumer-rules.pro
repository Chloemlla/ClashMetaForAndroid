# Runtime SDK facade — keep public entrypoints and Binder-facing models.
-keep class com.github.kr328.clash.sdk.** { *; }

-keep class com.github.kr328.clash.service.remote.** { *; }
-keep class com.github.kr328.clash.service.model.** { *; }
-keep class com.github.kr328.clash.core.model.** { *; }
-keep class com.github.kr328.clash.core.Clash { *; }

-keep class kotlinx.coroutines.CompletableDeferred {
    *;
}
-keep class kotlin.Unit {
    *;
}
-keepattributes *Annotation*, InnerClasses