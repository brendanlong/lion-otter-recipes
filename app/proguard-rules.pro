# Add project specific ProGuard rules here.

# Keep line numbers so Crashlytics stack traces are readable
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.lionotter.recipes.**$$serializer { *; }
-keepclassmembers class com.lionotter.recipes.** {
    *** Companion;
}
-keepclasseswithmembers class com.lionotter.recipes.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor (used by WebScraperService)
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# Anthropic SDK: anthropic-java-core ships its own R8 rules for Jackson and its models
# (META-INF/proguard/anthropic-java-core.pro), so no blanket keep is needed here.
-dontwarn com.fasterxml.jackson.databind.ext.**

# OkHttp (used by Anthropic SDK)
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Anthropic SDK transitive dependencies (not used on Android but referenced)
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn com.github.victools.jsonschema.generator.**

# jsoup (optional re2j dependency not present on Android)
-dontwarn com.google.re2j.**

# Firestore DTO classes (deserialized via reflection with toObject())
-keep class com.lionotter.recipes.data.remote.dto.** {
    <init>(...);
    *;
}

# ojAlgo (ILP solver for tag selection)
-dontwarn org.ojalgo.**
