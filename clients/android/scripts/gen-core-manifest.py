#!/usr/bin/env python3
"""从 audio-kit/native/decoder-profile.json 生成 core/.../DecoderManifest.kt。

对齐 Web 端 `decoder-manifest.generated.js`:
SDK 提供完整 manifest,客户端按自己编进来的 profile 取子集。
改了 profile 或升级解码器版本后重跑:

    python3 clients/android/scripts/gen-core-manifest.py
    python3 clients/android/scripts/gen-core-manifest.py --check   # CI 用

为什么用 Python 而不是 Gradle task:
· profile 是 JSON,生成逻辑只有几行;
· CI 的构建镜像不保证有 Kotlin/Gradle 之外的解析能力,但 Python 是基座本来就用的。
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
APP = HERE.parent
PROFILE = APP / "audio-kit/native/decoder-profile.json"
OUT = APP / "core/src/main/kotlin/com/jokerhub/orzmusic/core/playback/DecoderManifest.kt"

HEADER = '''package com.jokerhub.orzmusic.core.playback

/**
 * **自动生成,不要手改。**
 *
 * 单一事实来源:`clients/android/audio-kit/native/decoder-profile.json`
 * 生成器:`clients/android/scripts/gen-core-manifest.py`
 *
 * 与 Web 端 `decoder-manifest.generated.js` 同一层职责:
 * OrzAudioCore 提供完整 manifest,客户端按自己编进来的 profile 取子集。
 */
data class DecoderFormat(
    val id: String,
    val label: String,
    val group: String
)

object DecoderManifest : DecoderManifestSource {

'''

FOOTER = '''
    val supportedIds: Set<String> = formats.map { it.id }.toSet()

    override fun supports(format: String): Boolean = format.lowercase() in supportedIds

    override fun label(format: String): String =
        formats.firstOrNull { it.id == format.lowercase() }?.label ?: format.uppercase()
}
'''


def render() -> str:
    profile = json.loads(PROFILE.read_text(encoding="utf-8"))
    formats = profile.get("formats") or []
    if not formats:
        raise SystemExit("decoder-profile.json 里没有 formats")

    lines = [
        f'    const val PROFILE = "{profile["profile"]}"',
        f'    const val SDK_VERSION = "{profile["sdk"]}"',
        f'    const val LIBOPENMPT_VERSION = "{profile["libopenmpt"]}"',
        "",
        "    /**",
        "     * 解码器指纹:进 WAV 缓存键。",
        "     * 升级 OrzAudioCore / libopenmpt 后,旧 WAV 自动失效 ——",
        "     * 与服务端把 `orz_build_info()` fingerprint 写进 PCM 缓存键是同一口径。",
        "     */",
        f'    const val CACHE_NAMESPACE = "{profile["cacheNamespace"]}"',
        "",
        "    override val formats: List<DecoderFormat> = listOf(",
    ]
    for f in formats:
        lines.append(
            f'        DecoderFormat("{f["id"]}", "{f["label"]}", "{f["group"]}"),'
        )
    lines.append("    )")
    return HEADER + "\n".join(lines) + FOOTER


def main() -> int:
    want = render()
    if "--check" in sys.argv[1:]:
        have = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
        if have != want:
            print(f"!! {OUT.relative_to(APP)} 与 decoder-profile.json 不一致", file=sys.stderr)
            print("   重跑: python3 clients/android/scripts/gen-core-manifest.py", file=sys.stderr)
            return 1
        print(f"✅ {OUT.relative_to(APP)} 与 profile 一致")
        return 0

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(want, encoding="utf-8")
    print(f"已生成 {OUT.relative_to(APP)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
