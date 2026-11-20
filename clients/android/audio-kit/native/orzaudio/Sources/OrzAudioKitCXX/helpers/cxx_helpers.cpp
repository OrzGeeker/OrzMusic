// C++ 辅助层 — 包裹 C++ 库调用，异常安全
// 条件编译：各解码器包装仅在对应库头文件可用时编译

extern "C" {

// ── libopenmpt 安全包装 ──
#ifdef ORZ_HAVE_OPENMPT
#include <libopenmpt/libopenmpt.h>

openmpt_module* safe_openmpt_create(const unsigned char* data, size_t len) {
    try {
        return openmpt_module_create_from_memory2(data, len,
            NULL, NULL, NULL, NULL, NULL, NULL, NULL);
    } catch (...) { return NULL; }
}

double safe_openmpt_duration(openmpt_module* mod) {
    try { return openmpt_module_get_duration_seconds(mod); }
    catch (...) { return 0; }
}

size_t safe_openmpt_render_interleaved(openmpt_module* mod, int rate,
                                       size_t frames, float* out) {
    try {
        return openmpt_module_read_interleaved_float_stereo(
            mod, rate, frames, out
        );
    }
    catch (...) { return 0; }
}

void safe_openmpt_destroy(openmpt_module* mod) {
    try { openmpt_module_destroy(mod); }
    catch (...) {}
}
#endif // ORZ_HAVE_OPENMPT

// ── GME 安全包装 ──
#ifdef ORZ_HAVE_GME
#include <gme/gme.h>

gme_err_t safe_gme_open_data(const unsigned char* data, int len,
                             Music_Emu** emu, int rate) {
    try { return gme_open_data(data, len, emu, rate); }
    catch (...) { return "gme_open_data exception"; }
}

gme_err_t safe_gme_start_track(Music_Emu* emu, int track) {
    try { return gme_start_track(emu, track); }
    catch (...) { return "gme_start_track exception"; }
}

gme_err_t safe_gme_track_info(Music_Emu* emu, gme_info_t** info, int track) {
    try { return gme_track_info(emu, info, track); }
    catch (...) { return "gme_track_info exception"; }
}

void safe_gme_free_info(gme_info_t* info) {
    try { gme_free_info(info); }
    catch (...) {}
}

gme_err_t safe_gme_play(Music_Emu* emu, int count, short* buf) {
    try { return gme_play(emu, count, buf); }
    catch (...) { return "gme_play exception"; }
}

void safe_gme_delete(Music_Emu* emu) {
    try { gme_delete(emu); }
    catch (...) {}
}
#endif // ORZ_HAVE_GME

} // extern "C"
