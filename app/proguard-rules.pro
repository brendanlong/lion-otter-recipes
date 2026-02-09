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

# OkHttp (used by Anthropic SDK)
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Anthropic SDK transitive dependencies (not used on Android but referenced)
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn com.github.victools.jsonschema.generator.**
