# kotlinx.serialization: keep generated serializers for backup DTOs.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.lifeadmin.autopilot.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.lifeadmin.autopilot.**$$serializer { *; }
