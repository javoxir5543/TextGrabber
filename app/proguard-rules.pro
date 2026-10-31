# RHVoice JNI — faqat kerakli klasslar
-keep class com.github.olga_yakovleva.rhvoice.VoiceInfo { *; }
-keep class com.github.olga_yakovleva.rhvoice.LanguageInfo { *; }
-keep class com.github.olga_yakovleva.rhvoice.SynthesisParameters { *; }
-keep class com.github.olga_yakovleva.rhvoice.Logger { *; }
-keep class com.github.olga_yakovleva.rhvoice.LogLevel { *; }
-keep class com.github.olga_yakovleva.rhvoice.TTSClient { *; }
-keep class com.github.olga_yakovleva.rhvoice.RHVoiceException { *; }
-keep class com.github.olga_yakovleva.rhvoice.TTSEngine {
    native <methods>;
    <init>(...);
    *;
}

# Tesseract
-keep class com.googlecode.tesseract.android.** { *; }
-keepclassmembers class com.googlecode.tesseract.android.** { *; }

# ML Kit
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# TensorFlow
-keep class org.tensorflow.** { *; }
-dontwarn org.tensorflow.**

# TextGrabber
-keep class com.textgrabber.app.** { *; }
-keepclassmembers class com.textgrabber.app.** { *; }

# Debug log'larni olib tashlash
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
