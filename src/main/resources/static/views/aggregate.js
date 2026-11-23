/* =====================================================================
   聚合面板：按一个属性分组，统计 count / sum / avg / min / max
   ===================================================================== */

import { $, el, api, toast, state } from '../core.js';

let onClose = () => {};

const astate = {
    groupBy: '',
    fn: 'count',
    prop: '',
    result: null,
    loading: false,
    error: null
};

export function initAggregate(opts = {}) {
    if (opts.onClose) onClose = opts.onClose;
}

function groupableProps() {
    return (state.type && state.type.properties ? state.type.properties : [])
        .filter(p => !p.isArray);
}

function numericProps() {
    return groupableProps().filter(p => ['integer', 'long', 'decimal', 'boolean'].includes(p.type));
}

function ensureDefaults() {
    const groups = groupableProps();
    if (!astate.groupBy || !groups.find(p => p.key === astate.groupBy)) {
        astate.groupBy = groups.length ? groups[0].key : '';
    }
    const nums = numericProps();
    if (!astate.prop || !nums.find(p => p.key === astate.prop)) {
        astate.prop = nums.length ? nums[0].key : '';
    }
}

function fmtMetric(v) {
    if (v === null || v === undefined) return '—';
    if (typeof v === 'number') {
        return Number.isInteger(v) ? String(v) : v.toFixed(2);
    }
    return String(v);
}

async function run() {
    if (!state.type) return;
    if (!astate.groupBy) {
        toast('没有可分组的属性', 'err', 'NO_GROUP');
        return;
    }
    astate.loading = true;
    astate.error = null;
    try {
        const metrics = astate.fn === 'count'
            ? [{ fn: 'count' }]
            : [{ fn: 'count' }, { fn: astate.fn, prop: astate.prop }];
        astate.result = await api('/api/objects/aggregate', {
            method: 'POST',
            body: {
                typeCode: state.type.code,
                filters: state.filters.filter(f => f.prop && f.op),
                groupBy: [astate.groupBy],
                metrics
            }
        });
    } catch (e) {
        astate.error = e;
        astate.result = null;
    } finally {
        astate.loading = false;
        render();
    }
}

function selectors() {
    const groups = groupableProps();
    const nums = numericProps();
    const fns = [
        { v: 'count', t: '数量 count' },
        { v: 'avg', t: '平均 avg' },
        { v: 'sum', t: '求和 sum' },
        { v: 'max', t: '最大 max' },
        { v: 'min', t: '最小 min' }
    ];

    const groupSel = el('select', {
        onchange: e => { astate.groupBy = e.target.value; }
    }, groups.map(p => el('option', {
        value: p.key, text: `${p.label || p.key} · ${p.key}`,
        ...(p.key === astate.groupBy ? { selected: true } : {})
    })));

    const fnSel = el('select', {
        onchange: e => {
            astate.fn = e.target.value;
            render();
        }
    }, fns.map(f => el('option', {
        value: f.v, text: f.t, ...(f.v === astate.fn ? { selected: true } : {})
    })));

    const propSel = el('select', {
        disabled: astate.fn === 'count' || !nums.length,
        onchange: e => { astate.prop = e.target.value; }
    }, nums.map(p => el('option', {
        value: p.key, text: `${p.label || p.key} · ${p.key}`,
        ...(p.key === astate.prop ? { selected: true } : {})
    })));

    return el('div', { class: 'agg-bar' },
        el('span', { class: 'agg-label', text: '分组' }), groupSel,
        el('span', { class: 'agg-label', text: '指标' }), fnSel, propSel,
        el('button', {
            class: 'btn sm', disabled: astate.loading,
            onclick: run
        }, astate.loading ? '统计…' : '执行'),
        el('button', { class: 'btn ghost sm', onclick: () => { closeAggregate(); onClose(); } }, '关闭'));
}

function table() {
    const r = astate.result;
    if (!r) return el('div', { class: 'hint', text: '选好分组和指标，点「执行」' });

    const metricLabel = astate.fn === 'count' ? 'count' : `${astate.fn}_${astate.prop}`;
    const maxCount = Math.max(1, ...r.groups.map(g => g.count));

    const head = el('tr', {},
        el('th', { text: r.groupBy }),
        el('th', { class: 'num', text: '数量' }),
        astate.fn === 'count' ? null : el('th', { class: 'num', text: metricLabel }));

    const rows = r.groups.map(g => el('tr', {},
        el('td', {}, el('code', { text: g.key === null || g.key === undefined ? '(未填)' : String(g.key) })),
        el('td', { class: 'num' },
            el('div', { class: 'agg-cell' },
                el('div', { class: 'agg-fill', style: `width:${(g.count / maxCount) * 100}%` }),
                el('span', { class: 'agg-val', text: String(g.count) }))),
        astate.fn === 'count' ? null
            : el('td', { class: 'num', text: fmtMetric(g.metrics ? g.metrics[metricLabel] : null) })));

    return el('div', {},
        el('div', { class: 'agg-summary' },
            el('span', { text: `${r.groupCount} 组 · 按 ${r.groupBy} 分组` }),
            r.truncated ? el('span', { class: 'badge warn', text: '结果被截断' }) : null),
        el('table', { class: 'table' },
            el('thead', {}, head),
            el('tbody', {}, rows)));
}

export function render() {
    const card = $('#agg-card');
    if (!card) return;
    card.innerHTML = '';
    if (!state.type) {
        card.append(el('div', { class: 'hint', text: '先选一个对象类型' }));
        return;
    }
    ensureDefaults();
    card.append(selectors());
    if (astate.error) {
        card.append(el('div', { class: 'trav-error' },
            el('strong', { text: astate.error.code || 'ERROR' }), ' ' + astate.error.message));
    }
    card.append(table());
}

export function openAggregate() {
    const card = $('#agg-card');
    if (!card) return;
    astate.result = null;
    astate.error = null;
    card.classList.remove('hidden');
    render();
}

export function closeAggregate() {
    const card = $('#agg-card');
    if (card) card.classList.add('hidden');
}
