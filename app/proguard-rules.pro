# Add project specific ProGuard rules here.

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
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { *; }
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# Anthropic SDK
-keep class com.anthropic.** { *; }
-keepclassmembers class com.anthropic.** { *; }

# Jackson (used by the Anthropic SDK via jackson-module-kotlin, which reads
# Kotlin metadata and generic signatures reflectively)
-keep class kotlin.Metadata { *; }
-keepattributes Signature, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keep class com.fasterxml.jackson.annotation.** { *; }
-keep class com.fasterxml.jackson.databind.** { *; }
-keep class com.fasterxml.jackson.module.kotlin.** { *; }
-keep class com.fasterxml.jackson.datatype.jdk8.** { *; }
-keep class com.fasterxml.jackson.datatype.jsr310.** { *; }
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
-keep class org.ojalgo.** { *; }
-keepclassmembers class org.ojalgo.** { *; }
-dontwarn org.ojalgo.**
