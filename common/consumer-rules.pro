# B-70: common ships no reflection / serialization / JNI entry points.
# It hosts pure-Kotlin helpers (Store delegates, Parcelable slice utilities,
# constants). Parcelable CREATOR fields are covered by R8's built-in rule for
# android.os.Parcelable implementors, so there is nothing to keep here.
