import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

// iOS Safari 输入框聚焦自动放大回归测试 —— issue #12。
//
// iOS Safari 会对有效字号小于 16px 的文本输入框在聚焦时强制放大页面且不还原。
// 仓库的基础样式是 body{font-size:14px} + button,input{font:inherit}，会让每个输入框
// 都继承到 14px（管理令牌框更小，继承 .token-field 的 11px），因此必须为触屏设备
// 单独把可聚焦文本控件提到 16px。

const css = await readFile(new URL('../../Resources/Public/audio/app.css', import.meta.url), 'utf8');
const view = await readFile(new URL('../../Resources/Views/player.leaf', import.meta.url), 'utf8');

const TOUCH_QUERY = /@media\s*\(hover:none\)\s*and\s*\(pointer:coarse\)/;
const touchQueryAt = css.search(TOUCH_QUERY);

test('touch devices get 16px focusable text controls so iOS Safari does not zoom', () => {
    assert.ok(touchQueryAt >= 0, 'a (hover:none) and (pointer:coarse) media query must exist');
    const body = css.slice(touchQueryAt).match(/\{([^}]*)\}/);
    assert.ok(body, 'the touch media query must have a body');
    assert.match(body[1], /input[^{}]*\{[^}]*font-size:\s*16px/, 'inputs must be 16px on touch devices');
});

test('no 16px input override leaks into the desktop rules', () => {
    // 只关心命中 input 的 16px 规则；其他元素本来就有 16px（如 .brand-title strong）。
    const inputOverrides = [...css.matchAll(/[^{}]*\binput\b[^{}]*\{[^}]*font-size:\s*16px/g)]
        .map(match => match.index);
    assert.ok(inputOverrides.length >= 1, 'the touch override must exist');
    for (const at of inputOverrides) {
        assert.ok(
            at > touchQueryAt,
            'every 16px input override must live inside the touch media query',
        );
    }
});

test('the override is gated on pointer type, not viewport width', () => {
    // iPad 与横屏手机宽度会超过 720px，但仍然会触发缩放，因此不能用宽度断点做条件。
    assert.doesNotMatch(
        css,
        /@media\s*\(max-width:\s*720px\)\s*\{[^@]*?input[^{}]*\{[^}]*font-size/,
    );
});

test('the 16px rule wins the cascade against button,input{font:inherit}', () => {
    const inheritAt = css.indexOf('button,input{font:inherit}');
    assert.ok(inheritAt >= 0, 'the base font:inherit rule must still exist');
    assert.ok(
        touchQueryAt > inheritAt,
        'a same-specificity later rule is required; if it moves earlier iOS will zoom again',
    );
});

test('the viewport keeps pinch-zoom enabled', () => {
    // 锁 maximum-scale 会禁用双指缩放（WCAG 1.4.4），因此明确不采用该方案。
    assert.match(view, /<meta name="viewport" content="width=device-width, initial-scale=1">/);
    assert.doesNotMatch(view, /maximum-scale/);
    assert.doesNotMatch(view, /user-scalable\s*=\s*no/);
    assert.doesNotMatch(css, /maximum-scale/);
});

test('the desktop 14px base is left untouched', () => {
    assert.match(css, /body\{[^}]*font-size:14px\}/);
});
