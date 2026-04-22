# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# =============================================================================
# DISABLE OBFUSCATION - KEEP OPTIMIZATION AND SHRINKING
# =============================================================================
-dontobfuscate

# =============================================================================
# GSON - Keep generic type info for TypeToken-based deserialization
# =============================================================================
-keepattributes Signature
-keepattributes *Annotation*

# Keep Gson TypeToken and its subclasses (anonymous inner classes)
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Keep generic signatures of classes used with Gson
-keep class com.google.gson.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# =============================================================================
# APP DATA CLASSES - Keep fields used in JSON serialization
# =============================================================================
-keep class com.pauwma.glyphbeat.ui.settings.ThemeSettings { *; }
-keep class com.pauwma.glyphbeat.ui.settings.ThemeSettingDefinition { *; }
-keep class com.pauwma.glyphbeat.ui.settings.** { *; }
-keep class com.pauwma.glyphbeat.data.ShakeControlSettings { *; }
-keep class com.pauwma.glyphbeat.data.** { *; }
