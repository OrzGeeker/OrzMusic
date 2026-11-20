/**
 * unlzh_ym.c — 标准 LHa LH5 解压器
 *
 * 基于 Haruhiko Okumura 的公共域 ar002 代码的简化实现。
 * 零系统依赖，适配 WASM 和原生编译。
 *
 * LH5 参数（标准值）:
 *   DICBIT=13, CBIT=9, CODE_BIT=12, PBIT=4, TBIT=5
 */
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#define DICBIT      13
#define DICSIZ      (1U << DICBIT)
#define CBIT        9
#define CODE_BIT    12
#define NC          (510)    /* UCHAR_MAX+MAXMATCH+2-THRESHOLD */
#define NP          (DICBIT + 1)
#define NT          (CODE_BIT + 3)
#define PBIT        4
#define TBIT        5
#define NPT         (1 << TBIT)
#define MAXMATCH    256
#define THRESHOLD   3

/* Bit I/O */
static const unsigned char *inp;
static int inpos, insize;
static unsigned char *outp;
static int outpos;
static unsigned short bitbuf;
static unsigned int subbitbuf;
static int bitcount;

static int read_byte(void) {
    return (inpos < insize) ? inp[inpos++] : 0;
}

static void fillbuf(int n) {
    bitbuf <<= n;
    while (n > bitcount) {
        n -= bitcount;
        bitbuf |= (unsigned short)(subbitbuf << n);
        subbitbuf = (unsigned int)read_byte();
        bitcount = 8;
    }
    bitcount -= n;
    bitbuf |= (unsigned short)(subbitbuf >> bitcount);
}

static unsigned short getbits(int n) {
    unsigned short x = bitbuf >> (16 - n);
    fillbuf(n);
    return x;
}

/* Huffman tables */
static unsigned char c_len[NC];
static unsigned char pt_len[NPT];
static unsigned short left[2 * NC - 1];
static unsigned short right[2 * NC - 1];
static unsigned short c_table[4096];
static unsigned short pt_table[256];
static int blocksize;
static unsigned decode_i;
static int decode_j;

static void make_table(int nchar, unsigned char bitlen[], int tablebits, unsigned short table[]) {
    unsigned short count[17], weight[17], start[18], *p;
    unsigned i, k, len, ch, jutbits, avail, nextcode, mask;

    for (i = 1; i <= 16; i++) count[i] = 0;
    for (i = 0; i < (unsigned)nchar; i++) count[bitlen[i]]++;
    start[1] = 0;
    for (i = 1; i <= 16; i++)
        start[i + 1] = start[i] + (count[i] << (16 - i));
    if ((start[17] & 0xffff) != 0) return;

    jutbits = 16 - tablebits;
    for (i = 1; i <= (unsigned)tablebits; i++) {
        start[i] >>= jutbits;
        weight[i] = 1 << (tablebits - i);
    }
    while (i <= 16) { weight[i] = 1 << (16 - i); i++; }

    i = start[tablebits + 1] >> jutbits;
    if (i != 0) {
        k = 1 << tablebits;
        if (i > k) return;
        while (i < k) table[i++] = 0;
    }

    avail = nchar;
    mask = 1 << (15 - tablebits);
    for (ch = 0; ch < (unsigned)nchar; ch++) {
        if ((len = bitlen[ch]) == 0) continue;
        nextcode = start[len] + weight[len];
        if (len <= (unsigned)tablebits) {
            for (i = start[len]; i < nextcode; i++) table[i] = ch;
        } else {
            k = start[len];
            p = &table[k >> jutbits];
            i = len - tablebits;
            while (i != 0) {
                if (*p == 0) {
                    right[avail] = left[avail] = 0;
                    *p = avail++;
                }
                if (k & mask) p = &right[*p];
                else p = &left[*p];
                k <<= 1; i--;
            }
            *p = ch;
        }
        start[len] = nextcode;
    }
}

static void read_pt_len(int nn, int nbit, int i_special) {
    int i, c, n;
    n = getbits(nbit);
    if (n == 0) {
        c = getbits(nbit);
        for (i = 0; i < nn; i++) pt_len[i] = 0;
        for (i = 0; i < 256; i++) pt_table[i] = c;
    } else {
        i = 0;
        while (i < n) {
            c = bitbuf >> (16 - 3);
            if (c == 7) {
                unsigned mask = 1 << (16 - 1 - 3);
                while (mask & bitbuf) {
                    mask >>= 1; c++;
                }
            }
            fillbuf((c < 7) ? 3 : c - 3);
            pt_len[i++] = c;
            if (i == i_special) {
                c = getbits(2);
                while (--c >= 0) {
                    pt_len[i++] = 0;
                }
            }
        }
        while (i < nn) pt_len[i++] = 0;
        make_table(nn, pt_len, 8, pt_table);
    }
}

static void read_c_len(void) {
    int i, c, n;
    n = getbits(CBIT);
    if (n == 0) {
        c = getbits(CBIT);
        for (i = 0; i < NC; i++) c_len[i] = 0;
        for (i = 0; i < 4096; i++) c_table[i] = c;
    } else {
        i = 0;
        while (i < n) {
            c = pt_table[bitbuf >> (16 - 8)];
            if (c >= NT) {
                unsigned mask = 1 << (16 - 1 - 8);
                do {
                    if (bitbuf & mask) c = right[c];
                    else c = left[c];
                    mask >>= 1;
                } while (c >= NT);
            }
            fillbuf(pt_len[c]);
            if (c <= 2) {
                if (c == 0) c = 1;
                else if (c == 1) c = getbits(4) + 3;
                else c = getbits(CBIT) + 20;
                while (--c >= 0) c_len[i++] = 0;
            } else c_len[i++] = c - 2;
        }
        while (i < NC) c_len[i++] = 0;
        make_table(NC, c_len, 12, c_table);
    }
}

static unsigned decode_c(void) {
    unsigned j, mask;
    if (blocksize == 0) {
        blocksize = getbits(16);
        if (blocksize == 0) return NC; /* EOF */
        read_pt_len(NT, TBIT, 3);
        read_c_len();
        read_pt_len(NP, PBIT, -1);
    }
    blocksize--;
    j = c_table[bitbuf >> (16 - 12)];
    if (j >= NC) {
        mask = 1 << (16 - 1 - 12);
        do {
            if (bitbuf & mask) j = right[j];
            else j = left[j];
            mask >>= 1;
        } while (j >= NC);
    }
    fillbuf(c_len[j]);
    return j;
}

static unsigned decode_p(void) {
    unsigned j, mask;
    j = pt_table[bitbuf >> (16 - 8)];
    if (j >= NP) {
        mask = 1 << (16 - 1 - 8);
        do {
            if (bitbuf & mask) j = right[j];
            else j = left[j];
            mask >>= 1;
        } while (j >= NP);
    }
    fillbuf(pt_len[j]);
    if (j != 0) j = (1 << (j - 1)) + getbits((int)(j - 1));
    return j;
}

static void decode_start(void) {
    bitbuf = 0; subbitbuf = 0; bitcount = 0; blocksize = 0;
    decode_i = 0; decode_j = 0;
    fillbuf(16);
}

static int decode(unsigned count, unsigned char buffer[]) {
    unsigned r = 0, c;

    while (--decode_j >= 0) {
        buffer[r] = buffer[decode_i];
        decode_i = (decode_i + 1) & (DICSIZ - 1);
        if (++r == count) return r;
    }
    for (;;) {
        c = decode_c();
        if (c == NC) return r;
        if (c <= 0xff) {
            buffer[r] = (unsigned char)c;
            if (++r == count) return r;
        } else {
            decode_j = c - ((unsigned char)(-1) + 1 - THRESHOLD);
            decode_i = (r - decode_p() - 1) & (DICSIZ - 1);
            while (--decode_j >= 0) {
                buffer[r] = buffer[decode_i];
                decode_i = (decode_i + 1) & (DICSIZ - 1);
                if (++r == count) return r;
            }
        }
    }
}

/**
 * ym_lzh_decompress — 解压标准 LHa LH5 压缩流
 *
 * @param compressed  压缩数据（不含 LHa 头，直接是 Huffman 编码流）
 * @param compressed_len  压缩数据长度
 * @param decompressed  输出缓冲区
 * @param decompressed_len  预期解压大小
 * @return 实际解压字节数，或 -1 表示错误
 */
static int legacy_ym_lzh_decompress(const unsigned char *compressed, int compressed_len,
                                    unsigned char *decompressed, int decompressed_len)
{
    if (!compressed || compressed_len <= 0 || !decompressed || decompressed_len <= 0)
        return -1;

    inp = compressed;
    inpos = 0;
    insize = compressed_len;
    outp = decompressed;
    outpos = 0;

    unsigned char *window = (unsigned char *)calloc(DICSIZ, 1);
    if (!window) return -1;
    memset(window, ' ', DICSIZ);

    decode_start();

    int remaining = decompressed_len;
    int done = 0;
    while (!done && remaining > 0) {
        int chunk = (remaining > (int)DICSIZ) ? (int)DICSIZ : remaining;
        int n = decode(chunk, window);
        if (n > 0) {
            memcpy(outp + outpos, window, n);
            outpos += n;
            remaining -= n;
        }
        if (n < chunk) done = 1; /* end of input */
    }

    free(window);
    return outpos;
}

int ym_lhasa_lh5_decompress(const unsigned char *compressed, int compressed_len,
                            unsigned char *decompressed, int decompressed_len);

int ym_lzh_decompress(const unsigned char *compressed, int compressed_len,
                      unsigned char *decompressed, int decompressed_len)
{
    (void)legacy_ym_lzh_decompress; /* Retained for source-history comparison. */
    return ym_lhasa_lh5_decompress(compressed, compressed_len,
                                   decompressed, decompressed_len);
}
