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
# Serializers are generated at compile time and reached by direct calls; what a
# lookup by reflection needs (serializer<T>() on a type R8 cannot follow) —
# the Companion field and its serializer() of every @Serializable class — is
# kept by the rules kotlinx-serialization-json ships in its jar
# (META-INF/proguard, `-if @kotlinx.serialization.Serializable class **`), which
# R8 applies by itself. A wider copy used to stand here and kept the Companion
# of every class in the app, serializable or not (821 seeds against ~100
# serializers); a release build checked that each $$serializer and each
# serializable class's Companion.serializer() survives without it.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

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
