# ── Native / gomobile bridge ────────────────────────────────────────────────
# The Go bridge is reached over JNI by name; R8 must not rename or drop it.
-keep class appctr.** { *; }
-keep interface appctr.** { *; }
-keep class go.** { *; }
-keep interface go.** { *; }
-keepclasseswithmembernames class * {
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

# ── Misc ────────────────────────────────────────────────────────────────────
-keepattributes Signature, EnclosingMethod
