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

# Rules for Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }

# Keep our data models for JSON parsing
-keep class io.github.bropines.tailscaled.StatusResponse { *; }
-keep class io.github.bropines.tailscaled.PeerData { *; }

# Keep Appctr bridge just in case
-keep class appctr.** { *; }

# Keep all Gomobile bridge classes (internal mechanics)
-keep class go.** { *; }
-keep interface go.** { *; }

# Keep all the generated Go classes of our module
-keep class appctr.** { *; }
-keep interface appctr.** { *; }

# Just in case, please don't touch the native methods (JNI)
-keepclasseswithmembernames class * {
    native<methods>;
}

# Be sure to keep the annotations and signatures for Gson, 
# so that it can properly parse logs and status from JSON
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod

# Your data classes (@Keep) are already protected, but just in case, 
# Let's protect the entire model package from aggressive obfuscation.
-keep class io.github.bropines.tailscaled.PeerData { *; }
-keep class io.github.bropines.tailscaled.StatusResponse { *; }
-keep class io.github.bropines.tailscaled.LogEntry { *; }