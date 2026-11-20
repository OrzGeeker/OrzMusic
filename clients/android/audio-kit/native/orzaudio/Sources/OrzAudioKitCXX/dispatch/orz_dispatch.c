#ifdef __EMSCRIPTEN__
#include <emscripten.h>
#else
#define EMSCRIPTEN_KEEPALIVE
#endif
#include <string.h>
#include <stdlib.h>
#include <limits.h>
#include <stdint.h>
#include <stddef.h>
#include <stdatomic.h>
#include "audio_engine.h"
#include "orz_audio_core.h"
#include "decoder_registry.h"
#include "build_info.generated.h"

static const Decoder *find_decoder(const char *format) {
    const OrzDecoderDescriptor *entry = orz_registry_find(format);
    return entry ? entry->decoder : NULL;
}

// ── 当前活跃解码器 ──

struct OrzDecoderHandle {
    const Decoder *decoder;
    void *context;
    unsigned char *owned_data;
    size_t owned_size;
    char format[16];
    uint32_t selected_subsong;
    atomic_int cancelled;
};

static OrzDecoderHandle *active = NULL;

// Deprecated compatibility symbol referenced by the old AdPlug singleton
// wrapper. The v1 ABI always passes the format directly to create().
const char *orz_current_format = NULL;

// Deprecated compatibility hook. The registry is immutable in ABI v1.
void orz_register_decoder(const char *extensions, const Decoder *decoder) {
    (void)extensions;
    (void)decoder;
}

// 外部注册函数（在 audio_engine.c 中定义）
extern void register_all(void);

// ── 统一入口 ──

EMSCRIPTEN_KEEPALIVE
int orz_load(const char *format, const unsigned char *data, int len) {
    orz_destroy();
    active = orz_decoder_create(format, data, len);
    return active != NULL;
}

EMSCRIPTEN_KEEPALIVE
double orz_get_duration() {
    return orz_decoder_get_duration(active);
}

EMSCRIPTEN_KEEPALIVE
int orz_get_sample_rate() {
    return orz_decoder_get_sample_rate(active);
}

EMSCRIPTEN_KEEPALIVE
int orz_get_channels() {
    return orz_decoder_get_channels(active);
}

EMSCRIPTEN_KEEPALIVE
int orz_render(float *out, int frames) {
    return orz_decoder_render(active, out, frames);
}

EMSCRIPTEN_KEEPALIVE
void orz_destroy() {
    orz_decoder_destroy(active);
    active = NULL;
}

EMSCRIPTEN_KEEPALIVE
int orz_can_decode(const char *format) {
    return find_decoder(format) != NULL;
}

EMSCRIPTEN_KEEPALIVE
OrzDecoderHandle *orz_decoder_create(const char *format, const unsigned char *data, int len) {
    // Bound untrusted inputs before any third-party parser sees them. The
    // public ABI uses signed int lengths, and no supported music format needs
    // hundreds of MiB of in-memory module data.
    if (!format || !data || len < 32 || len > 512 * 1024 * 1024) return NULL;
    const Decoder *dec = find_decoder(format);
    if (!dec) return NULL;

    OrzDecoderHandle *handle = (OrzDecoderHandle *)calloc(1, sizeof(*handle));
    if (!handle) return NULL;
    handle->decoder = dec;

    if (!dec->create) { free(handle); return NULL; }
    handle->context = dec->create(format, data, len);
    if (!handle->context) { free(handle); return NULL; }
    strncpy(handle->format, format, sizeof(handle->format) - 1u);
    atomic_init(&handle->cancelled, 0);
    return handle;
}

EMSCRIPTEN_KEEPALIVE
double orz_decoder_get_duration(const OrzDecoderHandle *handle) {
    if (!handle) return 0.0;
    return handle->decoder->context_get_duration(handle->context);
}

EMSCRIPTEN_KEEPALIVE
int orz_decoder_get_sample_rate(const OrzDecoderHandle *handle) {
    if (!handle) return 0;
    return handle->decoder->context_get_sample_rate(handle->context);
}

EMSCRIPTEN_KEEPALIVE
int orz_decoder_get_channels(const OrzDecoderHandle *handle) {
    if (!handle) return 0;
    return handle->decoder->context_get_channels(handle->context);
}

EMSCRIPTEN_KEEPALIVE
int orz_decoder_render(OrzDecoderHandle *handle, float *out, int frames) {
    // Every decoder outputs at most stereo float32. Prevent frames*2 and byte
    // count overflow in backend scratch-buffer calculations.
    if (!handle || !out || frames <= 0 || frames > INT_MAX / 2) return 0;
    if (atomic_load(&handle->cancelled)) return 0;
    return handle->decoder->context_render(handle->context, out, frames);
}

EMSCRIPTEN_KEEPALIVE
void orz_decoder_destroy(OrzDecoderHandle *handle) {
    if (!handle) return;
    if (handle->context) handle->decoder->context_destroy(handle->context);
    free(handle->owned_data);
    free(handle);
}

EMSCRIPTEN_KEEPALIVE
int orz_decoder_get_subsong_count(const OrzDecoderHandle *handle) {
    if (!handle || !handle->context || !handle->decoder->context_get_subsong_count) return -1;
    return handle->decoder->context_get_subsong_count(handle->context);
}

EMSCRIPTEN_KEEPALIVE
int orz_decoder_select_subsong(OrzDecoderHandle *handle, int subsong) {
    if (!handle || !handle->context || !handle->decoder->context_select_subsong || subsong < 0) return -1;
    return handle->decoder->context_select_subsong(handle->context, subsong);
}

EMSCRIPTEN_KEEPALIVE
int orz_decoder_seek_ms(OrzDecoderHandle *handle, int position_ms) {
    if (!handle || !handle->context || !handle->decoder->context_seek_ms || position_ms < 0) return -1;
    return handle->decoder->context_seek_ms(handle->context, position_ms);
}

// ── Stable OrzAudioCore ABI v1 ──

uint32_t orz_abi_version(void) { return ORZ_ABI_VERSION; }

const char *orz_build_info(void) {
    return ORZ_BUILD_INFO_JSON;
}

const char *orz_status_message(orz_status status) {
    switch (status) {
        case ORZ_OK: return "success";
        case ORZ_END_OF_STREAM: return "end of stream";
        case ORZ_ERROR_INVALID_ARGUMENT: return "invalid argument";
        case ORZ_ERROR_UNSUPPORTED_FORMAT: return "unsupported format";
        case ORZ_ERROR_UNSUPPORTED_OPERATION: return "unsupported operation";
        case ORZ_ERROR_CORRUPT_DATA: return "corrupt or unrecognized audio data";
        case ORZ_ERROR_OUT_OF_MEMORY: return "out of memory";
        case ORZ_ERROR_CANCELLED: return "operation cancelled";
        case ORZ_ERROR_INTERNAL: return "internal decoder error";
        case ORZ_ERROR_ABI_MISMATCH: return "ABI version or structure size mismatch";
        default: return "unknown status";
    }
}

uint32_t orz_get_format_count(void) { return orz_registry_count(); }

static int valid_struct(uint32_t size, uint32_t version, size_t required) {
    return size >= required && (version >> 16u) == ORZ_ABI_VERSION_MAJOR;
}

orz_status orz_get_format_info(uint32_t index, orz_format_info *out_info) {
    if (!out_info) return ORZ_ERROR_INVALID_ARGUMENT;
    if (!valid_struct(out_info->struct_size, out_info->abi_version, sizeof(*out_info)))
        return ORZ_ERROR_ABI_MISMATCH;
    const OrzDecoderDescriptor *entry = orz_registry_at(index);
    if (!entry) return ORZ_ERROR_INVALID_ARGUMENT;
    uint32_t size = out_info->struct_size;
    memset(out_info, 0, sizeof(*out_info));
    out_info->struct_size = size;
    out_info->abi_version = ORZ_ABI_VERSION;
    out_info->format_id = entry->format_id;
    out_info->display_name = entry->display_name;
    out_info->decoder_id = entry->decoder_id;
    out_info->decoder_version = entry->decoder_version;
    out_info->category = entry->category;
    out_info->capabilities = entry->capabilities;
    out_info->platform_flags = entry->platform_flags;
    return ORZ_OK;
}

orz_status orz_decoder_create_memory(const uint8_t *data, size_t size, const char *format_hint,
                                     const orz_decoder_config *config,
                                     OrzDecoderHandle **out_decoder) {
    if (!out_decoder) return ORZ_ERROR_INVALID_ARGUMENT;
    *out_decoder = NULL;
    if (!data || !format_hint || size < 32u || size > (size_t)INT_MAX)
        return ORZ_ERROR_INVALID_ARGUMENT;
    if (!orz_registry_find(format_hint)) return ORZ_ERROR_UNSUPPORTED_FORMAT;
    uint64_t max_size = 512ull * 1024ull * 1024ull;
    uint32_t subsong = 0;
    if (config) {
        if (!valid_struct(config->struct_size, config->abi_version, sizeof(*config)))
            return ORZ_ERROR_ABI_MISMATCH;
        if (config->max_input_bytes) max_size = config->max_input_bytes;
        subsong = config->subsong;
    }
    if ((uint64_t)size > max_size) return ORZ_ERROR_INVALID_ARGUMENT;
    unsigned char *copy = (unsigned char *)malloc(size);
    if (!copy) return ORZ_ERROR_OUT_OF_MEMORY;
    memcpy(copy, data, size);
    OrzDecoderHandle *handle = orz_decoder_create(format_hint, copy, (int)size);
    if (!handle) { free(copy); return ORZ_ERROR_CORRUPT_DATA; }
    handle->owned_data = copy;
    handle->owned_size = size;
    if (subsong > 0) {
        if (orz_decoder_select_subsong(handle, (int)subsong) != 0) {
            orz_decoder_destroy(handle);
            return ORZ_ERROR_UNSUPPORTED_OPERATION;
        }
        handle->selected_subsong = subsong;
    }
    *out_decoder = handle;
    return ORZ_OK;
}

orz_status orz_probe(const uint8_t *data, size_t size, const char *extension_hint,
                     orz_probe_result *out_result) {
    if (!data || !out_result) return ORZ_ERROR_INVALID_ARGUMENT;
    if (!valid_struct(out_result->struct_size, out_result->abi_version, sizeof(*out_result)))
        return ORZ_ERROR_ABI_MISMATCH;
    uint32_t result_size = out_result->struct_size;
    memset(out_result, 0, sizeof(*out_result));
    out_result->struct_size = result_size;
    out_result->abi_version = ORZ_ABI_VERSION;
    if (extension_hint && orz_registry_find(extension_hint)) {
        OrzDecoderHandle *candidate = NULL;
        orz_status status = orz_decoder_create_memory(data, size, extension_hint, NULL, &candidate);
        if (status == ORZ_OK) {
            orz_decoder_destroy(candidate);
            out_result->format_id = orz_registry_find(extension_hint)->format_id;
            out_result->confidence = 100;
            return ORZ_OK;
        }
    }
    for (uint32_t i = 0; i < orz_registry_count(); ++i) {
        const OrzDecoderDescriptor *entry = orz_registry_at(i);
        OrzDecoderHandle *candidate = NULL;
        if (orz_decoder_create_memory(data, size, entry->format_id, NULL, &candidate) == ORZ_OK) {
            orz_decoder_destroy(candidate);
            out_result->format_id = entry->format_id;
            out_result->confidence = 80;
            return ORZ_OK;
        }
    }
    return ORZ_ERROR_UNSUPPORTED_FORMAT;
}

orz_status orz_decoder_get_stream_info(const OrzDecoderHandle *decoder, orz_stream_info *out_info) {
    if (!decoder || !out_info) return ORZ_ERROR_INVALID_ARGUMENT;
    if (!valid_struct(out_info->struct_size, out_info->abi_version, sizeof(*out_info)))
        return ORZ_ERROR_ABI_MISMATCH;
    const OrzDecoderDescriptor *entry = orz_registry_find(decoder->format);
    uint32_t size = out_info->struct_size;
    memset(out_info, 0, sizeof(*out_info));
    out_info->struct_size = size;
    out_info->abi_version = ORZ_ABI_VERSION;
    out_info->sample_rate = (uint32_t)orz_decoder_get_sample_rate(decoder);
    out_info->channels = (uint32_t)orz_decoder_get_channels(decoder);
    out_info->duration_seconds = orz_decoder_get_duration(decoder);
    int count = orz_decoder_get_subsong_count(decoder);
    out_info->subsong_count = count > 0 ? (uint32_t)count : 1u;
    out_info->capabilities = entry ? entry->capabilities : ORZ_CAP_RENDER;
    return ORZ_OK;
}

orz_status orz_decoder_render_f32(OrzDecoderHandle *decoder, float *output,
                                  uint32_t requested_frames, uint32_t *out_rendered_frames) {
    if (!decoder || !output || !out_rendered_frames || requested_frames == 0u || requested_frames > INT_MAX)
        return ORZ_ERROR_INVALID_ARGUMENT;
    *out_rendered_frames = 0;
    if (atomic_load(&decoder->cancelled)) return ORZ_ERROR_CANCELLED;
    int rendered = orz_decoder_render(decoder, output, (int)requested_frames);
    if (atomic_load(&decoder->cancelled)) return ORZ_ERROR_CANCELLED;
    if (rendered < 0) return ORZ_ERROR_INTERNAL;
    *out_rendered_frames = (uint32_t)rendered;
    return rendered == 0 ? ORZ_END_OF_STREAM : ORZ_OK;
}

orz_status orz_decoder_seek(OrzDecoderHandle *decoder, uint64_t position_ms) {
    if (!decoder || position_ms > INT_MAX) return ORZ_ERROR_INVALID_ARGUMENT;
    if (!decoder->decoder->context_seek_ms) return ORZ_ERROR_UNSUPPORTED_OPERATION;
    return orz_decoder_seek_ms(decoder, (int)position_ms) == 0 ? ORZ_OK : ORZ_ERROR_INTERNAL;
}

orz_status orz_decoder_select_subsong_v1(OrzDecoderHandle *decoder, uint32_t subsong) {
    if (!decoder || subsong > INT_MAX) return ORZ_ERROR_INVALID_ARGUMENT;
    if (!decoder->decoder->context_select_subsong) return ORZ_ERROR_UNSUPPORTED_OPERATION;
    if (orz_decoder_select_subsong(decoder, (int)subsong) != 0) return ORZ_ERROR_INVALID_ARGUMENT;
    decoder->selected_subsong = subsong;
    return ORZ_OK;
}

orz_status orz_decoder_reset(OrzDecoderHandle *decoder) {
    if (!decoder) return ORZ_ERROR_INVALID_ARGUMENT;
    atomic_store(&decoder->cancelled, 0);
    if (decoder->decoder->context_seek_ms && orz_decoder_seek_ms(decoder, 0) == 0) return ORZ_OK;
    if (!decoder->owned_data || !decoder->owned_size) return ORZ_ERROR_UNSUPPORTED_OPERATION;
    void *replacement = decoder->decoder->create(decoder->format, decoder->owned_data, (int)decoder->owned_size);
    if (!replacement) return ORZ_ERROR_INTERNAL;
    decoder->decoder->context_destroy(decoder->context);
    decoder->context = replacement;
    if (decoder->selected_subsong > 0 && decoder->decoder->context_select_subsong)
        decoder->decoder->context_select_subsong(decoder->context, (int)decoder->selected_subsong);
    return ORZ_OK;
}

orz_status orz_decoder_cancel(OrzDecoderHandle *decoder) {
    if (!decoder) return ORZ_ERROR_INVALID_ARGUMENT;
    atomic_store(&decoder->cancelled, 1);
    return ORZ_OK;
}

void orz_decoder_destroy_v1(OrzDecoderHandle *decoder) { orz_decoder_destroy(decoder); }
