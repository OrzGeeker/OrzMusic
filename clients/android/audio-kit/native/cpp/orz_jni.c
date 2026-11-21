/*
 * orz_jni.c —— OrzAudioCore C ABI 的 Android JNI 绑定
 *
 * 只做 C ABI <-> JVM 的转换:
 *   create / renderF32 / seek / selectSubsong / destroy / probe / buildInfo
 *
 * 不做 WAV、文件、HTTP、缓存、策略 —— 那些在 Kotlin (WavFile/MediaCache/PlaybackPolicy)。
 */
#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stddef.h>

#include "orz_audio_core.h"

#define TAG "OrzAudioJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static OrzDecoderHandle *as_handle(jlong value) {
    return (OrzDecoderHandle *)(intptr_t)value;
}

JNIEXPORT jlong JNICALL
Java_com_jokerhub_orzmusic_audiokit_OrzAudioCore_nativeCreateDecoder(
        JNIEnv *env, jobject thiz, jbyteArray j_data, jstring j_format, jint j_subsong) {
    (void)thiz;
    if (!j_data || !j_format) return 0;

    jsize len = (*env)->GetArrayLength(env, j_data);
    if (len < 32) return 0;

    const char *format = (*env)->GetStringUTFChars(env, j_format, NULL);
    if (!format) return 0;

    jbyte *bytes = (*env)->GetByteArrayElements(env, j_data, NULL);
    if (!bytes) {
        (*env)->ReleaseStringUTFChars(env, j_format, format);
        return 0;
    }

    orz_decoder_config cfg;
    memset(&cfg, 0, sizeof(cfg));
    cfg.struct_size = (uint32_t)sizeof(cfg);
    cfg.abi_version = ORZ_ABI_VERSION;
    cfg.subsong = (uint32_t)(j_subsong > 0 ? j_subsong : 0);
    cfg.max_input_bytes = (uint64_t)len;

    OrzDecoderHandle *handle = NULL;
    orz_status status = orz_decoder_create_memory(
        (const uint8_t *)bytes, (size_t)len, format, &cfg, &handle);

    (*env)->ReleaseByteArrayElements(env, j_data, bytes, JNI_ABORT);

    if (status != ORZ_OK || !handle) {
        LOGE("create failed: format=%s bytes=%d subsong=%d status=%d (%s)",
             format, (int)len, (int)cfg.subsong, (int)status, orz_status_message(status));
        (*env)->ReleaseStringUTFChars(env, j_format, format);
        return 0;
    }
    LOGI("decoder created: format=%s bytes=%d subsong=%d handle=%p",
         format, (int)len, (int)cfg.subsong, (void *)handle);
    (*env)->ReleaseStringUTFChars(env, j_format, format);
    return (jlong)(intptr_t)handle;
}

JNIEXPORT jlongArray JNICALL
Java_com_jokerhub_orzmusic_audiokit_OrzAudioCore_nativeStreamInfo(
        JNIEnv *env, jobject thiz, jlong j_handle) {
    (void)thiz;
    OrzDecoderHandle *handle = as_handle(j_handle);
    if (!handle) return NULL;

    orz_stream_info info;
    memset(&info, 0, sizeof(info));
    info.struct_size = (uint32_t)sizeof(info);
    info.abi_version = ORZ_ABI_VERSION;
    if (orz_decoder_get_stream_info(handle, &info) != ORZ_OK) return NULL;

    jlong values[5];
    values[0] = (jlong)info.sample_rate;
    values[1] = (jlong)info.channels;
    values[2] = (jlong)(info.duration_seconds * 1000.0 + 0.5);
    values[3] = (jlong)info.subsong_count;
    values[4] = (jlong)info.capabilities;

    jlongArray out = (*env)->NewLongArray(env, 5);
    if (!out) return NULL;
    (*env)->SetLongArrayRegion(env, out, 0, 5, values);
    return out;
}

JNIEXPORT jint JNICALL
Java_com_jokerhub_orzmusic_audiokit_OrzAudioCore_nativeRenderF32(
        JNIEnv *env, jobject thiz, jlong j_handle, jfloatArray j_out, jint j_frames) {
    (void)thiz;
    OrzDecoderHandle *handle = as_handle(j_handle);
    if (!handle || !j_out || j_frames <= 0) return ORZ_ERROR_INVALID_ARGUMENT;

    // OrzAudioCore 的所有解码器最多输出 stereo;调用方按 info.channels 分配。
    jsize capacity = (*env)->GetArrayLength(env, j_out);
    if (capacity < j_frames) return ORZ_ERROR_INVALID_ARGUMENT;

    jfloat *out = (*env)->GetFloatArrayElements(env, j_out, NULL);
    if (!out) return ORZ_ERROR_OUT_OF_MEMORY;

    uint32_t rendered = 0;
    orz_status status = orz_decoder_render_f32(
        handle, (float *)out, (uint32_t)j_frames, &rendered);

    // mode 0 = copy back and free
    (*env)->ReleaseFloatArrayElements(env, j_out, out, 0);

    if (status == ORZ_END_OF_STREAM) return 0;
    if (status != ORZ_OK) {
        LOGE("render failed: handle=%p frames=%d status=%d (%s)",
             (void *)handle, (int)j_frames, (int)status, orz_status_message(status));
        return (jint)status;   // negative
    }
    return (jint)rendered;
}

JNIEXPORT jint JNICALL
Java_com_jokerhub_orzmusic_audiokit_OrzAudioCore_nativeSeek(
        JNIEnv *env, jobject thiz, jlong j_handle, jlong j_position_ms) {
    (void)env; (void)thiz;
    OrzDecoderHandle *handle = as_handle(j_handle);
    if (!handle || j_position_ms < 0) return ORZ_ERROR_INVALID_ARGUMENT;
    return (jint)orz_decoder_seek(handle, (uint64_t)j_position_ms);
}

JNIEXPORT jint JNICALL
Java_com_jokerhub_orzmusic_audiokit_OrzAudioCore_nativeSelectSubsong(
        JNIEnv *env, jobject thiz, jlong j_handle, jint j_subsong) {
    (void)env; (void)thiz;
    OrzDecoderHandle *handle = as_handle(j_handle);
    if (!handle || j_subsong < 0) return ORZ_ERROR_INVALID_ARGUMENT;
    return (jint)orz_decoder_select_subsong_v1(handle, (uint32_t)j_subsong);
}

JNIEXPORT void JNICALL
Java_com_jokerhub_orzmusic_audiokit_OrzAudioCore_nativeDestroy(
        JNIEnv *env, jobject thiz, jlong j_handle) {
    (void)env; (void)thiz;
    OrzDecoderHandle *handle = as_handle(j_handle);
    if (handle) orz_decoder_destroy_v1(handle);
}

/*
 * 从别的线程打断正在进行的 render。
 * 关闭流式解码时先 cancel 再 join 解码线程，最后才 destroy ——
 * 否则会在 render 还没返回时释放 handle(use-after-free)。
 */
JNIEXPORT jint JNICALL
Java_com_jokerhub_orzmusic_audiokit_OrzAudioCore_nativeCancel(
        JNIEnv *env, jobject thiz, jlong j_handle) {
    (void)env; (void)thiz;
    OrzDecoderHandle *handle = as_handle(j_handle);
    if (!handle) return ORZ_ERROR_INVALID_ARGUMENT;
    return (jint)orz_decoder_cancel(handle);
}

JNIEXPORT jstring JNICALL
Java_com_jokerhub_orzmusic_audiokit_OrzAudioCore_nativeProbe(
        JNIEnv *env, jobject thiz, jbyteArray j_data, jstring j_extension) {
    (void)thiz;
    if (!j_data) return NULL;

    jsize len = (*env)->GetArrayLength(env, j_data);
    if (len < 32) return NULL;

    jbyte *bytes = (*env)->GetByteArrayElements(env, j_data, NULL);
    if (!bytes) return NULL;

    const char *extension = NULL;
    if (j_extension) {
        extension = (*env)->GetStringUTFChars(env, j_extension, NULL);
    }

    orz_probe_result result;
    memset(&result, 0, sizeof(result));
    result.struct_size = (uint32_t)sizeof(result);
    result.abi_version = ORZ_ABI_VERSION;

    orz_status status = orz_probe(
        (const uint8_t *)bytes, (size_t)len, extension, &result);

    if (extension) (*env)->ReleaseStringUTFChars(env, j_extension, extension);
    (*env)->ReleaseByteArrayElements(env, j_data, bytes, JNI_ABORT);

    if (status != ORZ_OK || !result.format_id) return NULL;
    return (*env)->NewStringUTF(env, result.format_id);
}

JNIEXPORT jint JNICALL
Java_com_jokerhub_orzmusic_audiokit_OrzAudioCore_nativeAbiVersion(
        JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return (jint)orz_abi_version();
}

/*
 * 本机 .so 实际编进来的格式 id 列表。
 *
 * 生成的 DecoderManifest 是「这个 SDK 版本提供哪些格式」,而这个调用的结果才是
 * 「当前二进制真的能解哪些格式」—— 两者取交集才是可用能力。
 * 注册表用弱符号存根过滤缺失解码器(见 audio_engine.c 的 available()),
 * 所以这里不需要自己判断 profile。
 */
JNIEXPORT jobjectArray JNICALL
Java_com_jokerhub_orzmusic_audiokit_OrzAudioCore_nativeFormatIds(
        JNIEnv *env, jobject thiz) {
    (void)thiz;

    uint32_t total = orz_get_format_count();
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    if (!string_class) return NULL;

    jobjectArray out = (*env)->NewObjectArray(env, (jsize)total, string_class, NULL);
    if (!out) return NULL;

    jsize written = 0;
    for (uint32_t i = 0; i < total; ++i) {
        orz_format_info info;
        memset(&info, 0, sizeof(info));
        info.struct_size = (uint32_t)sizeof(info);
        info.abi_version = ORZ_ABI_VERSION;
        if (orz_get_format_info(i, &info) != ORZ_OK || !info.format_id) continue;

        jstring id = (*env)->NewStringUTF(env, info.format_id);
        if (!id) continue;
        (*env)->SetObjectArrayElement(env, out, written++, id);
        (*env)->DeleteLocalRef(env, id);
    }

    /* 有条目被跳过时数组尾部是 NULL;截断成 written 长度,避免 Kotlin 侧出现 null。 */
    if (written == (jsize)total) return out;

    jobjectArray trimmed = (*env)->NewObjectArray(env, written, string_class, NULL);
    if (!trimmed) return out;
    for (jsize i = 0; i < written; ++i) {
        jobject item = (*env)->GetObjectArrayElement(env, out, i);
        (*env)->SetObjectArrayElement(env, trimmed, i, item);
        if (item) (*env)->DeleteLocalRef(env, item);
    }
    return trimmed;
}

JNIEXPORT jstring JNICALL
Java_com_jokerhub_orzmusic_audiokit_OrzAudioCore_nativeBuildInfo(
        JNIEnv *env, jobject thiz) {
    (void)thiz;
    const char *info = orz_build_info();
    return (*env)->NewStringUTF(env, info ? info : "{}");
}
