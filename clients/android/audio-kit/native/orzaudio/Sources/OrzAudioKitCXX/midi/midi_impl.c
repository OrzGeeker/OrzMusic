/**
 * midi_impl.c — MIDI 解码器 + wavetable 合成器
 *
 * 实现 Decoder 接口。内置波形表合成器，无需 SoundFont。
 * 自包含，零外部依赖。
 */
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include "audio_engine.h"
#include "midi_file.h"

// ── 合成器参数 ──

#define MAX_VOICES      48
#define SAMPLE_RATE     44100
#define WAVE_TABLE_SIZE 256
#define RELEASE_MS      300

// ── 波形类型 ──

enum { WAVE_SINE, WAVE_SQUARE, WAVE_SAW, WAVE_TRI, WAVE_NOISE };

// ── 音色 → 波形映射 ──

static int program_to_wave(int program) {
    if (program < 8)   return WAVE_SINE;     // Piano
    if (program < 16)  return WAVE_SQUARE;    // Organ
    if (program < 24)  return WAVE_SAW;       // Guitar
    if (program < 32)  return WAVE_SAW;       // Bass
    if (program < 48)  return WAVE_TRI;       // Strings/Ensemble
    if (program < 80)  return WAVE_SINE;      // Reed/Pipe/Synth Lead
    if (program < 104) return WAVE_SQUARE;     // Synth Pad/Effects
    return WAVE_SINE;
}

// ── 音色状态 ──

typedef struct {
    int   active;        // 0=空闲
    int   note;          // MIDI 音符编号
    int   channel;       // MIDI 通道
    int   wave;          // 波形类型
    float phase;         // 相位 (0.0 - 1.0)
    float freq;          // 频率 (Hz)
    float velocity;      // 力度 (0.0 - 1.0)
    float env_level;     // 当前包络电平
    int   env_stage;     // 0=attack, 1=decay, 2=sustain, 3=release, 4=done
    int   stage_samples; // 当前阶段已过采样数
    int   samples_left;  // release 阶段剩余采样数
} Voice;

// ── 预计算波形表 ──

typedef struct {
    float wave_table[5][WAVE_TABLE_SIZE];
    MidiEvent *events;
    int event_count, event_index;
    Voice voices[MAX_VOICES];
    int total_samples;
    double current_tick, ticks_per_sample;
    int program_map[16];
    double current_tempo;
    int ticks_per_quarter;
    double duration_seconds;
    int silence_frames, remaining_note_on;
} MIDIContext;

static void init_wave_table(MIDIContext *ctx) {
    unsigned int noise = 0x6d2b79f5U;
    for (int i = 0; i < WAVE_TABLE_SIZE; i++) {
        float p = (float)i / WAVE_TABLE_SIZE;
        float angle = 2.0f * 3.14159265f * p;
        ctx->wave_table[WAVE_SINE][i]   = sinf(angle);
        ctx->wave_table[WAVE_SQUARE][i] = (p < 0.5f) ? 1.0f : -1.0f;
        ctx->wave_table[WAVE_SAW][i]    = 2.0f * p - 1.0f;
        ctx->wave_table[WAVE_TRI][i]    = (p < 0.5f) ? 4.0f * p - 1.0f : 3.0f - 4.0f * p;
        noise = noise * 1664525U + 1013904223U;
        ctx->wave_table[WAVE_NOISE][i] = (float)((noise >> 8) & 0xffff) / 32767.5f - 1.0f;
    }
}

// ── 音符频率 ──

static float note_to_freq(int note) {
    return 440.0f * powf(2.0f, (note - 69.0f) / 12.0f);
}

// ── 包络参数 ──

#define ATTACK_SAMPLES  441   // 10ms
#define DECAY_SAMPLES   2205  // 50ms
#define SUSTAIN_LEVEL   0.6f
#define RELEASE_SAMPLES 13230 // 300ms

// ── 包络计算 ──

static float calc_envelope(Voice *v) {
    switch (v->env_stage) {
        case 0: // Attack: 0 → 1
            v->stage_samples++;
            if (v->stage_samples >= ATTACK_SAMPLES) {
                v->env_stage = 1;
                v->stage_samples = 0;
                v->env_level = 1.0f;
                return 1.0f;
            }
            v->env_level = (float)v->stage_samples / ATTACK_SAMPLES;
            return v->env_level;

        case 1: // Decay: 1 → SUSTAIN_LEVEL
            v->stage_samples++;
            if (v->stage_samples >= DECAY_SAMPLES) {
                v->env_stage = 2;
                v->stage_samples = 0;
                v->env_level = SUSTAIN_LEVEL;
                return SUSTAIN_LEVEL;
            }
            v->env_level = 1.0f - (1.0f - SUSTAIN_LEVEL) *
                           (float)v->stage_samples / DECAY_SAMPLES;
            return v->env_level;

        case 2: // Sustain
            return SUSTAIN_LEVEL;

        case 3: // Release: current_level → 0
            v->samples_left--;
            if (v->samples_left <= 0) {
                v->env_stage = 4;
                v->env_level = 0.0f;
                return 0.0f;
            }
            v->env_level *= 0.9995f; // 指数衰减
            return v->env_level;

        default:
            return 0.0f;
    }
}

// ── 读波形表（线性插值）──

static float read_wave(MIDIContext *ctx, int wave, float phase) {
    float fpos = phase * WAVE_TABLE_SIZE;
    int idx = (int)fpos % WAVE_TABLE_SIZE;
    float frac = fpos - (int)fpos;
    int next = (idx + 1) % WAVE_TABLE_SIZE;
    return ctx->wave_table[wave][idx] * (1.0f - frac) + ctx->wave_table[wave][next] * frac;
}

// ── 触发音符 ──

static void note_on(MIDIContext *ctx, int note, float vel, int channel) {
    int wave = program_to_wave(ctx->program_map[channel]);

    // 查找空闲音色
    int slot = -1;
    for (int i = 0; i < MAX_VOICES; i++) {
        if (!ctx->voices[i].active) { slot = i; break; }
    }
    if (slot < 0) {
        // 没有空闲音色，替换最早 release 或 sustain 的
        for (int i = 0; i < MAX_VOICES; i++) {
            if (ctx->voices[i].env_stage >= 3) { slot = i; break; }
        }
    }
    if (slot < 0) return;

    Voice *v = &ctx->voices[slot];
    v->active       = 1;
    v->note         = note;
    v->channel      = channel;
    v->wave         = wave;
    v->phase        = 0.0f;
    v->freq         = note_to_freq(note);
    v->velocity     = vel;
    v->env_level    = 0.0f;
    v->env_stage    = 0;
    v->stage_samples = 0;
    v->samples_left = RELEASE_SAMPLES;
}

static void note_off(MIDIContext *ctx, int note, int channel) {
    for (int i = 0; i < MAX_VOICES; i++) {
        if (ctx->voices[i].active && ctx->voices[i].note == note && ctx->voices[i].channel == channel &&
            ctx->voices[i].env_stage < 3) {
            ctx->voices[i].env_stage = 3;
            ctx->voices[i].stage_samples = 0;
            break;
        }
    }
}

// ── Decoder 接口 ──

static void context_destroy(void *opaque);

static void *context_create(const char *format, const unsigned char *data, int len) {
    (void)format;
    MIDIContext *ctx = (MIDIContext *)calloc(1, sizeof(*ctx));
    if (!ctx) return NULL;
    ctx->current_tempo = 500000.0;
    ctx->ticks_per_quarter = 480;
    init_wave_table(ctx);

    MidiFile mf;
    if (!midi_load(&mf, data, len)) { free(ctx); return NULL; }

    ctx->ticks_per_quarter = mf.ticks_per_quarter;
    ctx->event_count = mf.count;
    ctx->events = (MidiEvent *)malloc(sizeof(MidiEvent) * (size_t)ctx->event_count);
    if (!ctx->events) { midi_free(&mf); free(ctx); return NULL; }
    memcpy(ctx->events, mf.events, sizeof(MidiEvent) * (size_t)ctx->event_count);
    midi_free(&mf);

    // 统计 NOTE_ON 事件数量
    for (int i = 0; i < ctx->event_count; i++) {
        if (ctx->events[i].type == MIDI_EV_NOTE_ON) ctx->remaining_note_on++;
    }

    double duration = 0.0, previous_tick = 0.0, tempo = 500000.0;
    for (int i = 0; i < ctx->event_count; i++) {
        if (ctx->events[i].tick > previous_tick) {
            duration += (ctx->events[i].tick - previous_tick) * tempo / 1000000.0 / ctx->ticks_per_quarter;
            previous_tick = ctx->events[i].tick;
        }
        if (ctx->events[i].type == MIDI_EV_TEMPO && ctx->events[i].tempo > 0) tempo = ctx->events[i].tempo;
    }
    ctx->duration_seconds = duration + (double)RELEASE_SAMPLES / SAMPLE_RATE;

    // 预计算 ticks_per_sample（使用默认 tempo，后续 tempo 变化时更新）
    double secs_per_tick = ctx->current_tempo / 1000000.0 / ctx->ticks_per_quarter;
    ctx->ticks_per_sample = 1.0 / (secs_per_tick * SAMPLE_RATE);

    return ctx;
}

static double context_get_duration(void *opaque) { return opaque ? ((MIDIContext *)opaque)->duration_seconds : 0; }
static int context_get_sample_rate(void *opaque) { return opaque ? SAMPLE_RATE : 0; }
static int context_get_channels(void *opaque) { return opaque ? 2 : 0; }

#define SILENCE_LIMIT_SAMPLES 22050  // 0.5s 沉默后提前退出

static int context_render(void *opaque, float *out, int frames) {
    MIDIContext *ctx = (MIDIContext *)opaque;
    if (!ctx || !ctx->events || ctx->event_count == 0) return 0;

    for (int s = 0; s < frames; s++) {
        // 当前采样对应的 tick 位置
        // 处理事件
        while (ctx->event_index < ctx->event_count &&
               ctx->events[ctx->event_index].tick <= ctx->current_tick) {
            MidiEvent *ev = &ctx->events[ctx->event_index];
            switch (ev->type) {
                case MIDI_EV_NOTE_ON:
                    note_on(ctx, ev->note, ev->velocity / 127.0f, ev->channel);
                    if (ctx->remaining_note_on > 0) ctx->remaining_note_on--;
                    break;
                case MIDI_EV_NOTE_OFF:
                    note_off(ctx, ev->note, ev->channel);
                    break;
                case MIDI_EV_PROGRAM:
                    if (ev->channel < 16) ctx->program_map[ev->channel] = ev->program;
                    break;
                case MIDI_EV_TEMPO: {
                    ctx->current_tempo = ev->tempo ? (double)ev->tempo : 500000.0;
                    double st = ctx->current_tempo / 1000000.0 / ctx->ticks_per_quarter;
                    ctx->ticks_per_sample = 1.0 / (st * SAMPLE_RATE);
                    break;
                }
                default: break;
            }
            ctx->event_index++;
        }

        // 混合所有活跃音色
        float left = 0.0f, right = 0.0f;
        int active_count = 0;

        for (int v = 0; v < MAX_VOICES; v++) {
            if (!ctx->voices[v].active) continue;
            Voice *voice = &ctx->voices[v];

            float env = calc_envelope(voice);

            if (voice->env_stage == 4) {
                voice->active = 0;
                continue;
            }

            // 读波形
            float sample = read_wave(ctx, voice->wave, voice->phase);
            sample *= env * voice->velocity * 0.4f;

            float pan = (voice->note - 36.0f) / 96.0f;
            if (pan < 0.0f) pan = 0.0f;
            if (pan > 1.0f) pan = 1.0f;
            left  += sample * (1.0f - pan);
            right += sample * pan;

            voice->phase += voice->freq / SAMPLE_RATE;
            if (voice->phase >= 1.0f) voice->phase -= 1.0f;

            active_count++;
        }

        if (active_count > 0) {
            float scale = 1.0f;
            out[s * 2 + 0] = left * scale;
            out[s * 2 + 1] = right * scale;
        } else {
            out[s * 2 + 0] = 0.0f;
            out[s * 2 + 1] = 0.0f;
        }

        ctx->total_samples++;
        ctx->current_tick += ctx->ticks_per_sample;
    }

    // 如果所有 NOTE_ON 事件已处理完且所有音色已释放，继续沉默超过阈值则提前结束
    // 注意：检测 remaining_note_on 而非 event_index >= event_count，
    // 因为可能有非 NOTE_ON 元事件（tempo/end_track）在 Note 之后很远的位置
    if (ctx->remaining_note_on <= 0) {
        int all_done = 1;
        for (int v = 0; v < MAX_VOICES; v++) {
            if (ctx->voices[v].active) { all_done = 0; break; }
        }
        if (all_done) {
            ctx->silence_frames += frames;
            if (ctx->silence_frames >= SILENCE_LIMIT_SAMPLES) {
                ctx->silence_frames = 0;
                return 0;  // 信号结束，JS 流式循环收到 0 后会 break
            }
        } else {
            ctx->silence_frames = 0;
        }
    }

    return frames;
}

static void context_destroy(void *opaque) {
    MIDIContext *ctx = (MIDIContext *)opaque;
    if (!ctx) return;
    free(ctx->events);
    free(ctx);
}

static MIDIContext *legacy;
static int impl_load(const unsigned char *data, int len) { context_destroy(legacy); legacy = context_create(NULL, data, len); return legacy != NULL; }
static double impl_get_duration(void) { return context_get_duration(legacy); }
static int impl_get_sample_rate(void) { return context_get_sample_rate(legacy); }
static int impl_get_channels(void) { return context_get_channels(legacy); }
static int impl_render(float *out, int frames) { return context_render(legacy, out, frames); }
static void impl_destroy(void) { context_destroy(legacy); legacy = NULL; }

// ── 导出 Decoder 实例 ──

const Decoder decoder_midi = {
    "midi-wavetable",
    impl_load,
    impl_get_duration,
    impl_get_sample_rate,
    impl_get_channels,
    impl_render,
    impl_destroy,
    context_create, context_get_duration, context_get_sample_rate,
    context_get_channels, context_render, context_destroy
};
