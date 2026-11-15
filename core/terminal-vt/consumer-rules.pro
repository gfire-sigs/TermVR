# Preserve NativeVtSession JNI entrypoints and callback targets across R8 shrinking.
-keepclasseswithmembernames,includedescriptorclasses class dev.gfire.termvr.core.vt.** {
    native <methods>;
}
