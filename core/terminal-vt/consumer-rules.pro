# Preserve NativeVtSession and NativeGlRenderer JNI entrypoints and callback types across R8 shrinking.
-keepclasseswithmembernames,includedescriptorclasses class dev.gfire.termvr.core.vt.** {
    native <methods>;
}
-keep @androidx.annotation.Keep class dev.gfire.termvr.core.vt.** { *; }
-keepclassmembers class dev.gfire.termvr.core.vt.** {
    @androidx.annotation.Keep <methods>;
    @androidx.annotation.Keep <fields>;
}
