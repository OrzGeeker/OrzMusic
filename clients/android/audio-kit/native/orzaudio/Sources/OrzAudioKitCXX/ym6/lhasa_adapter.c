#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "lhasa/lha_decoder.h"

extern const LHADecoderType lha_lh5_decoder;

typedef struct {
    const uint8_t *data;
    size_t length;
    size_t position;
} YMInput;

static size_t read_compressed(void *buffer, size_t length, void *opaque) {
    YMInput *input = (YMInput *)opaque;
    size_t available = input->length - input->position;
    if (length > available) length = available;
    if (length > 0) {
        memcpy(buffer, input->data + input->position, length);
        input->position += length;
    }
    return length;
}

int ym_lhasa_lh5_decompress(const unsigned char *compressed, int compressed_len,
                            unsigned char *decompressed, int decompressed_len) {
    if (!compressed || compressed_len <= 0 || !decompressed || decompressed_len <= 0) return -1;

    YMInput input = { compressed, (size_t)compressed_len, 0 };
    void *state = calloc(1, lha_lh5_decoder.extra_size);
    uint8_t *chunk = malloc(lha_lh5_decoder.max_read);
    if (!state || !chunk) { free(state); free(chunk); return -1; }
    if (!lha_lh5_decoder.init(state, read_compressed, &input)) {
        free(chunk); free(state); return -1;
    }

    size_t written = 0;
    while (written < (size_t)decompressed_len) {
        size_t count = lha_lh5_decoder.read(state, chunk);
        if (count == 0 || count > (size_t)decompressed_len - written) {
            free(chunk); free(state); return -1;
        }
        memcpy(decompressed + written, chunk, count);
        written += count;
    }

    if (lha_lh5_decoder.free) lha_lh5_decoder.free(state);
    free(chunk);
    free(state);
    return (int)written;
}
