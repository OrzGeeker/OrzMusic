#!/usr/bin/env python3
"""生成 Android SDK 的 DecoderManifest.generated.kt。

输入:
  · clients/android/audio-kit/native/decoder-manifest.json   ← OrzAudioCore 上游 manifest
  · clients/android/audio-kit/native/decoder-profile.json    ← Android 编译进来的格式子集

输出:
  · clients/android/audio-kit/src/main/java/com/jokerhub/orzmusic/audiokit/DecoderManifest.generated.kt

和 Swift 的 `DecoderManifest.generated.swift`、Web 的
`decoder-manifest.generated.js` 同源:SDK 提供完整 manifest,
客户端按自己的 profile 取子集。

用法:
  python3 clients/android/audio-kit/scripts/gen-manifest.py
  python3 clients/android/audio-kit/scripts/gen-manifest.py --check
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
AUDIO_KIT = HERE.parent
NATIVE = AUDIO_KIT / "native"
PROFILE = NATIVE / "decoder-profile.json"
MANIFEST = NATIVE / "decoder-manifest.json"
OUT = AUDIO_KIT / "src/main/java/com/jokerhub/orzmusic/audiokit/DecoderManifest.generated.kt"

HEADER = '''package com.jokerhub.orzmusic.audiokit

/**
 * **自动生成,不要手改。**
 *
 * 输入:
 *   · `clients/android/audio-kit/native/decoder-manifest.json`(OrzAudioCore 上游)
 *   · `clients/android/audio-kit/native/decoder-profile.json`(Android profile)
 * 生成器:`clients/android/audio-kit/scripts/gen-manifest.py`
 *
 * 与 Swift 的 `DecoderManifest.generated.swift`、Web 的
 * `decoder-manifest.generated.js` 同源。
 */
object DecoderManifest {

'''


def kotlin_bool(value: bool) -> str:
    return "true" if value else "false"


def render() -> str:
    profile = json.loads(PROFILE.read_text(encoding="utf-8"))
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))

    by_id = {item["id"]: item for item in manifest.get("formats", [])}
    wanted = [item["id"] for item in profile.get("formats", [])]
    missing = [fid for fid in wanted if fid not in by_id]
    if missing:
        raise SystemExit(f"profile 里的格式在 decoder-manifest.json 中不存在: {missing}")

    lines = [
        f'    const val PROFILE = "{profile["profile"]}"',
        f'    const val SDK_VERSION = "{manifest["sdkVersion"]}"',
        f'    const val LIBOPENMPT_VERSION = "{profile["libopenmpt"]}"',
        f'    const val CACHE_NAMESPACE = "{profile["cacheNamespace"]}"',
        "",
        "    val formats: List<DecoderFormatInfo> = listOf(",
    ]

    for fid in wanted:
        item = by_id[fid]
        name = item.get("name", fid.upper())
        decoder = item.get("decoder", "")
        version = item.get("version", "")
        category = item.get("category", "")
        seek = kotlin_bool(bool(item.get("seek", False)))
        subsong = kotlin_bool(bool(item.get("subsong", False)))
        lines.append(
            f'        DecoderFormatInfo("{fid}", "{name}", "{decoder}", "{version}", '
            f'"{category}", {seek}, {subsong}),'
        )

    lines += [
        "    )",
        "",
        "    val supportedIds: Set<String> = formats.map { it.id }.toSet()",
        "",
        "    fun supports(format: String): Boolean = format.lowercase() in supportedIds",
        "}",
    ]
    return HEADER + "\n".join(lines) + "\n"


def main() -> int:
    want = render()
    if "--check" in sys.argv[1:]:
        have = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
        if have != want:
            print(f"!! {OUT.relative_to(AUDIO_KIT)} 与 profile/manifest 不一致", file=sys.stderr)
            print("   重跑: python3 clients/android/audio-kit/scripts/gen-manifest.py", file=sys.stderr)
            return 1
        print(f"✅ {OUT.relative_to(AUDIO_KIT)} 与 profile/manifest 一致")
        return 0

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(want, encoding="utf-8")
    print(f"已生成 {OUT.relative_to(AUDIO_KIT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
