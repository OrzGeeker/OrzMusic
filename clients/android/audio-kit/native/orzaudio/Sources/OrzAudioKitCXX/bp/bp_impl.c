#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "audio_engine.h"

#define BP_RATE 44100
#define BP_CHANNELS 4
#define BP_INSTRUMENTS 15
#define BP_HEADER 512
#define BP_TICK_FRAMES (BP_RATE / 50)

typedef struct {
    const int8_t *data;
    uint32_t length;
    uint32_t loop_start;
    uint32_t loop_length;
    uint8_t volume;
    uint8_t synthetic;
    uint8_t adsr_mode, adsr_table, adsr_delay;
    uint8_t lfo_mode, lfo_table, lfo_depth, lfo_delay;
    uint8_t eg_mode, eg_table, eg_delay;
    uint16_t adsr_length, lfo_length, eg_length;
} BPInstrument;

typedef struct {
    const BPInstrument *instrument;
    double position;
    double increment;
    uint16_t period;
    uint8_t note, instrument_number, volume;
    int8_t slide;
    uint8_t arpeggio, auto_arpeggio;
    uint8_t active, looping;
    uint16_t adsr_position, lfo_position, eg_position;
    uint8_t adsr_count, lfo_count, eg_count, base_volume, eg_value;
    uint8_t adsr_active, lfo_active, eg_active;
    int8_t waveform[256];
} BPVoice;

typedef struct {
    uint8_t *owned;
    size_t size;
    uint16_t steps, highest_pattern, step, row;
    uint8_t table_count, speed, tick;
    uint8_t repeat_count;
    uint32_t pattern_offset, table_offset;
    uint64_t rendered_frames, duration_frames;
    BPInstrument instruments[BP_INSTRUMENTS];
    BPVoice voices[BP_CHANNELS];
} BPContext;

static uint16_t be16(const uint8_t *p) { return (uint16_t)((p[0] << 8) | p[1]); }
static int range_ok(size_t offset, size_t count, size_t size) { return offset <= size && count <= size - offset; }

static uint16_t note_period(int note) {
    static const uint16_t periods[60] = {
        904,856,808,760,720,680,640,604,572,540,508,480,
        452,428,404,380,360,340,320,302,286,270,254,240,
        226,214,202,190,180,170,160,151,143,135,127,120,
        113,107,101,95,90,85,80,76,72,68,64,60,
        57,53,50,47,45,42,40,38,36,34,32,30
    };
    if (note < 1) note = 1;
    if (note > 60) note = 60;
    return periods[note - 1];
}

static void update_increment(BPVoice *v, int semitone) {
    int note = (int)v->note + semitone;
    v->period = note_period(note);
    v->increment = 3546895.0 / ((double)v->period * BP_RATE);
}

static void destroy_context(void *opaque);

static void *create_context(const char *format, const unsigned char *input, int len) {
    (void)format;
    if (!input || len < BP_HEADER) return NULL;
    if (memcmp(input + 26, "V.2", 3)) return NULL;

    BPContext *ctx = (BPContext *)calloc(1, sizeof(*ctx));
    if (!ctx) return NULL;
    ctx->owned = (uint8_t *)malloc((size_t)len);
    if (!ctx->owned) { free(ctx); return NULL; }
    memcpy(ctx->owned, input, (size_t)len); ctx->size = (size_t)len;
    ctx->steps = be16(ctx->owned + 30); ctx->table_count = ctx->owned[29];
    ctx->speed = 6;
    if (!ctx->steps || ctx->steps > 4096 || ctx->table_count > 64 ||
        !range_ok(BP_HEADER, (size_t)ctx->steps * 16, ctx->size)) { destroy_context(ctx); return NULL; }

    for (uint32_t i = 0; i < (uint32_t)ctx->steps * 4; i++) {
        uint16_t pattern = be16(ctx->owned + BP_HEADER + i * 4);
        if (!pattern || pattern > 4096) { destroy_context(ctx); return NULL; }
        if (pattern > ctx->highest_pattern) ctx->highest_pattern = pattern;
    }
    ctx->pattern_offset = BP_HEADER + (uint32_t)ctx->steps * 16;
    size_t pattern_bytes = (size_t)ctx->highest_pattern * 48;
    if (!range_ok(ctx->pattern_offset, pattern_bytes, ctx->size)) { destroy_context(ctx); return NULL; }
    ctx->table_offset = ctx->pattern_offset + (uint32_t)pattern_bytes;
    size_t sample_offset = (size_t)ctx->table_offset + (size_t)ctx->table_count * 64;
    if (sample_offset > ctx->size) { destroy_context(ctx); return NULL; }

    for (int i = 0; i < BP_INSTRUMENTS; i++) {
        const uint8_t *h = ctx->owned + 32 + i * 32;
        BPInstrument *ins = &ctx->instruments[i];
        ins->synthetic = h[0] == 0xff;
        /* The original player reads the low byte of the big-endian volume
           word: byte 25 for synthetic instruments, byte 31 for samples. */
        ins->volume = ins->synthetic ? h[25] : h[31];
        if (ins->volume > 64) ins->volume = 64;
        if (ins->synthetic) {
            uint8_t table = h[1];
            uint32_t bytes = (uint32_t)be16(h + 2) * 2;
            size_t waveform_offset = (size_t)ctx->table_offset + (size_t)table * 64;
            size_t table_end = (size_t)ctx->table_offset + (size_t)ctx->table_count * 64;
            if (table >= ctx->table_count || !bytes || bytes > sizeof(((BPVoice *)0)->waveform) ||
                waveform_offset > table_end || bytes > table_end - waveform_offset) {
                destroy_context(ctx); return NULL;
            }
            ins->data = (const int8_t *)(ctx->owned + ctx->table_offset + table * 64);
            ins->length = bytes; ins->loop_length = bytes;
            ins->adsr_mode = h[4]; ins->adsr_table = h[5]; ins->adsr_length = be16(h + 6); ins->adsr_delay = h[8];
            ins->lfo_mode = h[9]; ins->lfo_table = h[10]; ins->lfo_depth = h[11];
            ins->lfo_length = be16(h + 12); ins->lfo_delay = h[16];
            ins->eg_mode = h[17]; ins->eg_table = h[18]; ins->eg_length = be16(h + 20); ins->eg_delay = h[24];
            if ((ins->adsr_mode && (ins->adsr_table >= ctx->table_count || !ins->adsr_length ||
                    ins->adsr_length > (ctx->table_count - ins->adsr_table) * 64)) ||
                (ins->lfo_mode && (ins->lfo_table >= ctx->table_count || !ins->lfo_length ||
                    ins->lfo_length > (ctx->table_count - ins->lfo_table) * 64)) ||
                (ins->eg_mode && (ins->eg_table >= ctx->table_count || !ins->eg_length ||
                    ins->eg_length > (ctx->table_count - ins->eg_table) * 64))) {
                destroy_context(ctx); return NULL;
            }
        } else {
            uint32_t bytes = (uint32_t)be16(h + 24) * 2;
            uint32_t repeat = be16(h + 26), repeat_bytes = (uint32_t)be16(h + 28) * 2;
            if (!range_ok(sample_offset, bytes, ctx->size) ||
                (bytes != 0 && (repeat > bytes || repeat_bytes > bytes - repeat))) {
                destroy_context(ctx); return NULL;
            }
            ins->data = (const int8_t *)(ctx->owned + sample_offset); ins->length = bytes;
            ins->loop_start = repeat;
            ins->loop_length = bytes == 0 ? 0 : repeat_bytes;
            sample_offset += bytes;
        }
    }
    ctx->duration_frames = (uint64_t)ctx->steps * 16 * ctx->speed * BP_TICK_FRAMES;
    return ctx;
}

static void process_row(BPContext *ctx) {
    for (int ch = 0; ch < BP_CHANNELS; ch++) {
        size_t seq = BP_HEADER + (size_t)ctx->step * 16 + ch * 4;
        uint16_t pattern = be16(ctx->owned + seq);
        int8_t instrument_transpose = (int8_t)ctx->owned[seq + 2];
        int8_t note_transpose = (int8_t)ctx->owned[seq + 3];
        size_t event = ctx->pattern_offset + (size_t)(pattern - 1) * 48 + ctx->row * 3;
        uint8_t note = ctx->owned[event], packed = ctx->owned[event + 1], param = ctx->owned[event + 2];
        uint8_t instrument = packed >> 4, effect = packed & 15;
        BPVoice *v = &ctx->voices[ch];
        if (note) {
            int n = note + note_transpose;
            if (n < 1) n = 1; if (n > 60) n = 60;
            v->note = (uint8_t)n;
            v->slide = 0; v->auto_arpeggio = 0;
            if (instrument) {
                int ins = instrument + instrument_transpose;
                if (ins >= 1 && ins <= BP_INSTRUMENTS) {
                    v->instrument_number = (uint8_t)ins; v->instrument = &ctx->instruments[ins - 1];
                    v->volume = v->instrument->volume; v->position = 0;
                    v->active = v->instrument->length > 0; v->looping = 0;
                    v->base_volume = v->volume;
                    v->adsr_position = v->lfo_position = v->eg_position = 0;
                    v->adsr_count = v->lfo_count = v->eg_count = 1;
                    v->adsr_active = v->instrument->adsr_mode;
                    v->lfo_active = v->instrument->lfo_mode;
                    v->eg_active = v->instrument->eg_mode;
                    v->eg_value = 0;
                    if (v->instrument->synthetic) {
                        memcpy(v->waveform, v->instrument->data, v->instrument->length);
                    }
                }
            }
            update_increment(v, 0);
        }
        switch (effect) {
            case 0: v->arpeggio = param; break;
            case 1: v->volume = param > 64 ? 64 : param; break;
            case 2: if (param) { ctx->speed = param; ctx->duration_frames = (uint64_t)ctx->steps * 16 * ctx->speed * BP_TICK_FRAMES; } break;
            case 4: { int p = (int)v->period - param; v->period = (uint16_t)(p < 57 ? 57 : p); v->increment = 3546895.0 / (v->period * (double)BP_RATE); break; }
            case 5: { int p = (int)v->period + param; v->period = (uint16_t)(p > 6848 ? 6848 : p); v->increment = 3546895.0 / (v->period * (double)BP_RATE); break; }
            case 6: ctx->repeat_count = param; break;
            case 7: if (ctx->repeat_count > 1) { ctx->repeat_count--; if (param < ctx->steps) ctx->step = param; } break;
            case 8: v->slide = (int8_t)param; break;
            case 9: v->auto_arpeggio = param; break;
            default: break;
        }
    }
}

static void process_tick(BPContext *ctx) {
    if (ctx->tick == 0) process_row(ctx);
    for (int ch = 0; ch < BP_CHANNELS; ch++) {
        BPVoice *v = &ctx->voices[ch];
        const BPInstrument *ins = v->instrument;
        if (v->active && ins && ins->synthetic) {
            if (v->adsr_active && --v->adsr_count == 0) {
                const int8_t *table = (const int8_t *)(ctx->owned + ctx->table_offset + ins->adsr_table * 64);
                int level = ((int)table[v->adsr_position] + 128) >> 2;
                v->volume = (uint8_t)(v->base_volume * level / 64);
                if (++v->adsr_position >= ins->adsr_length) {
                    v->adsr_position = 0; if (v->adsr_active == 1) v->adsr_active = 0;
                }
                v->adsr_count = ins->adsr_delay ? ins->adsr_delay : 1;
            }
            if (v->lfo_active && --v->lfo_count == 0) {
                const int8_t *table = (const int8_t *)(ctx->owned + ctx->table_offset + ins->lfo_table * 64);
                int period = (int)v->period + (ins->lfo_depth ? table[v->lfo_position] / ins->lfo_depth : table[v->lfo_position]);
                if (period < 57) period = 57; if (period > 6848) period = 6848;
                v->increment = 3546895.0 / (period * (double)BP_RATE);
                if (++v->lfo_position >= ins->lfo_length) v->lfo_position = 0;
                v->lfo_count = ins->lfo_delay ? ins->lfo_delay : 1;
            }
            if (v->eg_active && --v->eg_count == 0) {
                const int8_t *table = (const int8_t *)(ctx->owned + ctx->table_offset + ins->eg_table * 64);
                uint8_t next = (uint8_t)(((int)table[v->eg_position] + 128) >> 3);
                uint8_t limit = next > ins->length ? (uint8_t)ins->length : next;
                for (uint8_t i = 0; i < ins->length; i++) {
                    int8_t original = ins->data[i];
                    v->waveform[i] = i < limit ? (int8_t)-original : original;
                }
                v->eg_value = next;
                if (++v->eg_position >= ins->eg_length) v->eg_position = 0;
                v->eg_count = ins->eg_delay ? ins->eg_delay : 1;
            }
        }
        if (v->slide) { int p = (int)v->period + v->slide; if (p < 57) p = 57; if (p > 6848) p = 6848; v->period = (uint16_t)p; v->increment = 3546895.0 / (p * (double)BP_RATE); }
        if (v->arpeggio || v->auto_arpeggio) {
            uint8_t arp = (uint8_t)(v->arpeggio + v->auto_arpeggio);
            int phase = ctx->tick % 3, semi = phase == 1 ? (arp >> 4) : phase == 2 ? (arp & 15) : 0;
            int shifted = note_period((int)v->note + semi);
            int base = note_period(v->note);
            int period = shifted + (int)v->period - base;
            if (period < 57) period = 57; if (period > 6848) period = 6848;
            v->increment = 3546895.0 / (period * (double)BP_RATE);
        }
    }
    if (++ctx->tick >= ctx->speed) {
        ctx->tick = 0;
        if (++ctx->row >= 16) { ctx->row = 0; if (++ctx->step >= ctx->steps) ctx->step = ctx->steps; }
    }
}

static int render_context(void *opaque, float *out, int frames) {
    BPContext *ctx = (BPContext *)opaque;
    if (!ctx || !out || frames <= 0 || ctx->step >= ctx->steps || ctx->rendered_frames >= ctx->duration_frames) return 0;
    int done = 0;
    while (done < frames && ctx->rendered_frames < ctx->duration_frames && ctx->step < ctx->steps) {
        if (ctx->rendered_frames % BP_TICK_FRAMES == 0) process_tick(ctx);
        float left = 0, right = 0;
        for (int ch = 0; ch < BP_CHANNELS; ch++) {
            BPVoice *v = &ctx->voices[ch];
            if (!v->active || !v->instrument) continue;
            uint32_t pos = (uint32_t)v->position;
            if (pos >= v->instrument->length) {
                if (v->instrument->loop_length > 1) {
                    pos = v->instrument->loop_start; v->position = pos; v->looping = 1;
                }
                else { v->active = 0; continue; }
            }
            const int8_t *sample = v->instrument->synthetic ? v->waveform : v->instrument->data;
            float value = sample[pos] / 128.0f * (v->volume / 64.0f) * 0.5f;
            if (ch == 0 || ch == 3) left += value; else right += value;
            v->position += v->increment;
            if (v->looping && v->instrument->loop_length > 1 &&
                v->position >= v->instrument->loop_start + v->instrument->loop_length)
                v->position = v->instrument->loop_start + fmod(v->position - v->instrument->loop_start, v->instrument->loop_length);
        }
        out[done * 2] = left; out[done * 2 + 1] = right;
        done++; ctx->rendered_frames++;
    }
    return done;
}

static double duration_context(void *opaque) { BPContext *c = (BPContext *)opaque; return c ? (double)c->duration_frames / BP_RATE : 0; }
static int rate_context(void *opaque) { return opaque ? BP_RATE : 0; }
static int channels_context(void *opaque) { return opaque ? 2 : 0; }
static void destroy_context(void *opaque) { BPContext *c = (BPContext *)opaque; if (c) { free(c->owned); free(c); } }

static BPContext *legacy;
static int load(const unsigned char *d, int n) { destroy_context(legacy); legacy = create_context("bp", d, n); return legacy != NULL; }
static double duration(void) { return duration_context(legacy); }
static int rate(void) { return rate_context(legacy); }
static int channels(void) { return channels_context(legacy); }
static int render(float *o, int n) { return render_context(legacy, o, n); }
static void destroy(void) { destroy_context(legacy); legacy = NULL; }

const Decoder decoder_bp = { "soundmon", load, duration, rate, channels, render, destroy,
    create_context, duration_context, rate_context, channels_context, render_context, destroy_context };
