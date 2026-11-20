/**
 * ym6_impl.c — YM6 (Atari ST YM2149) decoder for WASM
 *
 * YM6 format: 16× YM2149 register bytes per frame at 50fps
 * Contains built-in YM2149 (AY-3-8910 clone) emulation.
 * Also handles level-0 LHa/LH5-compressed YM files — no external lha needed.
 *
 * YM register layout:
 *  R0,R1   Channel A tone period  (12-bit)
 *  R2,R3   Channel B tone period
 *  R4,R5   Channel C tone period
 *  R6      Noise period           (5-bit)
 *  R7      Mixer control
 *  R8-R10  Channel A/B/C volume
 *  R11,R12 Envelope period        (16-bit)
 *  R13     Envelope shape         (4-bit)
 */
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <limits.h>
#include "audio_engine.h"

// ── LHa 解压 ───────────────────────────────────────────────
// YM 文件可能被 LHa 压缩。WASM 构建需要内置解压器。
int ym_lzh_decompress(const unsigned char *compressed, int compressed_len,
                      unsigned char *decompressed, int decompressed_len);

static uint32_t read_le32(const unsigned char *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static int ym6_decompress_lha(const unsigned char **data_ptr, int *len_ptr) {
    if (!data_ptr || !*data_ptr || !len_ptr || *len_ptr < 4) return -1;
    const unsigned char *data = *data_ptr;
    int len = *len_ptr;
    if (data[0] == 'Y' && data[1] == 'M') return 0;
    if (len < 22 || memcmp(data + 2, "-lh5-", 5) != 0) return 0;

    int header_size = data[0] + 2;
    if (header_size > len || data[20] != 0) return -1; // level-0 archive
    uint32_t compressed_size = read_le32(data + 7);
    uint32_t original_size = read_le32(data + 11);
    if (original_size == 0 || original_size > 64U * 1024U * 1024U ||
        compressed_size > (uint32_t)(len - header_size)) return -1;

    unsigned char *output = (unsigned char *)malloc(original_size);
    if (!output) return -1;
    int decoded = ym_lzh_decompress(data + header_size, (int)compressed_size,
                                    output, (int)original_size);
    if (decoded != (int)original_size) { free(output); return -1; }
    *data_ptr = output;
    *len_ptr = decoded;
    return 1;
}

// ── YM2149 emulator ──
#define YM_DEFAULT_CLOCK 2000000U
#define YM_DEFAULT_FPS 50

typedef struct {
    // Registers
    uint8_t regs[16];

    // Tone generators (3 channels)
    uint32_t tone_period[3];
    double   tone_phase[3];
    int      tone_level[3];

    // Noise generator
    uint32_t noise_period;
    double   noise_phase;
    uint32_t noise_lfsr;

    // Envelope generator
    uint32_t env_period;
    double   env_step_phase;
    int      env_phase;       // 0-31
    int      env_direction;   // +1 or -1
    int      env_continue : 1;
    int      env_attack   : 1;
    int      env_alternate : 1;
    int      env_hold     : 1;

    // Frame data (YM6 register dumps) — owned copy
    uint8_t *frame_data;
    int   total_frames;
    int   current_frame;
    double frame_remainder;      // fractional sample accumulation
    int   sample_rate;
    int   frame_rate;
    uint32_t master_clock;

    // Decompressed LHa data (if file was LHa-compressed), freed on destroy
    uint8_t *raw_data;
    int   raw_data_len;
} ym6_t;


// ── Reset emulator ──
static void ym6_reset(ym6_t *y) {
    memset(y, 0, sizeof(ym6_t));
    for (int i = 0; i < 3; i++) {
        y->tone_level[i] = 1;
        y->tone_period[i] = 1;
    }
    y->noise_period = 1;
    y->noise_lfsr = 1;
    y->env_period = 1;
    y->env_direction = -1;
    y->sample_rate = 44100;
    y->frame_rate = YM_DEFAULT_FPS;
    y->master_clock = YM_DEFAULT_CLOCK;
}

// ── Write YM register ──
static void ym6_write_reg(ym6_t *y, int r, uint8_t v) {
    if (r < 0 || r > 15) return;
    y->regs[r] = v;
    switch (r) {
    case 0: case 1:
        y->tone_period[0] = ((y->regs[1] & 0x0F) << 8) | y->regs[0];
        if (!y->tone_period[0]) y->tone_period[0] = 1;
        break;
    case 2: case 3:
        y->tone_period[1] = ((y->regs[3] & 0x0F) << 8) | y->regs[2];
        if (!y->tone_period[1]) y->tone_period[1] = 1;
        break;
    case 4: case 5:
        y->tone_period[2] = ((y->regs[5] & 0x0F) << 8) | y->regs[4];
        if (!y->tone_period[2]) y->tone_period[2] = 1;
        break;
    case 6:  y->noise_period = (v & 0x1F); if (!y->noise_period) y->noise_period = 1; break;
    case 11: case 12:
        y->env_period = ((uint32_t)y->regs[12] << 8) | y->regs[11];
        if (!y->env_period) y->env_period = 1;
        break;
    case 13:
        y->env_continue = (v >> 3) & 1;
        y->env_attack   = (v >> 2) & 1;
        y->env_alternate= (v >> 1) & 1;
        y->env_hold     = (v >> 0) & 1;
        y->env_phase    = y->env_attack ? 0 : 31;
        y->env_direction = y->env_attack ? 1 : -1;
        y->env_step_phase = 0;
        break;
    }
}

// ── Write all 16 registers from a frame ──
static void ym6_write_frame(ym6_t *y, const uint8_t *frame) {
    for (int r = 0; r < 16; r++) {
        if (r == 13 && frame[r] == 0xFF) continue; // YM convention: do not retrigger envelope
        if (frame[r] != y->regs[r]) {
            ym6_write_reg(y, r, frame[r]);
        }
    }
}

// ── Parse YM3/4/5/6 header ──
// Returns duration in seconds, or 0 on failure.
// Frame data for YM4/5/6 is always the LAST total_frames*16 bytes of the file.
// YM3 uses 4-byte frames at a fixed offset after the header.
typedef struct {
    int total_frames;
    int frame_offset;
    int frame_rate;
    uint32_t master_clock;
    int interleaved;
    int register_count;
} ym_header_t;

static uint16_t read_be16(const uint8_t *p) {
    return (uint16_t)(((uint16_t)p[0] << 8) | p[1]);
}

static uint32_t read_be32(const uint8_t *p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) |
           ((uint32_t)p[2] << 8) | p[3];
}

static int skip_c_string(const uint8_t *data, int len, int *offset) {
    while (*offset < len && data[*offset] != 0) (*offset)++;
    if (*offset >= len) return 0;
    (*offset)++;
    return 1;
}

static double ym6_parse_header(const uint8_t *data, int len, ym_header_t *h) {
    memset(h, 0, sizeof(*h));
    if (len < 4) return 0;
    const uint8_t m0 = data[0], m1 = data[1], m2 = data[2], m3 = data[3];
    if (m0 != 'Y' || m1 != 'M') return 0;
    if (m2 < '3' || m2 > '6') return 0;  // Only YM3-6
    if (m3 != '!' && m3 != ' ') return 0;

    int total_frames = 0;
    int fps = YM_DEFAULT_FPS;

    if (m2 == '3') {
        // YM3 stores 14 interleaved register streams and has no frame count.
        if ((len - 4) <= 0 || (len - 4) % 14 != 0) return 0;
        total_frames = (len - 4) / 14;
        h->total_frames = total_frames;
        h->frame_offset = 4;
        h->frame_rate = YM_DEFAULT_FPS;
        h->master_clock = YM_DEFAULT_CLOCK;
        h->interleaved = 1;
        h->register_count = 14;
        return (double)total_frames / fps;
    }

    if (m2 == '4') return 0; // obsolete layout is not safely distinguishable here

    // YM5/6: full metadata header
    //   YM5: magic(4) + name(8) + author(8) + frames(4) + attrs(4) + fps(2) = 30
    //        + extra text (variable, no size field)
    //   YM6: same + loop(4) = 34, + extra text (variable)
    // Frame data is the LAST total_frames*16 bytes of the file
    if (len < 34 || memcmp(data + 4, "LeOnArD!", 8) != 0) return 0;
    total_frames = (int)read_be32(data + 12);
    if (total_frames <= 0) return 0;

    uint32_t attributes = read_be32(data + 16);
    uint16_t digidrums = read_be16(data + 20);
    uint32_t master_clock = read_be32(data + 22);
    fps = read_be16(data + 26);
    if (fps <= 0) fps = 50;
    if (master_clock == 0) master_clock = YM_DEFAULT_CLOCK;

    uint16_t extra_size = read_be16(data + 32);
    int offset = 34;
    if (extra_size > (uint16_t)(len - offset)) return 0;
    offset += extra_size;
    for (uint16_t i = 0; i < digidrums; i++) {
        if (offset > len - 4) return 0;
        uint32_t size = read_be32(data + offset);
        offset += 4;
        if (size > (uint32_t)(len - offset)) return 0;
        offset += (int)size;
    }
    if (!skip_c_string(data, len, &offset) ||
        !skip_c_string(data, len, &offset) ||
        !skip_c_string(data, len, &offset)) return 0;
    if ((uint64_t)total_frames * 16U > (uint64_t)(len - offset)) return 0;

    h->total_frames = total_frames;
    h->frame_offset = offset;
    h->frame_rate = fps;
    h->master_clock = master_clock;
    h->interleaved = (attributes & 1U) != 0;
    h->register_count = 16;
    return (double)total_frames / fps;
}

// ── Render audio ──
// Processes YM6 frames and produces interleaved float PCM
static int ym6_render(ym6_t *y, float *out, int frames) {
    if (!y || !y->frame_data) return 0;

    // Calculate how many samples per frame at this sample rate
    double samples_per_frame = (double)y->sample_rate / y->frame_rate;
    int rendered = 0;

    while (rendered < frames) {
        // If we need to advance to the next frame
        if (y->frame_remainder < 1.0 && y->current_frame < y->total_frames) {
            // Write the next frame's register values
            const uint8_t *frame = y->frame_data + y->current_frame * 16;
            ym6_write_frame(y, frame);
            y->current_frame++;
            y->frame_remainder += samples_per_frame;
        }

        if (y->current_frame >= y->total_frames && y->frame_remainder < 1.0) {
            break; // No more data
        }

        // How many samples to render based on current frame
        int todo = frames - rendered;
        int chunk = (int)y->frame_remainder;
        if (chunk > todo) chunk = todo;

        if (chunk <= 0) {
            // Shouldn't happen, but safety
            break;
        }

        // Render chunk samples with current register state
        for (int s = 0; s < chunk; s++, rendered++) {
            // Toggle rate is master_clock/(8*period), giving a square-wave
            // frequency of master_clock/(16*period).
            for (int ch = 0; ch < 3; ch++) {
                y->tone_phase[ch] += (double)y->master_clock /
                    (8.0 * y->tone_period[ch] * y->sample_rate);
                while (y->tone_phase[ch] >= 1.0) {
                    y->tone_phase[ch] -= 1.0;
                    y->tone_level[ch] = !y->tone_level[ch];
                }
            }

            // Advance noise generator (17-bit LFSR)
            y->noise_phase += (double)y->master_clock /
                (16.0 * y->noise_period * y->sample_rate);
            while (y->noise_phase >= 1.0) {
                y->noise_phase -= 1.0;
                int fb = ((y->noise_lfsr & 1) ^ ((y->noise_lfsr >> 3) & 1)) ^ 1;
                y->noise_lfsr = (y->noise_lfsr >> 1) | (fb << 16);
            }

            // Advance envelope
            y->env_step_phase += (double)y->master_clock /
                (256.0 * y->env_period * y->sample_rate);
            while (y->env_step_phase >= 1.0) {
                y->env_step_phase -= 1.0;
                y->env_phase += y->env_direction;
                if (y->env_phase < 0 || y->env_phase > 31) {
                    if (y->env_phase < 0) y->env_phase = 0;
                    if (y->env_phase > 31) y->env_phase = 31;
                    if (!y->env_continue) {
                        y->env_direction = 0;
                    } else if (y->env_alternate) {
                        y->env_direction = -y->env_direction;
                        y->env_phase += y->env_direction;
                    } else {
                        y->env_phase = y->env_attack ? 0 : 31;
                    }
                    if (y->env_hold) y->env_direction = 0;
                }
            }

            // Mix 3 channels
            int mixer = y->regs[7];
            double left = 0, right = 0;

            for (int ch = 0; ch < 3; ch++) {
                int vol_byte = y->regs[8 + ch];
                int use_env = (vol_byte >> 4) & 1;
                int fixed_vol = vol_byte & 0x0F;
                double ampl;
                if (use_env)
                    ampl = (y->env_phase & 0x1F) / 31.0;
                else
                    ampl = fixed_vol / 15.0;

                int tone_disabled = (mixer >> ch) & 1;
                int noise_disabled = (mixer >> (ch + 3)) & 1;
                int gate = (tone_disabled || y->tone_level[ch]) &&
                           (noise_disabled || (y->noise_lfsr & 1));
                double val = (gate ? 1.0 : -1.0) * ampl * 0.4;

                // Pan: ch0→L, ch1→center, ch2→R
                if (ch == 0)      { left += val; }
                else if (ch == 1) { left += val * 0.7; right += val * 0.7; }
                else              { right += val; }
            }

            out[rendered * 2 + 0] = (float)left;
            out[rendered * 2 + 1] = (float)right;
        }

        y->frame_remainder -= chunk;
    }

    return rendered;
}

// ── Decoder interface ──
static void context_destroy(void *opaque);

static void *context_create(const char *format, const unsigned char *data, int len) {
    (void)format;
    // 1. Try LHa decompression first (YM files may be LHa-compressed)
    const unsigned char *ym_data = data;
    int ym_len = len;
    int lha_used = ym6_decompress_lha(&ym_data, &ym_len);
    if (lha_used < 0) return NULL;

    // 2. Parse YM6/5/4/3 header from (possibly decompressed) data
    ym_header_t header;
    double duration = ym6_parse_header(ym_data, ym_len, &header);
    if (duration <= 0 || header.total_frames <= 0) {
        if (lha_used) free((void *)ym_data);
        return NULL;
    }

    ym6_t *ym = (ym6_t*)calloc(1, sizeof(ym6_t));
    if (!ym) {
        if (lha_used) free((void *)ym_data);
        return NULL;
    }

    ym6_reset(ym);

    // 3. Store LHa decompressed data (if any)
    if (lha_used) {
        ym->raw_data = (uint8_t *)ym_data;
        ym->raw_data_len = ym_len;
    }

    // 4. Normalize register-major/interleaved input to frame-major storage.
    if (header.total_frames > INT_MAX / 16) { context_destroy(ym); return NULL; }
    int frames_size = header.total_frames * 16;
    ym->frame_data = (uint8_t*)malloc(frames_size);
    if (!ym->frame_data) { context_destroy(ym); return NULL; }
    memset(ym->frame_data, 0, (size_t)frames_size);
    for (int frame = 0; frame < header.total_frames; frame++) {
        for (int reg = 0; reg < header.register_count; reg++) {
            int source = header.interleaved
                ? reg * header.total_frames + frame
                : frame * header.register_count + reg;
            ym->frame_data[frame * 16 + reg] = ym_data[header.frame_offset + source];
        }
        if (header.register_count < 16) ym->frame_data[frame * 16 + 13] = 0xFF;
    }

    ym->total_frames = header.total_frames;
    ym->current_frame = 0;
    ym->frame_remainder = 0;
    ym->sample_rate = 44100;
    ym->frame_rate = header.frame_rate;
    ym->master_clock = header.master_clock;

    // Apply the first frame immediately
    if (header.total_frames > 0) {
        ym6_write_frame(ym, ym->frame_data);
        ym->current_frame = 1;
        ym->frame_remainder = (double)ym->sample_rate / ym->frame_rate;
    } else {
        free(ym->frame_data);
        free(ym);
        return NULL;
    }

    return ym;
}

static double context_get_duration(void *opaque) { ym6_t *ym = (ym6_t *)opaque; return ym ? (double)ym->total_frames / ym->frame_rate : 0; }
static int context_get_sample_rate(void *opaque) { return opaque ? 44100 : 0; }
static int context_get_channels(void *opaque) { return opaque ? 2 : 0; }

static int context_render(void *opaque, float *out, int frames) {
    ym6_t *ym = (ym6_t *)opaque;
    return ym ? ym6_render(ym, out, frames) : 0;
}

static void context_destroy(void *opaque) {
    ym6_t *ym = (ym6_t *)opaque;
    if (ym) {
        if (ym->frame_data) free(ym->frame_data);
        if (ym->raw_data)   free(ym->raw_data);
        free(ym);
    }
}

static ym6_t *legacy;
static int impl_load(const unsigned char *data, int len) { context_destroy(legacy); legacy = context_create(NULL, data, len); return legacy != NULL; }
static double impl_get_duration(void) { return context_get_duration(legacy); }
static int impl_get_sample_rate(void) { return context_get_sample_rate(legacy); }
static int impl_get_channels(void) { return context_get_channels(legacy); }
static int impl_render(float *out, int frames) { return context_render(legacy, out, frames); }
static void impl_destroy(void) { context_destroy(legacy); legacy = NULL; }

// ── Export decoder ──
const Decoder decoder_ym6 = {
    "ym6",
    impl_load,
    impl_get_duration,
    impl_get_sample_rate,
    impl_get_channels,
    impl_render,
    impl_destroy,
    context_create, context_get_duration, context_get_sample_rate,
    context_get_channels, context_render, context_destroy
};
