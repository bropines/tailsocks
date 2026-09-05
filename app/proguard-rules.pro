# ── Native / gomobile bridge ────────────────────────────────────────────────
# The Go bridge is reached over JNI by name; R8 must not rename or drop it.
-keep class appctr.** { *; }
-keep interface appctr.** { *; }
-keep class go.** { *; }
-keep interface go.** { *; }

# JNI entry points. hev-socks5-tunnel registers its whole method table with
# RegisterNatives in JNI_OnLoad, so System.loadLibrary fails with
# NoSuchMethodError if even one native method was shrunk away. The stock
# `-keepclasseswithmembernames` rule allows shrinking (it dropped the unused
# TProxyGetStats and crashed the app on every stop), so these must be plain
# keeps: names and bodies of every native method, in every class that has one.
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}
-keep class io.github.bropines.tailscaled.core.TunVpnService {
    native <methods>;
    public static ** Companion;
}
-keep class io.github.bropines.tailscaled.core.TunVpnService$Companion {
    native <methods>;
}
-keep class io.github.bropines.tailscaled.core.ByeDpiProxy {
    native <methods>;
}

# ── kotlinx.serialization ───────────────────────────────────────────────────
# Serializers are generated at compile time, so almost nothing is needed — but
# keep the generated $$serializer classes and the synthetic serializer()
# accessors for this app's @Serializable models so R8 does not strip them.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class io.github.bropines.tailscaled.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.bropines.tailscaled.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.bropines.tailscaled.**$$serializer { *; }

# ── Jetpack AppFunctions (KSP-generated inventory/invoker) ───────────────────
-keep class androidx.appfunctions.internal.** { *; }
-keep @androidx.appfunctions.AppFunctionSerializable class * { *; }
-keep class io.github.bropines.tailscaled.appfunctions.** { *; }

# ── Glance widgets ──────────────────────────────────────────────────────────
# GlanceAppWidgetManager persists each GlanceAppWidget subclass's canonicalName
# in its DataStore and getGlanceIds() looks ids up by that name. Minified names
# change from build to build, so after an update the lookup came back empty and
# widgets stayed stale until the next system-driven update. Keep the names.
-keepnames class * extends androidx.glance.appwidget.GlanceAppWidget

# ── Misc ────────────────────────────────────────────────────────────────────
-keepattributes Signature, EnclosingMethod
