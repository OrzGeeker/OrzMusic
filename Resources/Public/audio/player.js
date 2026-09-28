/**
 * OrzAudioPlayer — 统一音频播放引擎
 *
 * 播放策略（自动选择）:
 *   directFile  → <audio> 元素
 *   wasmDecode  → OrzAudioKit WASM 解码 (fallback 到 AudioContext.decodeAudioData)
 *   serverDecode → <audio> 元素 (服务端已转码为 WAV)
 *
 * 依赖:
 *   - /audio/orz_audio.js (OrzAudioKit WASM) — 可选，不存在则自动降级
 */

class OrzAudioPlayer {
    constructor() {
        this.audioEl = new Audio();
        this.audioCtx = null;
        this.wasmKit = null;        // OrzAudioKit WASM 模块实例
        this.decoderHandle = 0;     // 当前播放独占的 C decoder handle
        this.wasmReady = false;     // WASM 是否已初始化
        this.currentSource = null;  // AudioBufferSourceNode (WASM 渲染路径)
        this.analyser = null;       // shared AnalyserNode for all Web Audio paths
        this.masterGain = null;     // shared volume control for every Web Audio path
        this.mediaElementSource = null; // MediaElementAudioSourceNode (directFile path)
        this._usesWebAudio = false; // directFile path using Web Audio graph (vs native audioEl.volume)
        this._mediaElementConnected = false;

        // 状态
        this.isPlaying = false;
        this.currentTime = 0;
        this.duration = 0;
        this.volume = 0.7;
        this._wasmLoadAttempted = false;
        this._wasmLoadPromise = null;
        this._washProgressRAF = null;
        this._streamActive = false;
        this._streamGen = 0;
        this._playGen = 0;
        this._decoderWorker = null;
        this._workerPool = new Map();
        this._workerWarmups = new Map();
        this._disposed = false;
        this._workerReject = null;
        this._workerRejectOwner = null;
        this._fetchController = null;
        this._workletNode = null;
        this._workerControl = null;
        this._workletModulePromise = null;
        // 屏幕常亮 (Screen Wake Lock) — issue #13。所有写入点见 _setWakeIntent。
        this._wakeGen = 0;          // 播放意图代数：任何意图变化都令在途 request() 失效
        this._wakeWanted = false;   // 当前是否应持有锁（仅由 _setPlaying 驱动）
        this._wakeLock = null;      // 唯一有效 WakeLockSentinel | null
        this._wakePending = null;   // 在途 request() 的 Promise
        this._wakePendingGen = -1;  // 在途请求对应的代数
        this._wakeRetryAt = 0;      // release 事件的重试冷却时间戳
        this._mediaSessionSongId = null; // 已写入 Media Session metadata 的曲目
        this._mediaPositionAt = 0;       // setPositionState 节流时间戳
        this.diagnostics = { firstFrameMs: 0, decodeRate: 0, underruns: 0,
            peakBufferMs: 0, memoryPeakBytes: 0 };

        // 配置
        this.sampleRate = 48000;
        this.onEnded = null;
        this.onPrev = null;         // Media Session 上一首（队列逻辑在 app.js）
        this.onNext = null;         // Media Session 下一首（队列逻辑在 app.js）
        this.onTimeUpdate = null;
        this.onError = null;
        this.onPlaybackStateChange = null;
        this.onDiagnostic = null;
        this._diagnostic = null;
        this._pendingDirectSeek = null;
        this._audioBuffer = null;
        this._audioBufferClockStart = 0;

        // 初始化 audio 元素
        this.audioEl.volume = this.volume;
        this.audioEl.addEventListener('timeupdate', () => this._onAudioTimeUpdate());
        this.audioEl.addEventListener('loadedmetadata', () => {
            if (this._pendingDirectSeek !== null && Number.isFinite(this.audioEl.duration)) {
                this.audioEl.currentTime = this._pendingDirectSeek * this.audioEl.duration;
                this._pendingDirectSeek = null;
            }
        });
        this.audioEl.addEventListener('ended', () => this._onEnded());
        this.audioEl.addEventListener('error', (e) => this._onAudioError(e));

        // 页面隐藏时 UA 会自动释放 wake lock，因此必须监听可见性变化，在回到前台时重新获取。
        // 必须无条件注册（不能只在成功获取后懒注册）：后台标签页自动续播会在 hidden 状态下
        // 发起 request()（必然 reject），若当时没有监听器，用户切回前台将永远不会重新获取。
        this._onWakeVisibilityChange = this._onWakeVisibilityChange.bind(this);
        this._bindWakeVisibility();
        this._bindMediaSessionHandlers();
    }

    // ── WASM 初始化 ──

    /**
     * 尝试加载 OrzAudioKit WASM 模块
     */
    async initWasm() {
        if (this._wasmLoadAttempted) return this._wasmLoadPromise;
        this._wasmLoadAttempted = true;

        this._wasmLoadPromise = (async () => {
            try {
                if (crossOriginIsolated && typeof SharedArrayBuffer !== 'undefined' &&
                    typeof AudioWorkletNode !== 'undefined') {
                    // Worker imports the format-specific bundle on demand.
                    this.wasmReady = true;
                    return true;
                }
                if (typeof OrzAudioKit === 'undefined') {
                    // 动态加载
                    const script = document.createElement('script');
                    script.src = '/audio/orz_audio.js?v=' + Date.now();
                    await new Promise((resolve, reject) => {
                        script.onload = resolve;
                        script.onerror = reject;
                        document.head.appendChild(script);
                    });
                }

                // 实例化 WASM 模块
                this.wasmKit = await OrzAudioKit();
                this.wasmReady = true;
                console.log('OrzAudioKit WASM loaded');
                return true;
            } catch (e) {
                console.warn('OrzAudioKit WASM not available, using fallback:', e.message);
                this.wasmReady = false;
                return false;
            }
        })();

        return this._wasmLoadPromise;
    }

    /**
     * WASM 就绪状态
     */
    get canUseWasm() {
        return this.wasmReady && this.wasmKit !== null;
    }

    prewarmWasm(format) {
        if (!crossOriginIsolated || typeof SharedArrayBuffer === 'undefined' ||
            typeof Worker === 'undefined') return Promise.resolve(false);
        const bundle = this._workerBundle(format);
        if (this._workerPool.has(bundle)) return Promise.resolve(true);
        if (this._workerWarmups.has(bundle)) return this._workerWarmups.get(bundle);
        const promise = new Promise(resolve => {
            const worker = new Worker('/audio/orz-decoder-worker.js?v=20260726-worker-reuse-v1');
            worker._orzBundle = bundle;
            const timeout = setTimeout(() => {
                worker.terminate();
                resolve(false);
            }, 30000);
            const finish = success => {
                clearTimeout(timeout);
                worker.onmessage = null;
                worker.onerror = null;
                if (success) this._storeWorker(worker);
                else worker.terminate();
                resolve(success);
            };
            worker.onmessage = event => {
                if (event.data?.type === 'warmed' && event.data.bundle === bundle) finish(true);
                else if (event.data?.type === 'warmup-error' && event.data.bundle === bundle) finish(false);
            };
            worker.onerror = () => finish(false);
            worker.postMessage({ type: 'warmup', bundle, moduleJs: this._workerModuleJs(bundle) });
        }).finally(() => {
            if (this._workerWarmups.get(bundle) === promise) this._workerWarmups.delete(bundle);
        });
        this._workerWarmups.set(bundle, promise);
        return promise;
    }

    // ── 播放接口 ──

    /**
     * 播放歌曲
     * @param {Object} song - { id, streamUrl, rawUrl, playStrategy, fileFormat, ... }
     */
    async play(song) {
        this.stop();
        const playGen = this._playGen;

        this.currentSong = song;
        this.isPlaying = false;
        this.currentTime = 0;
        this.duration = 0;
        this._syncMediaSession(song);

        const strategy = song.playStrategy || 'directFile';
        this._diagnostic = {
            generation: playGen,
            strategy,
            format: String(song.fileFormat || '').toLowerCase(),
            startedAt: this._now(),
            resourceFetchMs: null,
            wasmReadyMs: null,
            workerReadyMs: null,
            firstFrameMs: null,
            clickToPlayingMs: null,
            underruns: 0,
            fallbackUsed: false,
            emitted: false,
        };

        try {
            switch (strategy) {
                case 'directFile':
                case 'serverDecode':
                    await this._playDirect(song.streamUrl || song.rawUrl, playGen);
                    break;

                case 'wasmDecode':
                    await this._playWasm(song.streamUrl || song.rawUrl, song.fileFormat, song.subsong || 0, playGen);
                    break;

                default:
                    await this._playDirect(song.streamUrl || song.rawUrl, playGen);
            }
        } catch (e) {
            if (playGen !== this._playGen || e?.name === 'AbortError') return;
            console.error('Playback error:', e);
            // 最后尝试: server decode 降级
            if (strategy !== 'serverDecode') {
                try {
                    if (this._diagnostic?.generation === playGen) this._diagnostic.fallbackUsed = true;
                    await this._playDirect(song.streamUrl || song.rawUrl, playGen);
                } catch (fallbackErr) {
                    if (this.onError) this.onError(fallbackErr);
                }
            } else {
                if (this.onError) this.onError(e);
            }
        }
    }

    /**
     * 暂停/继续
     */
    async togglePlay() {
        if (!this.currentSong) return false;

        if (this._usingWasm) {
            if (this.isPlaying) {
                await this.audioCtx?.suspend();
                this._setPlaying(false);
            } else {
                await this.audioCtx?.resume();
                this._setPlaying(this.audioCtx?.state === 'running');
            }
        } else {
            if (this.isPlaying) {
                this.audioEl.pause();
                this._setPlaying(false);
            } else {
                try {
                    await this.audioEl.play();
                    this._setPlaying(true);
                } catch (error) {
                    this._setPlaying(false);
                    if (this.onError) this.onError(error);
                }
            }
        }
        return this.isPlaying;
    }

    /**
     * 停止播放
     */
    stop() {
        this._diagnostic = null;
        this._playGen++;
        this._fetchController?.abort();
        this._fetchController = null;
        // 标记流式中止——让后台的 _renderAndPlayStreaming 循环尽快退出
        this._streamActive = false;
        this._streamGen++;
        if (this._workerControl) {
            Atomics.store(this._workerControl, 2, 4);
            Atomics.store(this._workerControl, 3, this._streamGen);
            this._workerControl = null;
        }
        if (this._decoderWorker) {
            if (this._workerReject && this._workerRejectOwner === this._decoderWorker) {
                this._workerReject(this._cancelledPlayback());
            }
            this._workerReject = null;
            this._workerRejectOwner = null;
            this._shutdownWorker(this._decoderWorker, this._streamGen);
            this._decoderWorker = null;
        }
        if (this._workletNode) {
            this._workletNode.disconnect();
            this._workletNode = null;
        }

        // 停止 WASM 渲染
        if (this._washProgressRAF) {
            cancelAnimationFrame(this._washProgressRAF);
            this._washProgressRAF = null;
        }
        // 停止流式播放的所有 AudioBufferSourceNode
        if (this._streamSources) {
            for (const src of this._streamSources) {
                try { src.stop(); } catch(e) {}
                try { src.disconnect(); } catch(e) {}
            }
            this._streamSources = null;
        }
        if (this.currentSource) {
            try { this.currentSource.stop(); } catch(e) {}
            this.currentSource.disconnect();
            this.currentSource = null;
        }
        this._destroyWasmDecoder();

        // 停止 audio 元素
        this.audioEl.pause();
        this.audioEl.src = '';

        this._setPlaying(false);
        this._usingWasm = false;
        this._audioBuffer = null;
    }

    /**
     * 跳转到指定位置 (0-1)
     */
    seek(position) {
        position = Math.max(0, Math.min(1, Number(position) || 0));
        const target = position * this.duration;
        if (this._usingWasm) {
            if (this._decoderWorker) {
                this._decoderWorker.postMessage({ type: 'seek', generation: this._streamGen,
                    positionMs: Math.round(target * 1000) });
                return true;
            }
            if (this._audioBuffer) {
                this._restartAudioBufferAt(target);
                return true;
            }
            return false;
        } else if (Number.isFinite(this.audioEl.duration) && this.audioEl.duration > 0) {
            this.audioEl.currentTime = position * this.audioEl.duration;
            return true;
        }
        this._pendingDirectSeek = position;
        return true;
    }

    /**
     * 设置音量
     */
    setVolume(vol) {
        const value = Number(vol);
        this.volume = Number.isFinite(value) ? Math.max(0, Math.min(1, value)) : this.volume;
        // 当 directFile 通过 Web Audio 路由时，音量由 masterGain 控制
        // 避免同时衰减 audioEl.volume 造成双重衰减
        this.audioEl.volume = this._usesWebAudio ? 1 : this.volume;
        const gain = this._ensureMasterGain();
        if (gain) {
            gain.gain.cancelScheduledValues?.(this.audioCtx.currentTime);
            gain.gain.setValueAtTime?.(this.volume, this.audioCtx.currentTime);
            // Minimal test/browser implementations may only expose `.value`.
            gain.gain.value = this.volume;
        }
    }

    _ensureMasterGain() {
        if (!this.audioCtx || typeof this.audioCtx.createGain !== 'function') return null;
        if (!this.masterGain) {
            const gain = this.audioCtx.createGain();
            gain.gain.value = this.volume;
            gain.connect(this.audioCtx.destination);
            this.masterGain = gain;
        }
        return this.masterGain;
    }

    /**
     * 创建共享 AnalyserNode（幂等）
     * 返回 analyser 或 null（浏览器不支持时）
     * 链路：source → analyser → masterGain → destination
     */
    _ensureAnalyser() {
        if (!this.audioCtx || typeof this.audioCtx.createAnalyser !== 'function') return null;
        if (this.analyser) return this.analyser;
        const gain = this._ensureMasterGain();
        if (!gain) return null;
        const analyser = this.audioCtx.createAnalyser();
        analyser.fftSize = 2048;
        analyser.smoothingTimeConstant = 0.8;
        analyser.connect(gain);
        this.analyser = analyser;
        return analyser;
    }

    /**
     * 返回用于连接音频源的输出节点
     * 优先 analyser，回退 masterGain，最后 destination
     */
    _outputNode() {
        return this._ensureAnalyser() || this._ensureMasterGain() || this.audioCtx?.destination;
    }

    /**
     * 获取共享分析器（只读）
     * @returns {AnalyserNode|null}
     */
    getAnalyser() {
        return this.analyser || null;
    }

    /**
     * 将 <audio> 元素幂等接入共享分析链路。
     * 任一步失败都保留原生 audioEl 输出和音量控制。
     */
    _ensureDirectAudioGraph() {
        try {
            if (!this.audioCtx) {
                const AudioContextClass = window.AudioContext || window.webkitAudioContext;
                if (!AudioContextClass) return false;
                this.audioCtx = new AudioContextClass();
            }
            if (this.audioCtx.state === 'suspended') {
                this.audioCtx.resume().catch(() => {});
            }
            const analyser = this._ensureAnalyser();
            if (!analyser || typeof this.audioCtx.createMediaElementSource !== 'function') return false;
            if (!this.mediaElementSource) {
                this.mediaElementSource = this.audioCtx.createMediaElementSource(this.audioEl);
            }
            if (!this._mediaElementConnected) {
                this.mediaElementSource.connect(analyser);
                this._mediaElementConnected = true;
            }
            this._usesWebAudio = true;
            this.audioEl.volume = 1;
            return true;
        } catch (_) {
            // createMediaElementSource 成功后，媒体输出已归 Web Audio 管理；
            // 若 analyser 连接失败，至少接到 gain/destination，避免静音。
            if (this.mediaElementSource && !this._mediaElementConnected) {
                try {
                    const fallbackOutput = this._ensureMasterGain() || this.audioCtx?.destination;
                    if (fallbackOutput) {
                        this.mediaElementSource.connect(fallbackOutput);
                        this._mediaElementConnected = true;
                        this._usesWebAudio = true;
                        this.audioEl.volume = 1;
                        return true;
                    }
                } catch (_) {}
            }
            this._usesWebAudio = false;
            this.audioEl.volume = this.volume;
            return false;
        }
    }

    // ── 内部方法 ──

    /**
     * 直接文件播放 (directFile / serverDecode)
     * 尝试将 audio 元素接入 Web Audio 分析链路；失败时回退原生播放
     */
    async _playDirect(url, playGen = this._playGen) {
        this._assertCurrentPlayback(playGen);
        this._usingWasm = false;
        this.audioEl.src = url;

        // 尝试创建/恢复 AudioContext 并将 audio 元素接入分析器
        if (!this._ensureDirectAudioGraph()) {
            this.audioEl.volume = this.volume;
        }

        await this.audioEl.play();
        this._assertCurrentPlayback(playGen);
        this._markFirstFrame(playGen);
        this._setPlaying(true);
        this.duration = this.audioEl.duration || 0;
    }

    /**
     * WASM 解码播放 (wasmDecode)
     */
    async _playWasm(url, format, subsong = 0, playGen = this._playGen) {
        this._assertCurrentPlayback(playGen);
        this._usingWasm = true;
        if (!this.wasmReady) await this.initWasm();
        this._markDiagnostic(playGen, 'wasmReadyMs');

        // 初始化 AudioContext（需用户交互后创建）
        if (!this.audioCtx) {
            this.audioCtx = new (window.AudioContext || window.webkitAudioContext)();
        }
        this._ensureMasterGain();
        if (this.audioCtx.state === 'suspended') {
            // Do not block decoder priming on an AudioContext resume promise:
            // some browsers keep it pending until the output device is ready.
            // Calling resume synchronously preserves user activation while the
            // Worker fills its initial ring in parallel.
            this.audioCtx.resume().catch(() => {});
        }

        if (crossOriginIsolated && typeof SharedArrayBuffer !== 'undefined' && this.audioCtx.audioWorklet) {
            await this._playWithWorker(url, format, subsong, playGen);
            return;
        }

        // 尝试 WASM 解码
        if (this.canUseWasm) {
            try {
                // 前置检查格式是否被 WASM 支持
                const supported = this.wasmKit._orz_audio_can_decode
                    ? this.wasmKit.ccall('orz_audio_can_decode', 'number', ['string'], [format])
                    : 0;
                if (!supported) {
                    throw new Error(`WASM: format "${format}" not supported by WASM module`);
                }

                await this._playWithWasm(url, format, subsong, playGen);
                this._markFirstFrame(playGen);
                return;
            } catch (e) {
                console.warn('WASM decode failed, falling back:', e.message);
            }
        }

        // Fallback: AudioContext.decodeAudioData
        await this._playWithAudioContext(url, playGen);
        this._markFirstFrame(playGen);
    }

    /**
     * 使用 WASM 模块解码并播放（统一 API）
     */
    async _playWithWasm(url, format, subsong = 0, playGen = this._playGen) {
        let step = 'fetch';
        try {
            step = 'fetch';
            const fetchStartedAt = this._now();
            const resp = await fetch(url);
            if (!resp.ok) throw new Error(`HTTP ${resp.status} ${resp.statusText}`);
            step = 'arrayBuffer';
            const buf = await resp.arrayBuffer();
            step = 'Uint8Array';
            const data = new Uint8Array(buf);
            this._markDiagnostic(playGen, 'resourceFetchMs', this._now() - fetchStartedAt);
            console.log(`WASM: fetched ${data.length} bytes for ${format}`);

            step = 'malloc_fmt';
            const fmtLen = this.wasmKit.lengthBytesUTF8(format) + 1;
            const fmtPtr = this.wasmKit._malloc(fmtLen);
            if (!fmtPtr) throw new Error('malloc fmt failed');
            step = 'stringToUTF8';
            this.wasmKit.stringToUTF8(format, fmtPtr, fmtLen);

            step = 'malloc_data';
            const dataPtr = this.wasmKit._malloc(data.length);
            if (!dataPtr) throw new Error('malloc data failed');
            step = 'copy_data';
            this.wasmKit.HEAPU8.set(data, dataPtr);

            step = 'orz_decoder_create_memory';
            const handlePtr = this.wasmKit._malloc(4);
            this.wasmKit.HEAPU32[handlePtr >> 2] = 0;
            const createStatus = this.wasmKit._orz_decoder_create_memory(dataPtr, data.length, fmtPtr, 0, handlePtr);
            const decoderHandle = this.wasmKit.HEAPU32[handlePtr >> 2];
            this.wasmKit._free(handlePtr);
            this.wasmKit._free(fmtPtr);
            this.wasmKit._free(dataPtr);
            console.log('WASM: decoder handle =', decoderHandle);

            if (createStatus !== 0 || !decoderHandle) {
                throw new Error(`WASM: failed to load module (status ${createStatus})`);
            }
            this.decoderHandle = decoderHandle;
            if (subsong > 0 && this.wasmKit._orz_decoder_select_subsong_v1(decoderHandle, subsong) !== 0) {
                throw new Error(`WASM: subsong ${subsong} is not supported`);
            }

            step = 'get_stream_info';
            const infoPtr = this.wasmKit._malloc(64);
            this.wasmKit.HEAPU8.fill(0, infoPtr, infoPtr + 64);
            this.wasmKit.HEAPU32[infoPtr >> 2] = 64;
            this.wasmKit.HEAPU32[(infoPtr + 4) >> 2] = this.wasmKit._orz_abi_version();
            const infoStatus = this.wasmKit._orz_decoder_get_stream_info(decoderHandle, infoPtr);
            if (infoStatus !== 0) throw new Error(`WASM: stream info failed (status ${infoStatus})`);
            const duration = this.wasmKit.HEAPF64[(infoPtr + 16) >> 3];
            console.log('WASM: duration =', duration);
            if (duration > 0) this.duration = duration;

            const sampleRate = this.wasmKit.HEAPU32[(infoPtr + 8) >> 2] || 44100;
            const channels = this.wasmKit.HEAPU32[(infoPtr + 12) >> 2] || 2;
            this.wasmKit._free(infoPtr);
            console.log('WASM: sr=', sampleRate, 'ch=', channels);

            const totalFrames = Math.ceil(duration * sampleRate);
            if (totalFrames <= 0 || totalFrames > 3600 * sampleRate) {
                this._destroyWasmDecoder();
                throw new Error(`WASM: invalid duration ${duration}s (frames ${totalFrames})`);
            }

            // 流式渲染 + 播放：小块渲染 → 立即通过 AudioContext 调度播放
            step = 'streaming';
            await this._renderAndPlayStreaming(sampleRate, channels);
        } catch (e) {
            console.error(`WASM decode failed at step "${step}":`, e.message, e);
            this._destroyWasmDecoder();
            throw e;
        }
    }

    async _playWithWorker(url, format, subsong = 0, playGen = this._playGen) {
        const startedAt = performance.now();
        const fetchController = new AbortController();
        this._fetchController = fetchController;
        let data;
        try {
            const fetchStartedAt = this._now();
            const response = await fetch(url, { signal: fetchController.signal });
            if (!response.ok) throw new Error(`HTTP ${response.status} ${response.statusText}`);
            data = await response.arrayBuffer();
            this._markDiagnostic(playGen, 'resourceFetchMs', this._now() - fetchStartedAt);
        } finally {
            if (this._fetchController === fetchController) this._fetchController = null;
        }
        this._assertCurrentPlayback(playGen);
        const generation = ++this._streamGen;
        const channels = 2;
        const capacityFrames = Math.ceil(44100 * 0.5) + 1;
        const startFrames = Math.ceil(44100 * 0.15);
        const controlBuffer = new SharedArrayBuffer(Int32Array.BYTES_PER_ELEMENT * 8);
        const sampleBuffer = new SharedArrayBuffer(Float32Array.BYTES_PER_ELEMENT * capacityFrames * channels);
        this.diagnostics.peakBufferMs = capacityFrames / 44.1;
        this.diagnostics.memoryPeakBytes = controlBuffer.byteLength + sampleBuffer.byteLength;
        const control = new Int32Array(controlBuffer);
        Atomics.store(control, 3, generation);
        this._workerControl = control;
        const bundle = this._workerBundle(format);
        const worker = await this._takeWorker(bundle);
        this._assertCurrentPlayback(playGen);
        this._decoderWorker = worker;
        let workletNode = null;

        const ready = new Promise((resolve, reject) => {
            this._workerReject = reject;
            this._workerRejectOwner = worker;
            worker.onmessage = async event => {
                const message = event.data;
                if (message.generation !== generation) return;
                if (message.type === 'ready') {
                    if (playGen !== this._playGen) return reject(this._cancelledPlayback());
                    this.duration = message.duration;
                    this._markDiagnostic(playGen, 'workerReadyMs');
                    await this._ensureWorkletModule();
                    if (playGen !== this._playGen) return reject(this._cancelledPlayback());
                    workletNode = new AudioWorkletNode(this.audioCtx, 'orz-ring-buffer', {
                        numberOfOutputs: 1,
                        outputChannelCount: [channels],
                        processorOptions: { control: controlBuffer, samples: sampleBuffer, capacityFrames,
                            channels, generation, sourceSampleRate: message.sampleRate }
                    });
                    this._workletNode = workletNode;
                    workletNode.connect(this._outputNode());
                    resolve();
                } else if (message.type === 'started') {
                    if (playGen !== this._playGen) return;
                    this.diagnostics.firstFrameMs = performance.now() - startedAt;
                    this._markFirstFrame(playGen);
                    this._setPlaying(this.audioCtx.state === 'running');
                } else if (message.type === 'seeked') {
                    this.currentTime = message.positionMs / 1000;
                    this._workerTimeOffset = this.currentTime;
                    this._workerClockStart = this.audioCtx.currentTime;
                    if (this.onTimeUpdate) this.onTimeUpdate(this.currentTime, this.duration);
                } else if (message.type === 'ended') {
                    this.diagnostics.decodeRate = message.decodeRate;
                    if (this._decoderWorker === worker) this._decoderWorker = null;
                    this._storeWorker(worker);
                } else if (message.type === 'error') reject(new Error(message.message));
            };
            worker.onerror = event => reject(new Error(event.message));
        });
        const moduleJs = this._workerModuleJs(bundle);
        worker.postMessage({ type: 'decode', generation, format, subsong, moduleJs, data, control: controlBuffer,
            samples: sampleBuffer, capacityFrames, channels, startFrames }, [data]);
        try {
            await ready;
            if (this._workerRejectOwner === worker) {
                this._workerReject = null;
                this._workerRejectOwner = null;
            }
        } catch (error) {
            if (this._workerRejectOwner === worker) {
                this._workerReject = null;
                this._workerRejectOwner = null;
            }
            worker.terminate();
            if (this._decoderWorker === worker) this._decoderWorker = null;
            if (this._workerControl === control) this._workerControl = null;
            workletNode?.disconnect();
            if (this._workletNode === workletNode) this._workletNode = null;
            throw error;
        }

        this._workerClockStart = this.audioCtx.currentTime;
        this._workerTimeOffset = 0;
        const tick = () => {
            if (this._streamGen !== generation) return;
            this.currentTime = Math.min(this._workerTimeOffset + this.audioCtx.currentTime - this._workerClockStart,
                this.duration || Infinity);
            this.diagnostics.underruns = Atomics.load(control, 4);
            this._syncMediaSessionPosition();
            if (this.onTimeUpdate) this.onTimeUpdate(this.currentTime, this.duration);
            if (Atomics.load(control, 2) === 2 && this.currentTime >= this.duration) return this._onEnded();
            if (Atomics.load(control, 2) === 3) return;
            this._washProgressRAF = requestAnimationFrame(tick);
        };
        this._washProgressRAF = requestAnimationFrame(tick);
    }

    _workerBundle(format) {
        return ['bp', 'mid', 'ym'].includes(String(format).toLowerCase()) ? 'builtin' : 'full';
    }

    _workerModuleJs(bundle) {
        return bundle === 'builtin'
            ? '/audio/orz_audio_builtin.js?v=20260717-controls-seek-v1'
            : '/audio/orz_audio.js?v=20260717-controls-seek-v1';
    }

    async _takeWorker(bundle) {
        if (this._workerWarmups.has(bundle)) await this._workerWarmups.get(bundle);
        const pooled = this._workerPool.get(bundle);
        if (pooled) {
            this._workerPool.delete(bundle);
            return pooled;
        }
        const worker = new Worker('/audio/orz-decoder-worker.js?v=20260726-worker-reuse-v1');
        worker._orzBundle = bundle;
        return worker;
    }

    _storeWorker(worker) {
        const bundle = worker?._orzBundle;
        if (this._disposed) return worker?.terminate();
        if (!bundle) return worker?.terminate();
        worker.onmessage = null;
        worker.onerror = null;
        const previous = this._workerPool.get(bundle);
        if (previous && previous !== worker) previous.terminate();
        this._workerPool.set(bundle, worker);
    }

    _shutdownWorker(worker, stopGeneration) {
        let terminated = false;
        const terminate = () => {
            if (terminated) return;
            terminated = true;
            clearTimeout(timeout);
            worker.terminate();
        };
        const timeout = setTimeout(terminate, 250);
        worker.onmessage = event => {
            if (event.data?.type === 'stopped' && event.data.generation === stopGeneration) {
                terminated = true;
                clearTimeout(timeout);
                this._storeWorker(worker);
            }
        };
        try { worker.postMessage({ type: 'stop', generation: stopGeneration }); }
        catch (_) { terminate(); }
    }

    async _ensureWorkletModule() {
        if (!this._workletModulePromise) {
            this._workletModulePromise = this.audioCtx.audioWorklet
                .addModule('/audio/orz-audio-worklet.js?v=20260717-ym-zero-period')
                .catch(error => {
                    this._workletModulePromise = null;
                    throw error;
                });
        }
        return this._workletModulePromise;
    }

    _cancelledPlayback() {
        return new DOMException('Playback superseded by a newer request', 'AbortError');
    }

    _assertCurrentPlayback(playGen) {
        if (playGen !== this._playGen) throw this._cancelledPlayback();
    }

    /**
     * 使用 AudioContext.decodeAudioData 播放
     */
    async _playWithAudioContext(url, playGen = this._playGen) {
        const fetchStartedAt = this._now();
        const resp = await fetch(url);
        const buf = await resp.arrayBuffer();
        this._markDiagnostic(playGen, 'resourceFetchMs', this._now() - fetchStartedAt);
        const audioBuffer = await this.audioCtx.decodeAudioData(buf);
        this._playAudioBuffer(audioBuffer);
    }

    _now() {
        return globalThis.performance?.now?.() ?? Date.now();
    }

    _markDiagnostic(playGen, field, value = null) {
        const diagnostic = this._diagnostic;
        if (!diagnostic || diagnostic.generation !== playGen || diagnostic.emitted) return;
        diagnostic[field] = value === null ? this._now() - diagnostic.startedAt : Math.max(0, value);
    }

    _markFirstFrame(playGen) {
        const diagnostic = this._diagnostic;
        if (!diagnostic || diagnostic.generation !== playGen || diagnostic.emitted) return;
        const elapsed = this._now() - diagnostic.startedAt;
        diagnostic.firstFrameMs = elapsed;
        diagnostic.clickToPlayingMs = elapsed;
        diagnostic.underruns = Number(this.diagnostics.underruns) || 0;
        diagnostic.emitted = true;
        if (this.onDiagnostic) {
            this.onDiagnostic({
                strategy: diagnostic.strategy,
                format: diagnostic.format,
                resourceFetchMs: diagnostic.resourceFetchMs,
                wasmReadyMs: diagnostic.wasmReadyMs,
                workerReadyMs: diagnostic.workerReadyMs,
                firstFrameMs: diagnostic.firstFrameMs,
                clickToPlayingMs: diagnostic.clickToPlayingMs,
                underruns: diagnostic.underruns,
                fallbackUsed: diagnostic.fallbackUsed,
            });
        }
    }

    /**
     * 播放 AudioBuffer
     */
    _playAudioBuffer(buffer, offset = 0) {
        if (this.currentSource) {
            try { this.currentSource.stop(); } catch(e) {}
            this.currentSource.disconnect();
        }

        this._audioBuffer = buffer;
        this.currentSource = this.audioCtx.createBufferSource();
        this.currentSource.buffer = buffer;
        this.currentSource.connect(this._outputNode());
        this.currentSource.start(0, offset);
        this._setPlaying(true);
        this.duration = buffer.duration;

        this._audioBufferClockStart = this.audioCtx.currentTime - offset;
        const tick = () => {
            if (!this.isPlaying) return;
            this.currentTime = this.audioCtx.currentTime - this._audioBufferClockStart;
            this._syncMediaSessionPosition();
            if (this.onTimeUpdate) this.onTimeUpdate(this.currentTime, this.duration);
            if (this.currentTime < this.duration) {
                this._washProgressRAF = requestAnimationFrame(tick);
            } else {
                this._onEnded();
            }
        };
        this._washProgressRAF = requestAnimationFrame(tick);
    }

    _restartAudioBufferAt(offset) {
        if (!this._audioBuffer) return false;
        const wasPlaying = this.isPlaying;
        if (this.currentSource) {
            try { this.currentSource.stop(); } catch (_) {}
            this.currentSource.disconnect();
        }
        this.currentSource = this.audioCtx.createBufferSource();
        this.currentSource.buffer = this._audioBuffer;
        this.currentSource.connect(this._outputNode());
        this.currentSource.start(0, offset);
        this.currentTime = offset;
        this._audioBufferClockStart = this.audioCtx.currentTime - offset;
        if (!wasPlaying) Promise.resolve(this.audioCtx.suspend()).catch(() => {});
        if (this.onTimeUpdate) this.onTimeUpdate(this.currentTime, this.duration);
        return true;
    }

    /**
     * 流式渲染 + 播放：逐小块渲染并通过 AudioContext 调度播放
     *
     * 每次 orz_render 处理小块帧（~2048 帧），创建 AudioBuffer，
     * 使用 BufferSourceNode.start(playTime) 调度到准确时间播放。
     * 块之间 await 让出主线程，浏览器保持响应。
     */
    async _renderAndPlayStreaming(sampleRate, channels) {
        const CHUNK_FRAMES = Math.min(22050, Math.max(1024, Math.round(sampleRate / 2)));
        this._streamSources = [];  // 清空并保持引用，stop() 能直接操作
        const myGen = ++this._streamGen;
        this._streamActive = true;

        let firstPlayTime = this.audioCtx.currentTime;
        let playTime = firstPlayTime;
        let totalRendered = 0;
        // 安全上限：最多渲染 duration 的 1.5 倍帧数，防止解码器不返回 0 时无限循环
        const maxFrames = Math.ceil(this.duration * sampleRate * 1.5);
        // 渲染起始时间，用于检测渲染耗时是否远超实时导致卡死
        const renderStart = Date.now();

        while (this._streamActive && this._streamGen === myGen) {
            if (totalRendered >= maxFrames) {
                console.log('WASM: render complete (cap)');
                break;
            }
            // 如果渲染耗时超过 60 秒（实时），主动中止以防页面卡死
            if (Date.now() - renderStart > 60000) {
                console.log('WASM: render timeout (60s)');
                break;
            }
            const chunkPtr = this.wasmKit._malloc(CHUNK_FRAMES * channels * 4);
            if (!chunkPtr) break;

            const renderedPtr = this.wasmKit._malloc(4);
            const status = this.wasmKit._orz_decoder_render_f32(this.decoderHandle, chunkPtr, CHUNK_FRAMES, renderedPtr);
            const frames = this.wasmKit.HEAPU32[renderedPtr >> 2];
            this.wasmKit._free(renderedPtr);
            if (status === 1 || frames <= 0) {
                this.wasmKit._free(chunkPtr);
                break;
            }
            if (status !== 0) {
                this.wasmKit._free(chunkPtr);
                throw new Error(`WASM render failed (status ${status})`);
            }

            // 从 WASM heap 拷贝（free 前必须拷贝）
            const view = new Float32Array(
                this.wasmKit.HEAPU8.buffer, chunkPtr, frames * channels
            );
            const copy = new Float32Array(view);
            this.wasmKit._free(chunkPtr);

            // 创建 AudioBuffer
            const audioBuffer = this.audioCtx.createBuffer(channels, frames, sampleRate);
            if (channels === 2) {
                const left = audioBuffer.getChannelData(0);
                const right = audioBuffer.getChannelData(1);
                for (let i = 0; i < frames; i++) {
                    left[i] = copy[i * 2];
                    right[i] = copy[i * 2 + 1];
                }
            } else {
                audioBuffer.getChannelData(0).set(copy);
            }

            // 调度播放 — 直接注册到 this._streamSources 以便 stop() 能立即停止
            const source = this.audioCtx.createBufferSource();
            source.buffer = audioBuffer;
            source.connect(this._outputNode());
            source.start(playTime);
            this._streamSources.push(source);

            playTime += frames / sampleRate;
            totalRendered += frames;

            // 让出主线程，保持 UI 响应
            await new Promise(r => setTimeout(r, 0));
        }

        // 如果被 stop() 中断（新歌已开始），直接退出
        if (!this._streamActive || this._streamGen !== myGen) {
            this._destroyWasmDecoder();
            return;
        }

        if (totalRendered <= 0) {
            this._destroyWasmDecoder();
            throw new Error('WASM: no audio rendered');
        }

        // 用实际渲染帧数更新时长
        this.duration = totalRendered / sampleRate;

        // 清理 WASM 解码器
        this._destroyWasmDecoder();

        this.currentSource = (this._streamSources && this._streamSources[this._streamSources.length - 1]) || null;
        this._setPlaying(true);

        // 时间进度跟踪
        const tick = () => {
            if (!this.isPlaying) return;
            this.currentTime = Math.min(
                this.audioCtx.currentTime - firstPlayTime,
                this.duration
            );
            this._syncMediaSessionPosition();
            if (this.onTimeUpdate) {
                this.onTimeUpdate(this.currentTime, this.duration);
            }
            if (this.currentTime >= this.duration) {
                this._setPlaying(false);
                this._onEnded();
                return;
            }
            this._washProgressRAF = requestAnimationFrame(tick);
        };
        this._washProgressRAF = requestAnimationFrame(tick);
    }

    _destroyWasmDecoder() {
        if (!this.decoderHandle || !this.wasmKit) return;
        try { this.wasmKit._orz_decoder_destroy_v1(this.decoderHandle); } catch (_) {}
        this.decoderHandle = 0;
    }

    // ── 事件处理 ──

    _onAudioTimeUpdate() {
        if (this.audioEl.duration) {
            this.currentTime = this.audioEl.currentTime;
            this.duration = this.audioEl.duration;
            this._syncMediaSessionPosition();
            if (this.onTimeUpdate) {
                this.onTimeUpdate(this.currentTime, this.duration);
            }
        }
    }

    _onEnded() {
        this._setPlaying(false);
        if (this.onEnded) this.onEnded();
    }

    _setPlaying(value) {
        const next = Boolean(value);
        if (this.isPlaying === next) return;
        this.isPlaying = next;
        // isPlaying 先落地，唤醒层与 Media Session 才不会读到过期真值；
        // 二者内部全部静默降级，绝不影响播放。
        this._setWakeIntent(next);
        this._syncMediaSessionPlaybackState(next);
        if (this.onPlaybackStateChange) this.onPlaybackStateChange(next);
    }

    _onAudioError(e) {
        // WASM 路径播放时，audio element 的 error 来自之前播放的 fallback 尝试，无害
        if (this._usingWasm) return;
        console.error('Audio element error:', e);
        if (this.onError) this.onError(e);
    }

    // ── 屏幕常亮 (Screen Wake Lock) ──
    //
    // 约束（issue #13）：
    //   1. 触发点唯一：_setPlaying() → _setWakeIntent()，覆盖所有播放路径。
    //   2. play() 先 stop() 再异步解码播放，所以 acquire 相对 release 是「迟到」的：
    //      _wakeGen 令被取代的在途 request() 自行归还，避免「已停止却仍持有锁」。
    //   3. 页面隐藏时 UA 会自动释放；request() 在隐藏 / 低电量模式 / 无权限时会 reject。
    //   4. 任何失败都不得影响播放 —— 全部静默降级。
    //   5. 只能用 globalThis.document / globalThis.navigator 访问：Tests/Browser 的 vm
    //      沙箱没有这两个全局，裸引用会在 new OrzAudioPlayer() 时抛 ReferenceError。

    /** 播放意图变化 —— 唯一的意图写入点。意图变化即递增代数，使在途 request() 全部作废。 */
    _setWakeIntent(wanted) {
        const next = Boolean(wanted);
        if (this._wakeWanted === next) return;
        this._wakeWanted = next;
        this._wakeGen++;
        if (next) this._syncWakeLock();
        else this._releaseWakeSentinel();
    }

    /** 让意图与实际持有对齐；可重复调用（visibilitychange 与 release 重试都会走这里）。 */
    _syncWakeLock() {
        if (this._disposed || !this._wakeWanted) return;
        // 页面不可见时 request() 必然 reject，等回到前台再取。
        if (globalThis.document?.hidden === true) return;
        if (this._wakeLock) return;
        // 同一代已有在途请求：不重复发起（hide→show 抖动会走到这里）。
        if (this._wakePending && this._wakePendingGen === this._wakeGen) return;
        this._acquireWakeLock(this._wakeGen);
    }

    /**
     * 发起一次 wake lock 请求 —— fire-and-forget，绝不抛出、也绝不被 await。
     * @param {number} gen 发起时的代数，用于 settle 时判定是否已被取代
     */
    _acquireWakeLock(gen) {
        const wakeLock = globalThis.navigator?.wakeLock;
        // 非安全上下文 (http:// LAN IP) 与 iOS < 16.4 没有 wakeLock：静默降级
        if (!wakeLock || typeof wakeLock.request !== 'function') return;
        if (this._disposed || !this._wakeWanted) return;

        let request;
        try {
            // 必须保留 receiver：解构或透传 request 会触发 "Illegal invocation"
            request = wakeLock.request('screen');
        } catch (_) {
            return; // 某些引擎对未知 type 会同步抛出
        }
        if (!request || typeof request.then !== 'function') return;

        this._wakePending = request;
        this._wakePendingGen = gen;

        const settle = () => {
            if (this._wakePending === request) {
                this._wakePending = null;
                this._wakePendingGen = -1;
            }
        };

        // .then 本身也可能同步抛出（非标准 thenable）。本方法从 _setPlaying 同步调用，
        // 抛出会一路冒到音频链路，所以整段都包起来。
        try {
            request.then((sentinel) => {
                settle();
                // sentinel 为空/缺方法：无法持有也无法监听，直接放弃（不抛）。
                if (!sentinel || typeof sentinel.addEventListener !== 'function') {
                    this._releaseSentinel(sentinel);
                    return;
                }
                // 已被取代（stop/pause/换歌）、页面已隐藏、已销毁，或已有更新的句柄胜出：
                // 立刻归还，绝不留「isPlaying === false 却仍持有锁」的野句柄。
                if (gen !== this._wakeGen || !this._wakeWanted || this._disposed ||
                    globalThis.document?.hidden === true || this._wakeLock) {
                    this._releaseSentinel(sentinel);
                    return;
                }
                this._wakeLock = sentinel;
                sentinel.addEventListener('release', () => this._onWakeLockReleased(sentinel));
            }, () => {
                // 隐藏 / 低电量模式 / 权限被拒：静默放弃。
                // 被拒的请求不产生 sentinel，也就不会触发 release，因此不会形成循环。
                settle();
            });
        } catch (_) {
            settle();
        }
    }

    /** 释放当前持有的锁（幂等；不改变意图、不递增代数）。 */
    _releaseWakeSentinel() {
        const sentinel = this._wakeLock;
        this._wakeLock = null;
        this._wakePending = null;
        this._wakePendingGen = -1;
        if (sentinel) this._releaseSentinel(sentinel);
    }

    /** 归还一个 sentinel，吞掉同步异常与 rejected promise。 */
    _releaseSentinel(sentinel) {
        try {
            const released = sentinel?.release?.();
            if (released && typeof released.catch === 'function') released.catch(() => {});
        } catch (_) {}
    }

    /** UA 单方面释放了锁（页面隐藏 / 省电模式 / UA 政策）；只有当前有效句柄才允许改动状态。 */
    _onWakeLockReleased(sentinel) {
        if (this._wakeLock !== sentinel) return; // 已废弃的 sentinel：不得清掉新句柄
        this._wakeLock = null;
        if (this._disposed || !this._wakeWanted) return;
        if (globalThis.document?.hidden === true) return; // 回前台时 visibilitychange 会重取
        const now = this._now();
        if (now < this._wakeRetryAt) return; // 冷却：防止 UA 立刻释放导致的请求风暴
        this._wakeRetryAt = now + 5000;
        this._syncWakeLock();
    }

    _bindWakeVisibility() {
        globalThis.document?.addEventListener?.('visibilitychange', this._onWakeVisibilityChange);
    }

    _unbindWakeVisibility() {
        globalThis.document?.removeEventListener?.('visibilitychange', this._onWakeVisibilityChange);
    }

    _onWakeVisibilityChange() {
        if (this._disposed) return;
        if (globalThis.document?.hidden === true) {
            // UA 已自动释放，这里立即丢弃句柄，避免把过期 sentinel 当成持有中。
            // 必须一并递增代数：隐藏前发出的 request() 仍在途，若不作废，它可能在回到前台
            // 之后才 resolve —— 那时 _wakeLock 还是空的，于是这个「UA 早已释放、release
            // 事件也已经错过」的 sentinel 会被当成持有中，导致 _syncWakeLock 永不重取、
            // 播放中屏幕却照常熄灭。
            this._wakeGen++;
            this._wakeLock = null;
            this._wakePending = null;
            this._wakePendingGen = -1;
            // 意图 (_wakeWanted) 保持不变：回到前台必须重新获取。
            return;
        }
        this._wakeRetryAt = 0; // 新的前台周期 = 新的机会（低电量模式可能已经结束）
        this._syncWakeLock();
    }

    // ── Media Session（锁屏 / 通知栏控制）──
    //
    // 同样全部静默降级：vm 沙箱与不支持的浏览器下 navigator.mediaSession 为 undefined。
    // 上/下一首的队列逻辑在 app.js（依赖 activeList/currentIndex），故经 onPrev/onNext 外派。

    _mediaSession() {
        const session = globalThis.navigator?.mediaSession;
        return session && typeof session === 'object' ? session : null;
    }

    /** 按曲目写入锁屏元数据。仓库无封面图资源，故不设 artwork。 */
    _syncMediaSession(song) {
        const session = this._mediaSession();
        if (!session || !song) return;
        if (this._mediaSessionSongId != null && this._mediaSessionSongId === song.id) return;
        try {
            if (typeof globalThis.MediaMetadata !== 'function') return;
            session.metadata = new globalThis.MediaMetadata({
                title: song.title || '',
                artist: song.artist || '',
                album: song.album || '',
            });
        } catch (_) { return; }
        // 只有写入成功才记账：否则 MediaMetadata 尚不可用时提前缓存 id，
        // 之后同一首歌会被去重挡在门外，元数据永远补不上。
        this._mediaSessionSongId = song.id ?? null;
    }

    _syncMediaSessionPlaybackState(isPlaying) {
        const session = this._mediaSession();
        if (!session) return;
        try {
            session.playbackState = isPlaying ? 'playing' : (this.currentSong ? 'paused' : 'none');
        } catch (_) {}
    }

    /**
     * 同步锁屏进度条。setPositionState 对非法值会抛异常，且热路径（RAF 循环）会高频调用，
     * 因此这里做 1 秒节流 + 合法性校验。
     */
    _syncMediaSessionPosition() {
        const session = this._mediaSession();
        if (!session || typeof session.setPositionState !== 'function') return;
        const { duration, currentTime: position } = this;
        if (!Number.isFinite(duration) || duration <= 0) return;
        if (!Number.isFinite(position) || position < 0 || position > duration) return;
        // 先校验再计时：曲目刚起的头几拍 duration 还是 0/NaN，若先计时会让这几拍白白吃掉
        // 一整个窗口，等 duration 就绪后还要再等 1 秒锁屏进度才动。
        const now = this._now();
        if (now - this._mediaPositionAt < 1000) return;
        this._mediaPositionAt = now;
        try {
            session.setPositionState({ duration, playbackRate: 1, position });
        } catch (_) {}
    }

    _bindMediaSessionHandlers() {
        const session = this._mediaSession();
        if (!session || typeof session.setActionHandler !== 'function') return;
        const seekRelative = (delta) => {
            if (!Number.isFinite(this.duration) || this.duration <= 0) return;
            return this.seek((this.currentTime + delta) / this.duration);
        };
        const handlers = {
            play: () => { if (!this.isPlaying) void this.togglePlay(); },
            pause: () => { if (this.isPlaying) void this.togglePlay(); },
            // 刻意不注册 stop：stop() 会清空音源（audioEl.src='' / 销毁 WASM 解码器）却保留
            // currentSong，于是锁屏会停在「已暂停」且播放键看似可用，按下却必然失败并弹错误提示。
            // app 内也没有停止入口（stop() 只被 play() 与 dispose() 调用），暂停已覆盖该需求，
            // 主流音乐应用的锁屏同样只有播放/暂停/上下一首。缺省不注册即可，无需置 null。
            seekbackward: (details) => seekRelative(-(details?.seekOffset || 10)),
            seekforward: (details) => seekRelative(details?.seekOffset || 10),
            seekto: (details) => {
                if (!Number.isFinite(this.duration) || this.duration <= 0) return;
                if (!Number.isFinite(details?.seekTime)) return;
                return this.seek(details.seekTime / this.duration);
            },
            previoustrack: () => { if (this.onPrev) this.onPrev(); },
            nexttrack: () => { if (this.onNext) this.onNext(); },
        };
        for (const [action, handler] of Object.entries(handlers)) {
            try { session.setActionHandler(action, handler); } catch (_) {}
        }
    }

    /**
     * 释放资源
     */
    dispose() {
        this._disposed = true;          // 在途 acquire 会在 settle 时因 _disposed 自行归还
        this._unbindWakeVisibility();   // 先解绑，避免关闭过程中被 visibilitychange 进入
        this.stop();                    // → _setPlaying(false) → _setWakeIntent(false) → 释放
        // 兜底：isPlaying 本就是 false 时 _setWakeIntent 会早退，这里确保不留残句柄。
        this._wakeWanted = false;
        this._releaseWakeSentinel();
        this._mediaSessionSongId = null;
        for (const worker of this._workerPool.values()) worker.terminate();
        this._workerPool.clear();
        if (this.audioCtx) {
            this.audioCtx.close();
            this.audioCtx = null;
        }
        this.analyser = null;
        this.masterGain = null;
        this.mediaElementSource = null;
        this._mediaElementConnected = false;
        this._usesWebAudio = false;
    }
}
