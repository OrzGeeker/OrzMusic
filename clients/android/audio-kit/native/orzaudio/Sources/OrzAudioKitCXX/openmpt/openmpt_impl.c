#include <libopenmpt/libopenmpt.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include "audio_engine.h"

// C++ 异常安全包装（在 cxx_helpers.cpp 中实现）
extern openmpt_module* safe_openmpt_create(const unsigned char* data, size_t len);
extern double safe_openmpt_duration(openmpt_module* mod);
extern size_t safe_openmpt_render_interleaved(openmpt_module* mod, int rate,
                                              size_t frames, float* out);
extern void safe_openmpt_destroy(openmpt_module* mod);

// ── 旧 ABI 兼容状态；实例 API 不使用它 ──
static openmpt_module *mod = NULL;

// ── Decoder 接口实现 ──

static int impl_load(const unsigned char *data, int len) {
    if (mod) {
        safe_openmpt_destroy(mod);
        mod = NULL;
    }
    mod = safe_openmpt_create(data, (size_t)len);
    return mod ? 1 : 0;
}

static double impl_get_duration() {
    if (!mod) return 0;
    return safe_openmpt_duration(mod);
}

static int impl_get_sample_rate() {
    return 48000;
}

static int impl_get_channels() {
    return 2;
}

static int impl_render(float *out, int frames) {
    if (!mod) return 0;

    return (int)safe_openmpt_render_interleaved(
        mod, 48000, (size_t)frames, out
    );
}

static void impl_destroy() {
    if (mod) {
        safe_openmpt_destroy(mod);
        mod = NULL;
    }
}

static void *context_create(const char *format, const unsigned char *data, int len) {
    (void)format;
    return safe_openmpt_create(data, (size_t)len);
}

static double context_get_duration(void *context) {
    return context ? safe_openmpt_duration((openmpt_module *)context) : 0.0;
}

static int context_get_sample_rate(void *context) { (void)context; return 48000; }
static int context_get_channels(void *context) { (void)context; return 2; }

static int context_render(void *context, float *out, int frames) {
    if (!context || !out || frames <= 0) return 0;
    return (int)safe_openmpt_render_interleaved(
        (openmpt_module *)context, 48000, (size_t)frames, out
    );
}

static void context_destroy(void *context) {
    if (context) safe_openmpt_destroy((openmpt_module *)context);
}

static int context_get_subsong_count(void *context) {
    return context ? (int)openmpt_module_get_num_subsongs((openmpt_module *)context) : -1;
}

static int context_select_subsong(void *context, int subsong) {
    if (!context || subsong < 0 || subsong >= context_get_subsong_count(context)) return -1;
    return openmpt_module_select_subsong((openmpt_module *)context, subsong) ? 0 : -1;
}

static int context_seek_ms(void *context, int position_ms) {
    if (!context || position_ms < 0) return -1;
    return openmpt_module_set_position_seconds((openmpt_module *)context, position_ms / 1000.0) >= 0 ? 0 : -1;
}

// ── 导出 Decoder 实例 ──
const Decoder decoder_openmpt = {
    "libopenmpt",
    impl_load,
    impl_get_duration,
    impl_get_sample_rate,
    impl_get_channels,
    impl_render,
    impl_destroy,
    context_create,
    context_get_duration,
    context_get_sample_rate,
    context_get_channels,
    context_render,
    context_destroy,
    context_get_subsong_count,
    context_select_subsong,
    context_seek_ms
};
