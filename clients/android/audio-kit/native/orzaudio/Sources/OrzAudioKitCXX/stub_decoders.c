/*
 * stub_decoders.c — 缺失解码器的弱符号存根
 *
 * 当某些解码器实现未编译进 WASM 时，提供空的 Decoder 结构体。
 * 使用 __attribute__((weak)) 确保当真实定义存在时被覆盖。
 */

#include "audio_engine.h"

#define WEAK __attribute__((weak))

WEAK const Decoder decoder_openmpt = {0};
WEAK const Decoder decoder_gme = {0};
WEAK const Decoder decoder_sidplayfp = {0};
WEAK const Decoder decoder_asap = {0};
WEAK const Decoder decoder_v2m = {0};
WEAK const Decoder decoder_sc68 = {0};
WEAK const Decoder decoder_uade_ahx = {0};
WEAK const Decoder decoder_adplug = {0};
/* BP, MIDI and YM are mandatory in every build profile and therefore must
 * remain strong unresolved references so static archive linkers pull their
 * implementation objects into consumers. */
