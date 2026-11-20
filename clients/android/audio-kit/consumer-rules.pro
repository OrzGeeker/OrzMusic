# JNI 在 liborzaudio.so 里按类名/方法名查找:
#   Java_com_jokerhub_orzmusic_audiokit_OrzAudioCore_nativeCreateDecoder 等
# 开了 minify 也必须保留这个类和 native 方法名。
-keep class com.jokerhub.orzmusic.audiokit.OrzAudioCore { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
