/* =====================================================================
   多跳遍历面板
   从当前选中对象出发，按 1~3 跳的链接路径走一遍，展示每一跳的落点。
   ===================================================================== */

import { $, el, api, toast, state } from '../core.js';

const MAX_DEPTH = 3;

let selectObject = () => {};

const tstate = {
    startId: null,
    path: [],
    result: null,
    loading: false,
    error: null
};

export function initTraverse(opts = {}) {
    if (opts.selectObject) selectObject = opts.selectObject;
}

function resetIfStartChanged() {
    const id = state.selectedId;
    if (tstate.startId !== id) {
        tstate.startId = id;
        tstate.path = [];
        tstate.result = null;
        tstate.error = null;
    }
}

function linkOptions() {
    return state.linkTypes || [];
}

function dirOptions() {
    return [{ v: 'OUT', t: '出向 →' }, { v: 'IN', t: '← 入向' }];
}

async function run() {
    if (!tstate.startId) return;
    tstate.loading = true;
    tstate.error = null;
    try {
        tstate.result = await api(`/api/objects/${encodeURIComponent(tstate.startId)}/traverse`, {
            method: 'POST',
            body: { path: tstate.path, limit: 100 }
        });
    } catch (e) {
        tstate.error = e;
        tstate.result = null;
    } finally {
        tstate.loading = false;
        renderTraversePane($('#traverse-pane'));
    }
}

function stepRow(step, index) {
    const links = linkOptions();
    const linkSel = el('select', {
        onchange: e => { step.linkType = e.target.value; }
    }, links.map(l => el('option', {
        value: l.code,
        text: `${l.code} · ${l.fromTypeCode}→${l.toTypeCode}`,
        ...(l.code === step.linkType ? { selected: true } : {})
    })));

    const dirSel = el('select', {
        onchange: e => { step.direction = e.target.value; }
    }, dirOptions().map(d => el('option', {
        value: d.v, text: d.t, ...(d.v === step.direction ? { selected: true } : {})
    })));

    return el('div', { class: 'trav-step' },
        el('span', { class: 'trav-idx', text: String(index + 1) }),
        linkSel,
        dirSel,
        el('button', {
            class: 'trav-del', title: '删除这一跳',
            onclick: () => { tstate.path.splice(index, 1); renderTraversePane($('#traverse-pane')); }
        }, '×'));
}

function hopBlock(step, index) {
    const head = el('div', { class: 'trav-hop-head' },
        el('span', { class: 'trav-hop-no', text: `第 ${index + 1} 跳` }),
        el('span', { class: 'mono', text: `${step.linkType} ${step.direction === 'IN' ? '←' : '→'} ${step.targetType}` }),
        el('span', { class: 'trav-hop-count', text: `${step.count} 个` }),
        step.truncated ? el('span', { class: 'badge warn', text: '已截断' }) : null);

    const nodes = (step.items || []).map(n => el('button', {
        class: 'trav-node',
        title: n.objectId,
        onclick: () => selectObject(n.objectId)
    },
        el('span', { class: 'trav-node-title', text: n.title || n.businessKey }),
        el('span', { class: 'trav-node-type', text: n.typeCode })));

    return el('div', { class: 'trav-hop' }, head,
        nodes.length ? el('div', { class: 'trav-nodes' }, nodes)
            : el('div', { class: 'hint', text: '这一跳没有落点' }));
}

export function renderTraversePane(pane) {
    if (!pane) return;
    resetIfStartChanged();
    pane.innerHTML = '';

    if (!tstate.startId) {
        pane.append(el('div', { class: 'empty' },
            el('strong', { text: '先选一个对象' }),
            el('div', { text: '在左侧结果里点一行，然后从这里按链接路径走' })));
        return;
    }

    pane.append(el('div', { class: 'trav-start' },
        el('span', { class: 'muted', text: '起点' }),
        el('code', { text: tstate.startId })));

    if (!linkOptions().length) {
        pane.append(el('div', { class: 'hint', text: '还没有定义链接类型' }));
        return;
    }

    const steps = el('div', { class: 'trav-steps' });
    if (!tstate.path.length) {
        steps.append(el('div', { class: 'hint', text: '还没有加跳：点下面「添加一跳」，选链接类型和方向' }));
    }
    tstate.path.forEach((s, i) => steps.append(stepRow(s, i)));
    pane.append(steps);

    pane.append(el('div', { class: 'trav-actions' },
        el('button', {
            class: 'btn ghost sm',
            disabled: tstate.path.length >= MAX_DEPTH,
            onclick: () => {
                const first = linkOptions()[0];
                tstate.path.push({ linkType: first.code, direction: 'OUT' });
                renderTraversePane(pane);
            }
        }, tstate.path.length >= MAX_DEPTH ? `最多 ${MAX_DEPTH} 跳` : '＋ 添加一跳'),
        el('button', {
            class: 'btn sm',
            disabled: !tstate.path.length || tstate.loading,
            onclick: run
        }, tstate.loading ? '走…' : '走一遍')));

    if (tstate.error) {
        pane.append(el('div', { class: 'trav-error' },
            el('strong', { text: tstate.error.code || 'ERROR' }),
            ' ' + tstate.error.message));
    }

    const r = tstate.result;
    if (r) {
        const summary = el('div', { class: 'trav-summary' },
            el('span', { text: `走完 ${r.steps.length} 跳，终点 ${r.finalNodes.length} 个` }),
            r.truncated ? el('span', { class: 'badge warn', text: '有跳被截断' }) : null);
        pane.append(summary);
        r.steps.forEach((s, i) => pane.append(hopBlock(s, i)));
    }
}
