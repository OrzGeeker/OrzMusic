import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(import.meta.url);
let builtinModulePromise;

function builtinModule() {
    if (!builtinModulePromise) {
        const createModule = require(path.join(root, 'Resources/Public/audio/orz_audio_builtin.js'));
        builtinModulePromise = createModule({
            wasmBinary: fs.readFileSync(path.join(root, 'Resources/Public/audio/orz_audio_builtin.wasm'))
        });
    }
    return builtinModulePromise;
}

test('builtin WASM is pinned to the immutable OrzAudioCore release', async () => {
    const lock = JSON.parse(fs.readFileSync(path.join(root, 'audio-core-sdk.lock.json'), 'utf8'));
    assert.equal(lock.schemaVersion, 1);
    const asset = (platform, profile) => {
        const found = lock.assets.find(item => item.platform === platform && item.profile === profile);
        assert.ok(found, `lock 缺少 ${platform}/${profile} 资产`);
        assert.match(found.sha256, /^[0-9a-f]{64}$/);
        return found;
    };
    // 不在这里写死版本号:真正的约束是「lock == 打包进来的 WASM 的 build info」。
    asset('macos-arm64', 'full');
    asset('linux-x86_64', 'server');
    asset('linux-arm64', 'server');
    asset('web', 'full');
    const wasm = await builtinModule();
    const buildInfo = JSON.parse(wasm.UTF8ToString(wasm._orz_build_info()));
    assert.equal(buildInfo.version, lock.version);
    assert.equal(buildInfo.profile, 'builtin-lite');
    assert.equal(buildInfo.cacheNamespace, lock.profiles['builtin-lite'].cacheNamespace);
    assert.equal(buildInfo.fingerprint, lock.profiles['builtin-lite'].fingerprint);
});

function createV1(wasm, tune, formatName) {
    const format = wasm._malloc(formatName.length + 1);
    const data = wasm._malloc(tune.length);
    const output = wasm._malloc(4);
    wasm.stringToUTF8(formatName, format, formatName.length + 1);
    wasm.HEAPU8.set(tune, data);
    wasm.HEAPU32[output >> 2] = 0;
    const status = wasm._orz_decoder_create_memory(data, tune.length, format, 0, output);
    const decoder = wasm.HEAPU32[output >> 2];
    wasm._free(output); wasm._free(format); wasm._free(data);
    assert.equal(status, 0);
    assert.notEqual(decoder, 0);
    return decoder;
}

function renderV1(wasm, decoder, frames, channels = 2) {
    const pcm = wasm._malloc(frames * channels * 4);
    const rendered = wasm._malloc(4);
    try {
        assert.equal(wasm._orz_decoder_render_f32(decoder, pcm, frames, rendered), 0);
        const count = wasm.HEAPU32[rendered >> 2];
        assert.equal(count, frames);
        return wasm.HEAPF32.slice(pcm >> 2, (pcm >> 2) + count * channels);
    } finally {
        wasm._free(rendered);
        wasm._free(pcm);
    }
}

test('WASM exposes ABI v1 and owns decoder input memory', async () => {
    const wasm = await builtinModule();
    assert.equal(wasm._orz_abi_version(), 0x10000);
    const tune = fs.readFileSync(path.join(root, 'keygenmusic/KEYGENMUSiC MusicPack/EDGE/EDGE - ChessTiger 2007 UCI kg.ym'));
    const decoder = createV1(wasm, tune, 'ym');
    try {
        const info = wasm._malloc(64);
        wasm.HEAPU8.fill(0, info, info + 64);
        wasm.HEAPU32[info >> 2] = 64;
        wasm.HEAPU32[(info + 4) >> 2] = 0x10000;
        assert.equal(wasm._orz_decoder_get_stream_info(decoder, info), 0);
        assert.equal(wasm.HEAPU32[(info + 12) >> 2], 2);
        wasm._free(info);
        const pcm = wasm._malloc(512 * 2 * 4), rendered = wasm._malloc(4);
        assert.equal(wasm._orz_decoder_render_f32(decoder, pcm, 512, rendered), 0);
        assert.equal(wasm.HEAPU32[rendered >> 2], 512);
        wasm._free(rendered); wasm._free(pcm);
    } finally { wasm._orz_decoder_destroy_v1(decoder); }
});

test('released builtin and full cores produce matching YM PCM', async () => {
    const builtin = await builtinModule();
    const createFullModule = require(path.join(root, 'Resources/Public/audio/orz_audio.js'));
    const full = await createFullModule({
        wasmBinary: fs.readFileSync(path.join(root, 'Resources/Public/audio/orz_audio.wasm'))
    });
    const tune = fs.readFileSync(path.join(root, 'keygenmusic/KEYGENMUSiC MusicPack/EDGE/EDGE - ChessTiger 2007 UCI kg.ym'));
    const builtinDecoder = createV1(builtin, tune, 'ym');
    const fullDecoder = createV1(full, tune, 'ym');
    try {
        const expected = renderV1(full, fullDecoder, 4096);
        const actual = renderV1(builtin, builtinDecoder, 4096);
        assert.equal(actual.length, expected.length);
        let maximumError = 0;
        for (let index = 0; index < actual.length; index++) {
            maximumError = Math.max(maximumError, Math.abs(actual[index] - expected[index]));
        }
        assert.ok(maximumError <= 1e-7, `YM PCM maximum error ${maximumError}`);
    } finally {
        builtin._orz_decoder_destroy_v1(builtinDecoder);
        full._orz_decoder_destroy_v1(fullDecoder);
    }
});

test('full WASM includes AdPlug and renders audible HSC PCM', async () => {
    const createModule = require(path.join(root, 'Resources/Public/audio/orz_audio.js'));
    const wasm = await createModule({
        wasmBinary: fs.readFileSync(path.join(root, 'Resources/Public/audio/orz_audio.wasm'))
    });
    const tune = fs.readFileSync(path.join(
        root,
        'keygenmusic/KEYGENMUSiC MusicPack/LEGEND/LEGEND - Dark Legions intro.hsc'
    ));
    const decoder = createV1(wasm, tune, 'hsc');
    try {
        const pcm = renderV1(wasm, decoder, 44_100);
        assert.ok(pcm.some(sample => Math.abs(sample) > 0.001), 'AdPlug HSC output remained silent');
    } finally {
        wasm._orz_decoder_destroy_v1(decoder);
    }
});

test('full WASM V2M decoder reports seconds and renders audible PCM', async () => {
    const createModule = require(path.join(root, 'Resources/Public/audio/orz_audio.js'));
    const wasmPath = path.join(root, 'Resources/Public/audio/orz_audio.wasm');
    const wasmBinary = fs.readFileSync(wasmPath);
    const wasm = await createModule({ wasmBinary });
    const tune = fs.readFileSync(path.join(
        root,
        'keygenmusic/KEYGENMUSiC MusicPack/iOTA/iOTA - ACDSee Pro 5.3 build 168 crk.v2m'
    ));

    const format = wasm._malloc(4);
    const data = wasm._malloc(tune.length);
    wasm.stringToUTF8('v2m', format, 4);
    wasm.HEAPU8.set(tune, data);
    const decoder = wasm._orz_decoder_create(format, data, tune.length);
    wasm._free(format);
    wasm._free(data);
    assert.notEqual(decoder, 0);

    try {
        assert.equal(wasm._orz_decoder_get_duration(decoder), 172);
        const frames = 44_100;
        const pcmPointer = wasm._malloc(frames * 2 * 4);
        assert.notEqual(pcmPointer, 0);
        try {
            assert.equal(wasm._orz_decoder_render(decoder, pcmPointer, frames), frames);
            const pcm = wasm.HEAPF32.subarray(pcmPointer >> 2, (pcmPointer >> 2) + frames * 2);
            assert.ok(pcm.some(sample => Math.abs(sample) > 0.01), 'V2M WASM output remained silent');
        } finally {
            wasm._free(pcmPointer);
        }
    } finally {
        wasm._orz_decoder_destroy(decoder);
    }
});

test('full WASM converts older V2M synth layouts instead of rendering silence', async () => {
    const createModule = require(path.join(root, 'Resources/Public/audio/orz_audio.js'));
    const wasm = await createModule({
        wasmBinary: fs.readFileSync(path.join(root, 'Resources/Public/audio/orz_audio.wasm'))
    });
    for (const relativePath of [
        'keygenmusic/KEYGENMUSiC MusicPack/kZ/kZ - DeskSoft HardCopy Pro 3.2.1 crk.v2m',
        'keygenmusic/KEYGENMUSiC MusicPack/DimitarSerg/DimitarSerg - Resource Builder 3.0.3.25 kg.v2m'
    ]) {
        const tune = fs.readFileSync(path.join(root, relativePath));
        const format = wasm._malloc(4);
        const data = wasm._malloc(tune.length);
        wasm.stringToUTF8('v2m', format, 4);
        wasm.HEAPU8.set(tune, data);
        const decoder = wasm._orz_decoder_create(format, data, tune.length);
        wasm._free(format);
        wasm._free(data);
        assert.notEqual(decoder, 0, relativePath);
        try {
            const frames = 5 * 44_100;
            const pcmPointer = wasm._malloc(frames * 2 * 4);
            assert.notEqual(pcmPointer, 0);
            try {
                assert.equal(wasm._orz_decoder_render(decoder, pcmPointer, frames), frames);
                const pcm = wasm.HEAPF32.subarray(pcmPointer >> 2, (pcmPointer >> 2) + frames * 2);
                assert.ok(pcm.some(sample => Math.abs(sample) > 0.01), relativePath);
            } finally {
                wasm._free(pcmPointer);
            }
        } finally {
            wasm._orz_decoder_destroy(decoder);
        }
    }
});
