# Vosk JNI bridges
-keep class org.vosk.** { *; }
-keepclassmembers class org.vosk.** { *; }

# Porcupine is loaded reflectively when the optional artifact is present.
-dontwarn ai.picovoice.**
-keep class ai.picovoice.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
