#ifndef ORZ_AUDIO_ENGINE_H
#define ORZ_AUDIO_ENGINE_H

#include "orz_audio_core.h"

// 每个解码器实现的统一接口
typedef struct {
    const char *name;       // 日志用
    int  (*load)(const unsigned char *data, int len);
    double (*get_duration)();
    int   (*get_sample_rate)();
    int   (*get_channels)();
    int   (*render)(float *out, int frames);
    void  (*destroy)();
    // Optional instance API. Decoders can migrate incrementally; when create is
    // NULL the dispatcher uses the legacy singleton callbacks above.
    void *(*create)(const char *format, const unsigned char *data, int len);
    double (*context_get_duration)(void *context);
    int (*context_get_sample_rate)(void *context);
    int (*context_get_channels)(void *context);
    int (*context_render)(void *context, float *out, int frames);
    void (*context_destroy)(void *context);
    // Optional navigation API. Return -1 when unsupported.
    int (*context_get_subsong_count)(void *context);
    int (*context_select_subsong)(void *context, int subsong);
    int (*context_seek_ms)(void *context, int position_ms);
} Decoder;

typedef struct OrzDecoderHandle OrzDecoderHandle;

// 注册解码器到调度表
void orz_register_decoder(const char *extensions, const Decoder *decoder);

// 统一入口（EMSCRIPTEN_KEEPALIVE 在 orz_dispatch.c 中标记）
int         orz_load(const char *format, const unsigned char *data, int len);
double      orz_get_duration();
int         orz_get_sample_rate();
int         orz_get_channels();
int         orz_render(float *out, int frames);
void        orz_destroy();
int         orz_can_decode(const char *format);

// Instance-based API. Multiple handles are independent when their decoder has
// implemented the optional context callbacks. Legacy singleton decoders reject
// a second simultaneous handle until they are migrated.
OrzDecoderHandle *orz_decoder_create(const char *format, const unsigned char *data, int len);
double            orz_decoder_get_duration(const OrzDecoderHandle *handle);
int               orz_decoder_get_sample_rate(const OrzDecoderHandle *handle);
int               orz_decoder_get_channels(const OrzDecoderHandle *handle);
int               orz_decoder_render(OrzDecoderHandle *handle, float *out, int frames);
void              orz_decoder_destroy(OrzDecoderHandle *handle);
int               orz_decoder_get_subsong_count(const OrzDecoderHandle *handle);
int               orz_decoder_select_subsong(OrzDecoderHandle *handle, int subsong);
int               orz_decoder_seek_ms(OrzDecoderHandle *handle, int position_ms);

#endif /* ORZ_AUDIO_ENGINE_H */
