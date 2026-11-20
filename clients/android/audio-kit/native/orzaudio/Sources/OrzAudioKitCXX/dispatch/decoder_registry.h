#ifndef ORZ_DECODER_REGISTRY_H
#define ORZ_DECODER_REGISTRY_H

#include <stdint.h>
#include "audio_engine.h"

typedef struct OrzDecoderDescriptor {
    const char *format_id;
    const char *display_name;
    const char *decoder_id;
    const char *decoder_version;
    const char *category;
    uint32_t capabilities;
    uint32_t platform_flags;
    const Decoder *decoder;
} OrzDecoderDescriptor;

uint32_t orz_registry_count(void);
const OrzDecoderDescriptor *orz_registry_at(uint32_t index);
const OrzDecoderDescriptor *orz_registry_find(const char *format);

#endif
