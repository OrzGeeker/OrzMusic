# 保持默认即可

# ── JNI ──────────────────────────────────────────────────────────
# OrzAudioCore 的 native 方法名必须和 C 侧的
# Java_com_jokerhub_orzmusic_audiokit_OrzAudioCore_* 完全一致。
# 当前 release 没开 minify;以后开了也必须保留这个类。
-keep class com.jokerhub.orzmusic.audiokit.OrzAudioCore { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
