import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

// 屏幕常亮 (Screen Wake Lock) 与 Media Session 回归测试 —— issue #13。
//
// 沙箱必须同时覆盖两种环境：
//   1. 有 document/navigator（真实浏览器）：驱动 wake lock 与 Media Session 逻辑；
//   2. 没有 document/navigator（player-switch.test.mjs 的沙箱）：验证全部访问都走了
//      globalThis.document?. / globalThis.navigator?. 守卫，裸引用会抛 ReferenceError。

class FakeAudio {
    constructor() {
        this.src = '';
        this.volume = 1;
        this.currentTime = 0;
        this.duration = Number.NaN;
        this.listeners = new Map();
    }
    addEventListener(type, listener) { this.listeners.set(type, listener); }
    dispatch(type) { this.listeners.get(type)?.(); }
    pause() { this.paused = true; }
    async play() { this.paused = false; }
}

const source = await readFile(new URL('../../Resources/Public/audio/player.js', import.meta.url), 'utf8');

/** 让已 resolve 的 promise 链跑完。 */
const flush = () => new Promise(resolve => setImmediate(resolve));

function createPlayerClass(sandbox) {
    const context = vm.createContext(sandbox);
    vm.runInContext(`${source}\nglobalThis.TestPlayer = OrzAudioPlayer;`, context);
    return { Player: context.TestPlayer, context };
}

function createEnv({ withWakeLock = true, withMediaSession = true } = {}) {
    const documentListeners = new Map();
    const requests = [];

    class FakeSentinel {
        constructor() {
            this.released = false;
            this.releaseCalls = 0;
            this._listeners = new Map();
        }
        addEventListener(type, listener) { this._listeners.set(type, listener); }
        release() {
            this.releaseCalls++;
            this.released = true;
            return Promise.resolve();
        }
        /** 模拟 UA 单方面释放（页面隐藏 / 省电模式）。 */
        emitRelease() { this._listeners.get('release')?.(); }
    }

    const media = {
        metadata: null,
        playbackState: null,
        handlers: new Map(),
        positions: [],
        setActionHandler(action, handler) { this.handlers.set(action, handler); },
        setPositionState(state) {
            if (!(state.duration > 0)) throw new TypeError('bad duration');
            if (state.position > state.duration) throw new TypeError('position > duration');
            this.positions.push(state);
        },
    };

    const navigator = {};
    if (withWakeLock) {
        navigator.wakeLock = {
            request(type) {
                let resolve, reject;
                const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
                requests.push({ type, resolve, reject, promise });
                return promise;
            },
        };
    }
    if (withMediaSession) navigator.mediaSession = media;

    class FakeMediaMetadata {
        constructor(init) { Object.assign(this, init); }
    }

    const sandbox = {
        Audio: FakeAudio,
        DOMException,
        console,
        cancelAnimationFrame() {},
        requestAnimationFrame() { return 1; },
        AbortController,
        clearTimeout,
        setTimeout,
        window: {},
        performance: { now: () => Date.now() },
        MediaMetadata: FakeMediaMetadata,
        document: {
            hidden: false,
            visibilityState: 'visible',
            addEventListener(type, listener) { documentListeners.set(type, listener); },
            removeEventListener(type) { documentListeners.delete(type); },
        },
        navigator,
    };

    const { Player } = createPlayerClass(sandbox);
    return {
        Player, media, requests, documentListeners, sandbox, FakeSentinel,
        hide() { sandbox.document.hidden = true; sandbox.document.visibilityState = 'hidden'; },
        show() { sandbox.document.hidden = false; sandbox.document.visibilityState = 'visible'; },
        fireVisibilityChange() { documentListeners.get('visibilitychange')?.(); },
    };
}

// ── 获取 / 释放 ──

test('playing acquires a screen wake lock and stopping releases it', async () => {
    const env = createEnv();
    const player = new env.Player();

    player._setPlaying(true);
    assert.equal(env.requests.length, 1);
    assert.equal(env.requests[0].type, 'screen');

    const sentinel = new env.FakeSentinel();
    env.requests[0].resolve(sentinel);
    await flush();
    assert.equal(player._wakeLock, sentinel);

    player._setPlaying(false);
    assert.equal(player._wakeLock, null);
    assert.equal(sentinel.released, true);
});

test('a late acquire that resolves after stopping is reaped, never left held', async () => {
    const env = createEnv();
    const player = new env.Player();

    // play() 先 stop() 再异步解码播放：获取相对释放是「迟到」的。
    player._setPlaying(true);
    player._setPlaying(false);

    const sentinel = new env.FakeSentinel();
    env.requests[0].resolve(sentinel);
    await flush();

    assert.equal(player._wakeLock, null, 'stopped playback must not hold a wake lock');
    assert.equal(sentinel.released, true, 'the superseded sentinel must be handed back');
});

test('rapid play -> pause -> play ends with exactly one live sentinel', async () => {
    const env = createEnv();
    const player = new env.Player();

    player._setPlaying(true);   // gen 1 -> request A
    player._setPlaying(false);  // gen 2
    player._setPlaying(true);   // gen 3 -> request B (A is a different generation)

    assert.equal(env.requests.length, 2, 'a superseded generation must not block the new acquire');

    const [a, b] = env.requests;
    const sentinelA = new env.FakeSentinel();
    const sentinelB = new env.FakeSentinel();

    a.resolve(sentinelA);
    await flush();
    assert.equal(sentinelA.released, true, 'the stale acquire is handed back');
    assert.equal(player._wakeLock, null);

    b.resolve(sentinelB);
    await flush();
    assert.equal(player._wakeLock, sentinelB);
    assert.equal(sentinelB.released, false);
});

test('a superseded sentinel releasing later does not clear the current handle', async () => {
    const env = createEnv();
    const player = new env.Player();

    player._setPlaying(true);
    const sentinelA = new env.FakeSentinel();
    env.requests[0].resolve(sentinelA);
    await flush();
    assert.equal(player._wakeLock, sentinelA);

    // 换歌：释放 A，再取到 B。
    player._setPlaying(false);
    player._setPlaying(true);
    const sentinelB = new env.FakeSentinel();
    env.requests[1].resolve(sentinelB);
    await flush();
    assert.equal(player._wakeLock, sentinelB);

    // A 的 release 事件迟到 —— 不得把 B 也清掉。
    sentinelA.emitRelease();
    assert.equal(player._wakeLock, sentinelB);

    // B 自己的 release 事件才允许改动状态。
    sentinelB.emitRelease();
    assert.equal(player._wakeLock, null);
});

test('a request that never settles keeps two same-generation acquires from stacking', async () => {
    const env = createEnv();
    const player = new env.Player();

    player._setPlaying(true);
    // 同一代内重复同步（例如 hide->show 抖动）：不得重复发起请求。
    player._syncWakeLock();
    player._syncWakeLock();
    assert.equal(env.requests.length, 1);
});

// ── 可见性 ──

test('a hidden page never requests a lock, and returning to the foreground re-acquires', async () => {
    const env = createEnv();
    const player = new env.Player();

    env.hide();
    player._setPlaying(true);
    assert.equal(env.requests.length, 0, 'request() would reject while hidden');

    env.show();
    env.fireVisibilityChange();
    assert.equal(env.requests.length, 1);

    const sentinel = new env.FakeSentinel();
    env.requests[0].resolve(sentinel);
    await flush();
    assert.equal(player._wakeLock, sentinel);
});

test('hiding drops the stale handle but keeps the intent, so coming back re-acquires', async () => {
    const env = createEnv();
    const player = new env.Player();

    player._setPlaying(true);
    const first = new env.FakeSentinel();
    env.requests[0].resolve(first);
    await flush();
    assert.equal(player._wakeLock, first);

    // 页面隐藏：UA 会自动释放，我们同步丢弃句柄，但意图不变。
    env.hide();
    env.fireVisibilityChange();
    assert.equal(player._wakeLock, null);
    assert.equal(player._wakeWanted, true, 'the intent to stay awake must survive backgrounding');

    env.show();
    env.fireVisibilityChange();
    assert.equal(env.requests.length, 2);
    const second = new env.FakeSentinel();
    env.requests[1].resolve(second);
    await flush();
    assert.equal(player._wakeLock, second);
});

test('a request still in flight across a hide/show cycle is never adopted', async () => {
    const env = createEnv();
    const player = new env.Player();

    player._setPlaying(true);
    assert.equal(env.requests.length, 1);

    // 请求还在途时页面隐藏：UA 会释放它，但我们从未拿到句柄，也就错过它的 release 事件。
    env.hide();
    env.fireVisibilityChange();

    env.show();
    env.fireVisibilityChange();
    assert.equal(env.requests.length, 2, 'coming back to the foreground must issue a fresh request');

    // 隐藏前发出的请求此刻才 resolve。它代表的锁早已被 UA 释放，绝不能被当成持有中 ——
    // 否则 _wakeLock 永远为真，_syncWakeLock 再也不会重取，播放中屏幕照常熄灭。
    const stale = new env.FakeSentinel();
    env.requests[0].resolve(stale);
    await flush();
    assert.equal(stale.released, true, 'the pre-hide sentinel must be handed straight back');
    assert.equal(player._wakeLock, null, 'and must not be adopted as the held handle');

    const fresh = new env.FakeSentinel();
    env.requests[1].resolve(fresh);
    await flush();
    assert.equal(player._wakeLock, fresh, 'the post-hide request is the one that counts');
    assert.equal(fresh.released, false);
});

// ── 降级 ──

test('an unsupported browser (no navigator.wakeLock) plays without throwing', async () => {
    const env = createEnv({ withWakeLock: false });
    const player = new env.Player();

    assert.doesNotThrow(() => player._setPlaying(true));
    assert.equal(player.isPlaying, true);
    assert.equal(player._wakeLock, null);
    assert.doesNotThrow(() => player._setPlaying(false));
});

test('a rejected request degrades silently and never disturbs playback', async () => {
    const env = createEnv();
    const player = new env.Player();

    player._setPlaying(true);
    env.requests[0].reject(new Error('NotAllowedError'));
    await flush();

    assert.equal(player.isPlaying, true, 'a wake lock failure must not affect playback');
    assert.equal(player._wakeLock, null);
    assert.equal(player._wakePending, null, 'a rejected request must clear the in-flight slot');
});

test('a request resolving without a usable sentinel degrades instead of throwing', async () => {
    for (const resolved of [null, {}, { release() {} }]) {
        const env = createEnv();
        const player = new env.Player();

        player._setPlaying(true);
        env.requests[0].resolve(resolved);
        await flush();

        assert.equal(player._wakeLock, null, `a sentinel of ${JSON.stringify(resolved)} must not be held`);
        assert.equal(player.isPlaying, true);
        assert.equal(player._wakePending, null, 'the in-flight slot must be cleared either way');
    }
});

test('a hostile thenable cannot escape _setPlaying into the audio path', () => {
    const env = createEnv();
    env.sandbox.navigator.wakeLock.request = () => ({
        then() { throw new TypeError('non-conforming thenable'); },
    });
    const player = new env.Player();

    // _acquireWakeLock 由 _setPlaying 同步调用，同步抛出会一路冒到播放链路。
    assert.doesNotThrow(() => player._setPlaying(true));
    assert.equal(player.isPlaying, true);
    assert.equal(player._wakePending, null, 'a failed attach must not wedge the in-flight slot');
});

test('dispose releases the handle and unbinds the visibility listener', async () => {
    const env = createEnv();
    const player = new env.Player();

    player._setPlaying(true);
    const sentinel = new env.FakeSentinel();
    env.requests[0].resolve(sentinel);
    await flush();
    assert.equal(player._wakeLock, sentinel);

    player.dispose();

    assert.equal(sentinel.released, true);
    assert.equal(player._wakeLock, null);
    assert.equal(env.documentListeners.size, 0, 'the visibilitychange listener must be removed');
});

test('a sentinel settling after dispose is reaped instead of being retained', async () => {
    const env = createEnv();
    const player = new env.Player();

    player._setPlaying(true);
    const sentinel = new env.FakeSentinel();
    player.dispose();

    env.requests[0].resolve(sentinel);
    await flush();

    assert.equal(sentinel.released, true);
    assert.equal(player._wakeLock, null);
});

// ── Media Session ──

test('song metadata is published to the lock screen without artwork', async () => {
    const env = createEnv();
    const player = new env.Player();

    player._syncMediaSession({ id: 7, title: 'Chip Tune', artist: 'Someone', album: 'Some Album' });

    assert.equal(env.media.metadata.title, 'Chip Tune');
    assert.equal(env.media.metadata.artist, 'Someone');
    assert.equal(env.media.metadata.album, 'Some Album');
    assert.equal(env.media.metadata.artwork, undefined, 'the repo ships no cover art');
});

test('playback state follows the playing flag', async () => {
    const env = createEnv();
    const player = new env.Player();

    player._setPlaying(true);
    assert.equal(env.media.playbackState, 'playing');

    player._setPlaying(false);
    assert.equal(env.media.playbackState, 'none', 'no current song -> none');
});

test('lock-screen transport controls dispatch to the app-level callbacks', () => {
    const env = createEnv();
    const player = new env.Player();
    const calls = [];
    player.onNext = () => calls.push('next');
    player.onPrev = () => calls.push('prev');
    player.togglePlay = () => calls.push('toggle');

    env.media.handlers.get('nexttrack')();
    env.media.handlers.get('previoustrack')();
    assert.deepEqual([...calls], ['next', 'prev']);

    // 不注册 stop：stop() 清空音源却保留 currentSong，锁屏会停在「已暂停」且播放键按下去
    // 必然失败（audioEl.src 已为空）。app 内同样没有停止入口，暂停已覆盖该需求。
    assert.equal(env.media.handlers.has('stop'), false,
        'a lock-screen stop button would advertise a resume that cannot succeed');

    // play/pause 只在状态真的需要翻转时才转发，避免锁屏重复点击造成来回抖动。
    player._setPlaying(true);
    env.media.handlers.get('pause')();
    assert.equal(calls.at(-1), 'toggle');
    env.media.handlers.get('pause')();
    assert.equal(calls.at(-1), 'toggle', 'pause is a no-op while already paused');

    player._setPlaying(false);
    env.media.handlers.get('play')();
    assert.equal(calls.at(-1), 'toggle');
    env.media.handlers.get('play')();
    assert.equal(calls.at(-1), 'toggle', 'play is a no-op while already playing');
});

test('position state is throttled and skips values the API would reject', async () => {
    const env = createEnv();
    const player = new env.Player();

    // duration 未知（0 / NaN）时不得调用，否则 setPositionState 会抛异常。
    player.duration = 0;
    player.currentTime = 0;
    assert.doesNotThrow(() => player._syncMediaSessionPosition());
    assert.equal(env.media.positions.length, 0);

    player.duration = 180;
    player.currentTime = 42;
    player._mediaPositionAt = 0;
    player._syncMediaSessionPosition();
    assert.equal(env.media.positions.length, 1);
    // 逐字段比较：posiiton state 对象由 vm realm 创建，跨 realm 的深比较会因原型不同而失败。
    const position = env.media.positions[0];
    assert.equal(position.duration, 180);
    assert.equal(position.playbackRate, 1);
    assert.equal(position.position, 42);

    // 1 秒节流：紧接着再调一次不应重复上报。
    player._syncMediaSessionPosition();
    assert.equal(env.media.positions.length, 1);
});

test('the first tick with a known duration publishes without waiting out the throttle', () => {
    const env = createEnv();
    const player = new env.Player();

    // 曲目刚起时 duration 还是 0。若「先计时后校验」，这一拍会白白吃掉整个 1 秒窗口，
    // 等 duration 就绪后锁屏进度还要再等 1 秒才动。
    player.duration = 0;
    player.currentTime = 0;
    player._syncMediaSessionPosition();
    assert.equal(env.media.positions.length, 0);

    // 注意不重置 _mediaPositionAt：真实的 RAF tick 也不会重置它。
    player.duration = 180;
    player.currentTime = 5;
    player._syncMediaSessionPosition();
    assert.equal(env.media.positions.length, 1, 'an invalid call must not burn the throttle window');
    assert.equal(env.media.positions[0].position, 5);
});

test('metadata is retried once MediaMetadata becomes available', () => {
    const env = createEnv();
    const player = new env.Player();
    const song = { id: 3, title: 'Later' };
    const MediaMetadata = env.sandbox.MediaMetadata;

    // MediaMetadata 尚未可用时写入失败，此时不得把这个 id 记进去重缓存，
    // 否则同一首歌之后会被提前 return 挡掉，锁屏元数据永远补不上。
    env.sandbox.MediaMetadata = undefined;
    player._syncMediaSession(song);
    assert.equal(env.media.metadata, null);

    env.sandbox.MediaMetadata = MediaMetadata;
    player._syncMediaSession(song);
    assert.equal(env.media.metadata?.title, 'Later', 'the retry must not be blocked by the de-dup cache');
});

test('every playback tick feeds the lock-screen position, including the worker path', () => {
    // wasmDecode（xm/mod/ym…）是模块格式的主播放路径；漏掉它的 tick，锁屏进度就会一直停在原地。
    const ticks = [...source.matchAll(/const tick = \(\) => \{[\s\S]*?\n {8}\};/g)].map(match => match[0]);
    assert.ok(ticks.length >= 3, `expected at least 3 progress ticks, found ${ticks.length}`);
    for (const tick of ticks) {
        assert.match(tick, /this\._syncMediaSessionPosition\(\);/,
            'a progress tick that updates currentTime but not the lock-screen position');
    }

    const worker = source.indexOf('this._workerClockStart = this.audioCtx.currentTime');
    assert.notEqual(worker, -1, 'the worker tick anchor moved — update this test');
    const workerTick = source.slice(worker, source.indexOf('requestAnimationFrame(tick)', worker));
    assert.match(workerTick, /this\._syncMediaSessionPosition\(\);/);
});

test('a browser without Media Session still plays', () => {
    const env = createEnv({ withMediaSession: false });
    const player = new env.Player();

    assert.doesNotThrow(() => player._syncMediaSession({ id: 1, title: 'x' }));
    assert.doesNotThrow(() => player._setPlaying(true));
    assert.equal(player.isPlaying, true);
});

// ── 沙箱兼容性（player-switch.test.mjs 的环境没有 document / navigator）──

test('a sandbox with neither document nor navigator does not throw', async () => {
    const sandbox = {
        Audio: FakeAudio,
        DOMException,
        console,
        cancelAnimationFrame() {},
        requestAnimationFrame() { return 1; },
        AbortController,
        clearTimeout,
        setTimeout,
        window: {},
    };
    const { Player } = createPlayerClass(sandbox);

    let player;
    assert.doesNotThrow(() => { player = new Player(); }, 'constructor must guard document/navigator');
    assert.doesNotThrow(() => player._setPlaying(true));
    assert.doesNotThrow(() => player._setPlaying(false));
    assert.doesNotThrow(() => player._syncMediaSession({ id: 1, title: 'x' }));
    assert.doesNotThrow(() => player._syncMediaSessionPosition());
    assert.doesNotThrow(() => player.dispose());
});
