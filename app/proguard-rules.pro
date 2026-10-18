# kotlinx.serialization: keep generated serializers for backup DTOs.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.lifeadmin.autopilot.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.lifeadmin.autopilot.**$$serializer { *; }

# Anthropic Java SDK (M2 AI planning) ships its own keep rules (META-INF/proguard). Its
# OpenTelemetry dependency references the compile-only AutoValue annotation.
-dontwarn com.google.auto.value.AutoValue
