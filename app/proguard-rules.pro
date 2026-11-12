# mpv: native code calls into these classes by name.
-keep class dev.jdtech.mpv.** { *; }

# kotlinx.serialization ships its own rules; these keep the generated serializers of our models explicit.
-keepclassmembers @kotlinx.serialization.Serializable class app.neifur.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.neifur.**$$serializer { *; }

# jsoup's optional regex engine and annotations are not on the classpath.
-dontwarn com.google.re2j.**
-dontwarn org.jspecify.annotations.**
