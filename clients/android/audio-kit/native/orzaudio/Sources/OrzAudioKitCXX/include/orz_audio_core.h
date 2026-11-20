#ifndef ORZ_AUDIO_CORE_H
#define ORZ_AUDIO_CORE_H

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32) && defined(ORZ_AUDIO_CORE_SHARED)
#  ifdef ORZ_AUDIO_CORE_BUILD
#    define ORZ_API __declspec(dllexport)
#  else
#    define ORZ_API __declspec(dllimport)
#  endif
#elif defined(__GNUC__) || defined(__clang__)
#  define ORZ_API __attribute__((visibility("default")))
#else
#  define ORZ_API
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define ORZ_ABI_VERSION_MAJOR 1u
#define ORZ_ABI_VERSION_MINOR 0u
#define ORZ_ABI_VERSION ((ORZ_ABI_VERSION_MAJOR << 16u) | ORZ_ABI_VERSION_MINOR)

typedef struct OrzDecoderHandle OrzDecoderHandle;

typedef enum orz_status {
    ORZ_OK = 0,
    ORZ_END_OF_STREAM = 1,
    ORZ_ERROR_INVALID_ARGUMENT = -1,
    ORZ_ERROR_UNSUPPORTED_FORMAT = -2,
    ORZ_ERROR_UNSUPPORTED_OPERATION = -3,
    ORZ_ERROR_CORRUPT_DATA = -4,
    ORZ_ERROR_OUT_OF_MEMORY = -5,
    ORZ_ERROR_CANCELLED = -6,
    ORZ_ERROR_INTERNAL = -7,
    ORZ_ERROR_ABI_MISMATCH = -8
} orz_status;

typedef enum orz_capability {
    ORZ_CAP_RENDER = 1u << 0u,
    ORZ_CAP_DURATION = 1u << 1u,
    ORZ_CAP_SEEK = 1u << 2u,
    ORZ_CAP_SUBSONG = 1u << 3u,
    ORZ_CAP_PROBE = 1u << 4u,
    ORZ_CAP_CONCURRENT_INSTANCES = 1u << 5u
} orz_capability;

typedef struct orz_decoder_config {
    uint32_t struct_size;
    uint32_t abi_version;
    uint32_t subsong;
    uint32_t reserved_flags;
    uint64_t max_input_bytes;
    uint64_t reserved[4];
} orz_decoder_config;

typedef struct orz_stream_info {
    uint32_t struct_size;
    uint32_t abi_version;
    uint32_t sample_rate;
    uint32_t channels;
    double duration_seconds;
    uint32_t subsong_count;
    uint32_t capabilities;
    uint64_t reserved[4];
} orz_stream_info;

typedef struct orz_format_info {
    uint32_t struct_size;
    uint32_t abi_version;
    const char *format_id;
    const char *display_name;
    const char *decoder_id;
    const char *decoder_version;
    const char *category;
    uint32_t capabilities;
    uint32_t platform_flags;
    uint64_t reserved[2];
} orz_format_info;

typedef struct orz_probe_result {
    uint32_t struct_size;
    uint32_t abi_version;
    const char *format_id;
    uint32_t confidence;
    uint32_t reserved_flags;
    uint64_t reserved[2];
} orz_probe_result;

ORZ_API uint32_t orz_abi_version(void);
ORZ_API const char *orz_build_info(void);
ORZ_API const char *orz_status_message(orz_status status);
ORZ_API uint32_t orz_get_format_count(void);
ORZ_API orz_status orz_get_format_info(uint32_t index, orz_format_info *out_info);
ORZ_API orz_status orz_probe(const uint8_t *data, size_t size, const char *extension_hint,
                             orz_probe_result *out_result);

ORZ_API orz_status orz_decoder_create_memory(const uint8_t *data, size_t size,
                                              const char *format_hint,
                                              const orz_decoder_config *config,
                                              OrzDecoderHandle **out_decoder);
ORZ_API orz_status orz_decoder_get_stream_info(const OrzDecoderHandle *decoder,
                                                orz_stream_info *out_info);
ORZ_API orz_status orz_decoder_render_f32(OrzDecoderHandle *decoder, float *output,
                                          uint32_t requested_frames,
                                          uint32_t *out_rendered_frames);
ORZ_API orz_status orz_decoder_seek(OrzDecoderHandle *decoder, uint64_t position_ms);
ORZ_API orz_status orz_decoder_select_subsong_v1(OrzDecoderHandle *decoder, uint32_t subsong);
ORZ_API orz_status orz_decoder_reset(OrzDecoderHandle *decoder);
ORZ_API orz_status orz_decoder_cancel(OrzDecoderHandle *decoder);
ORZ_API void orz_decoder_destroy_v1(OrzDecoderHandle *decoder);

#ifdef __cplusplus
}
#endif
#endif
