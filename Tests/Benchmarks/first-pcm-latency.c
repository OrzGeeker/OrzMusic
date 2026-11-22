/*
 * 首播延迟基准：解码器 create + 首批 PCM 出帧要多久。
 *
 * 为什么单独有这个文件：`Docs/backlog/android-pcm-streaming.md` 的验收清单里
 * 「sc68 首播 < 1s」长期没有数字。Android 真机要挂设备，但这条链路（OrzAudioCore
 * 的 C 代码 + JNI）在桌面就是同一份，所以先在宿主上量一个基准，把量级定下来；
 * 真机仍需要一次性确认（ARM 主频低，通常比这里慢 1.5~3 倍）。
 *
 * 编译（需要先 ./script/update-audio-core-server.sh 装 SDK）：
 *   gcc -O2 -o /tmp/first-pcm-latency Tests/Benchmarks/first-pcm-latency.c \
 *     -I.audio-core-sdk/server/native/include \
 *     -L.audio-core-sdk/server/native/lib -lOrzAudioCore \
 *     -Wl,-rpath,$PWD/.audio-core-sdk/server/native/lib
 *
 * 跑（fixture 来自 keygenmusic 子模块）：
 *   /tmp/first-pcm-latency "keygenmusic/.../AGiLE - Crime Wave intro.sc68" sc68
 *
 * 输出里 `first audible` = create + 首批 frames 渲染完成的时间，也就是 PcmStream
 * 能开始往 AudioTrack 送数据的时间点；不含 AudioTrack 起播（250ms 硬件缓冲）。
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "orz_audio_core.h"

#define FIRST_FRAMES 4096u

static double now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s <file> <format-hint>\n", argv[0]);
        return 2;
    }
    FILE *f = fopen(argv[1], "rb");
    if (!f) { perror("open"); return 2; }
    fseek(f, 0, SEEK_END);
    long len = ftell(f);
    fseek(f, 0, SEEK_SET);
    uint8_t *data = malloc((size_t)len);
    if (fread(data, 1, (size_t)len, f) != (size_t)len) { perror("read"); return 2; }
    fclose(f);

    printf("build: %s\n", orz_build_info());
    printf("file:  %s (%ld bytes, hint=%s)\n", argv[1], len, argv[2]);

    orz_decoder_config cfg;
    memset(&cfg, 0, sizeof(cfg));
    cfg.struct_size = sizeof(cfg);
    cfg.abi_version = ORZ_ABI_VERSION;

    double t0 = now_ms();
    OrzDecoderHandle *dec = NULL;
    orz_status st = orz_decoder_create_memory(data, (size_t)len, argv[2], &cfg, &dec);
    double t_create = now_ms();
    if (st != ORZ_OK || !dec) {
        printf("create failed: %s\n", orz_status_message(st));
        return 1;
    }

    orz_stream_info info;
    memset(&info, 0, sizeof(info));
    info.struct_size = sizeof(info);
    info.abi_version = ORZ_ABI_VERSION;
    orz_decoder_get_stream_info(dec, &info);
    printf("stream: %u Hz / %u ch / %.2fs / subsongs=%u\n",
           info.sample_rate, info.channels, info.duration_seconds, info.subsong_count);

    float *buf = malloc(sizeof(float) * FIRST_FRAMES * 2);
    uint32_t got = 0;
    uint64_t frames_total = 0;
    int renders = 0;
    double t_render0 = 0;
    while (frames_total < FIRST_FRAMES) {
        double ta = now_ms();
        st = orz_decoder_render_f32(dec, buf, FIRST_FRAMES, &got);
        double tb = now_ms();
        if (renders == 0) t_render0 = tb - ta;
        renders++;
        if (st != ORZ_OK) {
            printf("render status: %s (after %d renders)\n", orz_status_message(st), renders);
            break;
        }
        if (got == 0) break;
        frames_total += got;
    }
    double t_end = now_ms();

    printf("create: %.1f ms | 1st render: %.1f ms | %llu frames in %d renders: %.1f ms\n",
           t_create - t0, t_render0, (unsigned long long)frames_total, renders, t_end - t_create);
    printf("=> first audible (create + first %u frames): %.1f ms\n", FIRST_FRAMES, t_end - t0);

    orz_decoder_destroy_v1(dec);
    free(buf);
    free(data);
    return 0;
}
