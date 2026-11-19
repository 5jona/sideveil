# Keep WebView @JavascriptInterface bridges.
-keepattributes *Annotation*
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep kotlinx.serialization models and generated serializers.
-keep,allowobfuscation class pochita.sideveil.shared.** { *; }
-keepclassmembers class pochita.sideveil.shared.**$$serializer { *; }
-keep,allowobfuscation class pochita.sideveil.host.**$* { *; }

-dontwarn java.lang.invoke.**
-dontwarn org.jetbrains.annotations.**
