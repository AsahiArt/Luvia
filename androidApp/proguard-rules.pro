# JNA: UniFFI Kotlin (Gobley) loads libluvia_transport via
# Native.load(cdylibName, UniffiLib::class.java). JNA reflects on
# Library / Callback / Structure subtypes to bind native symbols.
# Source: uniffi-rs 0.29.4 NamespaceLibraryTemplate.kt and Gobley's
# GenerateUniffiProguardRulesTask (copied from JNA's Android FAQ).
-dontwarn java.awt.**
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Library
-keep class * implements com.sun.jna.Callback { *; }
-keep class * extends com.sun.jna.Structure { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }

# Generated UniFFI bindings live in this package (uniffi.toml
# package_name). Method names on UniffiLib must survive shrinking
# because JNA maps them to C symbols by Java method name.
-keep class tech.asahiart.luvia.transport.** { *; }

# kotlinx.serialization 1.9.0 official common.pro:
# https://raw.githubusercontent.com/Kotlin/kotlinx.serialization/v1.9.0/rules/common.pro
# HostStore JSON and UHP codecs use @Serializable; R8 must keep
# Companion.serializer() lookup used by the runtime.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$* Companion;
}

-keepnames @kotlinx.serialization.internal.NamedCompanion class *
-if @kotlinx.serialization.internal.NamedCompanion class *
-keepclassmembernames class * {
    static <1> *;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-dontnote kotlinx.serialization.**
-dontwarn kotlinx.serialization.internal.ClassValueReferences
-keepclassmembers public class **$$serializer {
    private ** descriptor;
}
