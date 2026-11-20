#ifdef __EMSCRIPTEN__
#include <emscripten.h>
#else
#define EMSCRIPTEN_KEEPALIVE
#endif
#include <string.h>
#include "audio_engine.h"
#include "orz_audio_core.h"
#include "decoder_registry.h"

// ── 导入各解码器的实例 ──
extern const Decoder decoder_openmpt;

// Game Music Emu (NSF/SPC)
extern const Decoder decoder_gme;

// ASAP (SAP — Atari POKEY 格式)
extern const Decoder decoder_asap;

// libsidplayfp (SID)
extern const Decoder decoder_sidplayfp;

// libsc68 (Atari ST YM/Amiga)
extern const Decoder decoder_sc68;

// v2m-player (Farbrausch V2M)
extern const Decoder decoder_v2m;

// ym6 (Atari ST YM2149)
extern const Decoder decoder_ym6;

// uade (Amiga: ahx, thx)
extern const Decoder decoder_uade_ahx;

// adplug (AdLib OPL2/3: rad, d00, hsc)
extern const Decoder decoder_adplug;

// midi (wavetable synth)
extern const Decoder decoder_midi;
extern const Decoder decoder_bp;


// Static, immutable registry. It is safe during concurrent first use and has
// no arbitrary decoder count limit. Weak zero-valued decoder stubs allow lite
// builds to omit modules; accessors filter those entries by their context API.
#define BASE_CAPS (ORZ_CAP_RENDER | ORZ_CAP_DURATION | ORZ_CAP_PROBE | ORZ_CAP_CONCURRENT_INSTANCES)
#define NAV_CAPS (BASE_CAPS | ORZ_CAP_SEEK | ORZ_CAP_SUBSONG)
#define ALL_PLATFORMS 0x07u /* native, wasm, future mobile wrapper */
#define ENTRY(fmt, title, id, ver, group, caps, symbol) \
    { fmt, title, id, ver, group, caps, ALL_PLATFORMS, &symbol }

static const OrzDecoderDescriptor registry[] = {
#include "decoder_manifest.generated.inc"
};

static int available(const OrzDecoderDescriptor *entry) {
    const Decoder *decoder = entry->decoder;
    return decoder && decoder->create && decoder->context_get_duration &&
           decoder->context_get_sample_rate && decoder->context_get_channels &&
           decoder->context_render && decoder->context_destroy;
}

uint32_t orz_registry_count(void) {
    uint32_t count = 0;
    for (uint32_t i = 0; i < (uint32_t)(sizeof(registry) / sizeof(registry[0])); ++i)
        if (available(&registry[i])) ++count;
    return count;
}

const OrzDecoderDescriptor *orz_registry_at(uint32_t index) {
    uint32_t current = 0;
    for (uint32_t i = 0; i < (uint32_t)(sizeof(registry) / sizeof(registry[0])); ++i) {
        if (!available(&registry[i])) continue;
        if (current++ == index) return &registry[i];
    }
    return NULL;
}

const OrzDecoderDescriptor *orz_registry_find(const char *format) {
    if (!format) return NULL;
    for (uint32_t i = 0; i < (uint32_t)(sizeof(registry) / sizeof(registry[0])); ++i)
        if (available(&registry[i]) && strcmp(registry[i].format_id, format) == 0) return &registry[i];
    return NULL;
}

// Compatibility symbol retained for one release. Registration is now static.
__attribute__((used)) __attribute__((noinline)) void register_all(void) {}

// ── orz_audio_can_decode（保留，供 JS 调用）──
EMSCRIPTEN_KEEPALIVE
int orz_audio_can_decode(const char *extension) {
    return orz_can_decode(extension);
}
