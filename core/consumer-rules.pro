-keep class kotlinx.coroutines.CompletableDeferred {
    *;
}

-keep class kotlin.Unit {
    *;
}

-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt # core serialization annotations

# kotlinx-serialization-json specific. Add this if you have java.lang.NoClassDefFoundError kotlinx.serialization.json.JsonObjectSerializer
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- B-70: kotlinx.serialization models -------------------------------------
# core models are serialized/deserialized as JSON across the native bridge and
# (via :sdk) to embedders; R8 must keep the generated $$serializer classes and
# their serializer() entry points even though the host app keeps -dontobfuscate.
-keep,includedescriptorclasses class com.github.kr328.clash.core.**$$serializer { *; }
-keepclassmembers class com.github.kr328.clash.core.** {
    *** Companion;
}
-keepclasseswithmembers class com.github.kr328.clash.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- B-70: JNI bridge -------------------------------------------------------
# The Go native side calls into these Kotlin entry points by mangled JNI name and
# receives the @Keep callback interfaces back across the boundary. Keep the whole
# bridge package so shrink cannot drop a native callback receiver or rename an
# external fun (protects against future obfuscation, not just shrinking).
-keep class com.github.kr328.clash.core.bridge.** { *; }
