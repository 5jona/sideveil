# Keep WebView @JavascriptInterface bridges.
-keepattributes *Annotation*
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep kotlinx.serialization models and generated serializers.
-keep,allowobfuscation class pochita.sideveil.protocol.** { *; }
-keepclassmembers class pochita.sideveil.protocol.**$$serializer { *; }
-keep,allowobfuscation class pochita.sideveil.host.**$* { *; }

-dontwarn java.lang.invoke.**
-dontwarn org.jetbrains.annotations.**
