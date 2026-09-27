import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const source = await readFile(new URL('../../Resources/Public/audio/app.js', import.meta.url), 'utf8');
const manifestSource = await readFile(new URL('../../Resources/Public/audio/decoder-manifest.generated.js', import.meta.url), 'utf8');
const playerView = await readFile(new URL('../../Resources/Views/player.leaf', import.meta.url), 'utf8');
const appStyles = await readFile(new URL('../../Resources/Public/audio/app.css', import.meta.url), 'utf8');

class FakeFormData {
    constructor() { this.fields = []; }
    append(name, value, filename) { this.fields.push({ name, value, filename }); }
}

function response(body, status = 200) {
    return { ok: status >= 200 && status < 300, status, json: async () => body };
}

const defaultFetch = async url => {
    if (url === '/api/songs/formats') return response({ total: 0, formats: [] });
    return response({ items: [], metadata: { page: 1, per: 50, total: 0 } });
};

// The app uploads via XMLHttpRequest (fetch has no upload progress events), so
// the test VM provides a fake XHR that records headers/body, can emit
// upload.onprogress, and completes asynchronously like the real browser.
const defaultXhr = async () => ({ status: 201, body: { status: 'created' } });

function createApp(fetchImpl = defaultFetch, xhrHandler = defaultXhr) {
    const session = new Map();
    const uploads = [];
    class FakeXMLHttpRequest {
        constructor() {
            this.headers = {};
            this.upload = { onprogress: null };
            this.responseText = '';
            this.status = 0;
            this.timeout = 0;
            this.onload = null;
            this.onerror = null;
        }
        open(method, url) { this.method = method; this.url = url; }
        setRequestHeader(name, value) { this.headers[name] = value; }
        send(body) {
            const fileField = body.fields?.find(field => field.name === 'file');
            const total = fileField?.value?.size ?? 0;
            const record = {
                headers: this.headers,
                body,
                onProgress: ratio => {
                    if (this.upload.onprogress && total > 0) {
                        this.upload.onprogress({ lengthComputable: true, loaded: Math.round(total * ratio), total });
                    }
                },
            };
            uploads.push(record);
            setTimeout(async () => {
                try {
                    const result = await xhrHandler(this.url, record, uploads);
                    const status = result?.status ?? 200;
                    const payload = result?.body ?? null;
                    const ratios = result?.progress ?? [1];
                    for (const ratio of ratios) record.onProgress(ratio);
                    this.status = status;
                    this.responseText = typeof payload === 'string' ? payload : JSON.stringify(payload ?? {});
                    this.onload?.();
                } catch {
                    this.onerror?.();
                }
            }, 0);
        }
    }
    const context = {
        console,
        fetch: fetchImpl,
        XMLHttpRequest: FakeXMLHttpRequest,
        FormData: FakeFormData,
        sessionStorage: { getItem: key => session.get(key) || null, setItem: (key, value) => session.set(key, value), removeItem: key => session.delete(key) },
        globalThis: { ORZ_DECODER_FORMATS: [] },
        module: { exports: {} },
        setTimeout: () => 0,
        URLSearchParams,
        document: { body: {}, activeElement: null, documentElement: { scrollHeight: 0 }, querySelector: () => null },
        window: { innerHeight: 800, addEventListener() {}, matchMedia: () => ({ matches: false }) },
    };
    context.globalThis.globalThis = context.globalThis;
    vm.runInNewContext(manifestSource, context);
    vm.runInNewContext(source, context);
    const app = context.globalThis.playerApp();
    app.notify = (message, type = 'success') => app.toasts.push({ message, type });
    return { app, session, uploads };
}

const file = (name, size = 128, relativePath = '') => ({ name, size, webkitRelativePath: relativePath });

test('directory import UI provides picker buttons that trigger hidden file inputs, never uses localStorage, and exposes the I shortcut', () => {
    assert.match(playerView, /webkitdirectory directory multiple/);
    assert.match(playerView, /app\.css\?v=20260927-touch-input-font-v1/);
    assert.match(playerView, /id="filePicker"[^>]*type="file" multiple/);
    assert.match(playerView, /id="directoryPicker"[^>]*webkitdirectory directory multiple/);
    assert.match(playerView, /class="visually-hidden"/);
    assert.match(playerView, /class="import-picker-button"[^>]*>选择目录<\/button>/);
    assert.match(playerView, /class="import-picker-button"[^>]*>选择文件<\/button>/);
    assert.match(playerView, /\$refs\.directoryPicker\.click\(\)/);
    assert.match(playerView, /\$refs\.filePicker\.click\(\)/);
    assert.doesNotMatch(playerView, /import-picker-field/);
    assert.doesNotMatch(playerView, /import-picker-native/);
    assert.doesNotMatch(playerView, /<span>选择目录<\/span>|<span>选择文件<\/span>/);
    assert.doesNotMatch(appStyles, /import-picker-native/);
    assert.doesNotMatch(appStyles, /\.import-picker-native[^}]*opacity:0/);
    assert.match(playerView, /:disabled="importRunning"/);
    assert.match(playerView, /管理员“?工具|管理员工具/);
    assert.match(playerView, /aria-keyshortcuts="I" title="导入本地目录 \(I\)"/);
    assert.match(playerView, /class="import-hint-key">I<\/kbd>/);
    assert.match(source, /key:'I',label:'导入本地目录'/);
    assert.match(source, /sessionStorage\.getItem\('orz-admin-api-token'\)/);
    assert.doesNotMatch(source, /localStorage/);
});

test('directory preflight accepts server-supported formats and rejects unsupported or oversized files', async () => {
    const { app } = createApp();

    assert.equal(app.isImportFileSupported(file('demo.mod')), true);
    assert.equal(app.isImportFileSupported(file('song.MP3')), true);
    assert.equal(app.isImportFileSupported(file('legacy.thx')), false);
    assert.equal(app.isImportFileSupported(file('notes.txt')), false);

    app.adminToken = 'token';
    await app.selectImportFiles([file('demo.mod'), file('notes.txt'), file('large.xm', 32 * 1024 * 1024 + 1)]);
    assert.equal(app.importItems.length, 3);
    assert.equal(app.importItems.filter(item => item.status === 'failed').length, 2);
    assert.equal(app.importItems[0].path, 'demo.mod');
});

test('selecting files without an admin token marks them retryable-failed instead of waiting forever; retry uploads after a token is set', async () => {
    const { app, uploads } = createApp();
    await app.selectImportFiles([file('a.mod'), file('b.xm')]);
    assert.equal(app.importItems.length, 2);
    assert.equal(app.importItems.every(item => item.status === 'failed'), true);
    assert.equal(app.importItems.every(item => item.retryable), true);
    assert.match(app.importItems[0].error, /管理令牌/);
    assert.equal(uploads.length, 0);

    app.adminToken = 'token';
    await app.retryImportFailures();
    assert.equal(app.importItems[0].status, 'created');
    assert.equal(app.importItems[1].status, 'created');
    assert.equal(uploads.length, 2);
});

test('server-side admin_api_disabled is surfaced as an actionable message', async () => {
    const { app } = createApp(
        defaultFetch,
        async () => ({ status: 503, body: { error: 'admin_api_disabled', reason: 'Administrative API is disabled', code: 503 } })
    );
    app.adminToken = 'token';
    app.importItems = [
        { file: file('a.mod'), path: 'a.mod', status: 'queued', loaded: 0 },
    ];
    await app.startImport();
    assert.equal(app.importItems[0].status, 'failed');
    assert.equal(app.importItems[0].retryable, true);
    assert.match(app.importItems[0].error, /ADMIN_API_TOKEN/);
});

test('invalid admin token surfaces a clear message', async () => {
    const { app } = createApp(
        defaultFetch,
        async () => ({ status: 401, body: { error: 'unauthorized', reason: 'Missing or invalid bearer token', code: 401 } })
    );
    app.adminToken = 'wrong-token';
    app.importItems = [
        { file: file('a.mod'), path: 'a.mod', status: 'queued', loaded: 0 },
    ];
    await app.startImport();
    assert.equal(app.importItems[0].status, 'failed');
    assert.match(app.importItems[0].error, /令牌不正确/);
});

test('directory upload has two workers, counts created and duplicates, and sends session token with relative path', async () => {
    let active = 0;
    let maxActive = 0;
    const { app, session, uploads } = createApp(
        defaultFetch,
        async (url, record, uploadsList) => {
            active += 1;
            maxActive = Math.max(maxActive, active);
            await new Promise(resolve => setTimeout(resolve, 5));
            active -= 1;
            const isDuplicate = uploadsList.length === 2;
            return { status: isDuplicate ? 200 : 201, body: { status: isDuplicate ? 'duplicate' : 'created' } };
        }
    );
    app.adminToken = 'session-only-token';
    app.saveAdminToken();
    assert.equal(session.get('orz-admin-api-token'), 'session-only-token');

    app.importItems = [
        { file: file('a.mod', 1, 'Folder/a.mod'), path: 'Folder/a.mod', status: 'queued' },
        { file: file('b.xm', 1, 'Folder/b.xm'), path: 'Folder/b.xm', status: 'queued' },
        { file: file('c.mp3', 1, 'Folder/c.mp3'), path: 'Folder/c.mp3', status: 'queued' },
    ];
    await app.startImport();

    assert.equal(maxActive, 2);
    assert.equal(app.importCreated, 2);
    assert.equal(app.importDuplicates, 1);
    assert.equal(app.importFailed, 0);
    assert.equal(app.importProgress, 100);
    assert.equal(uploads[0].headers.Authorization, 'Bearer session-only-token');
    assert.deepEqual(uploads[0].body.fields.find(field => field.name === 'relativePath'), { name: 'relativePath', value: 'Folder/a.mod', filename: undefined });
});

test('failed files do not stop the batch and retry only retryable failures', async () => {
    let failedOnce = true;
    const { app, uploads } = createApp(
        defaultFetch,
        async (url, record) => {
            const path = record.body.fields.find(field => field.name === 'relativePath').value;
            if (path === 'retry.mod' && failedOnce) { failedOnce = false; throw new Error('offline'); }
            return { status: 201, body: { status: 'created' } };
        }
    );
    const uploadedPaths = () => uploads.map(record => record.body.fields.find(field => field.name === 'relativePath').value);
    app.adminToken = 'token';
    app.importItems = [
        { file: file('retry.mod'), path: 'retry.mod', status: 'queued' },
        { file: file('continues.xm'), path: 'continues.xm', status: 'queued' },
        { file: file('not-retryable.txt'), path: 'not-retryable.txt', status: 'failed', error: '不支持的音频格式', retryable: false },
    ];

    await app.startImport();
    assert.equal(app.importCreated, 1);
    assert.equal(app.importFailed, 2);
    assert.equal(app.importItems[0].retryable, true);
    await app.retryImportFailures();

    assert.deepEqual(uploadedPaths(), ['retry.mod', 'continues.xm', 'retry.mod']);
    assert.equal(app.importCreated, 2);
    assert.equal(app.importFailed, 1);
    assert.equal(app.importItems[2].status, 'failed');
});

test('byte-weighted progress reflects uploaded bytes and excludes preflight-rejected files', async () => {
    const { app } = createApp();
    app.adminToken = 'token';
    app.importItems = [
        { file: file('small.mod', 1, 'small.mod'), path: 'small.mod', status: 'created', retryable: false, loaded: 1 },
        { file: file('big.xm', 100, 'big.xm'), path: 'big.xm', status: 'uploading', retryable: false, loaded: 50, uploadTotal: 100 },
        { file: file('rejected.txt', 50, 'rejected.txt'), path: 'rejected.txt', status: 'failed', error: '不支持的音频格式', retryable: false },
    ];
    assert.equal(app.importTotalBytes, 101);   // rejected.txt excluded
    assert.equal(app.importLoadedBytes, 51);
    assert.equal(app.importProgress, 50);      // 51/101 ≈ 50%, not 66% by count
    assert.equal(app.itemUploadPercent(app.importItems[1]), 50);
    assert.equal(app.currentUploadLabel(), 'big.xm · 50%');

    app.importItems[1].status = 'created';
    app.importItems[1].loaded = 100;
    assert.equal(app.importProgress, 100);
});

test('XHR upload progress drives the live byte-weighted bar', async () => {
    let releaseBig;
    const gate = new Promise(resolve => { releaseBig = resolve; });
    const { app } = createApp(
        defaultFetch,
        async (url, record) => {
            const path = record.body.fields.find(field => field.name === 'relativePath').value;
            if (path === 'big.xm') {
                record.onProgress(0.5);
                await gate;
            }
            return { status: 201, body: { status: 'created' } };
        }
    );
    app.adminToken = 'token';
    app.importItems = [
        { file: file('small.mod', 1, 'small.mod'), path: 'small.mod', status: 'queued', loaded: 0 },
        { file: file('big.xm', 100, 'big.xm'), path: 'big.xm', status: 'queued', loaded: 0 },
    ];
    const done = app.startImport();
    await new Promise(resolve => setTimeout(resolve, 30));
    assert.equal(app.importItems[1].status, 'uploading');
    assert.equal(app.importProgress, 50);      // small.mod 1/1 + big.xm 50/100
    assert.equal(app.itemUploadPercent(app.importItems[1]), 50);
    releaseBig();
    await done;
    assert.equal(app.importProgress, 100);
});
