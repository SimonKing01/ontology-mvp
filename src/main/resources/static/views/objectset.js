/* =====================================================================
   对象集：把当前查询条件存下来，下次一键复用
   ===================================================================== */

import { $, el, api, toast, state } from '../core.js';

let applyFilters = () => {};
let rerun = () => {};

const ostate = { sets: [], loading: false };

export function initObjectSet(opts = {}) {
    if (opts.onApply) applyFilters = opts.onApply;
    if (opts.onRerun) rerun = opts.onRerun;
}

export async function loadSets() {
    if (ostate.loading) return;
    ostate.loading = true;
    try {
        ostate.sets = await api('/api/object-sets');
    } catch (e) {
        ostate.sets = [];
    } finally {
        ostate.loading = false;
        renderSets();
    }
}

function currentFilters() {
    return (state.filters || []).filter(f => f.prop && f.op);
}

export async function saveCurrentQuery() {
    if (!state.type) {
        toast('先选一个对象类型', 'err', 'NO_TYPE');
        return;
    }
    const filters = currentFilters();
    if (!filters.length) {
        toast('当前没有过滤条件', 'warn', 'NO_FILTER', '先加一两个条件再保存，否则集合等于"全部对象"');
        return;
    }
    const name = window.prompt('给这个对象集起个名字', `${state.type.code} · 条件 ${filters.length} 个`);
    if (!name) return;

    try {
        await api('/api/object-sets', {
            method: 'POST',
            body: { name, typeCode: state.type.code, filters, shared: false }
        });
        toast('已保存', 'ok', 'object-set', name);
        await loadSets();
    } catch (e) {
        toast(e.message, 'err', e.code, e.details ? JSON.stringify(e.details) : null);
    }
}

function applySet(def) {
    applyFilters(def.filters || [], def.sort || []);
    rerun();
    toast('已应用对象集', 'ok', 'object-set', `${def.name} · ${(def.filters || []).length} 个条件`);
}

export function renderSets() {
    const bar = $('#set-bar');
    const chips = $('#set-chips');
    if (!bar || !chips) return;
    chips.innerHTML = '';

    const visible = (ostate.sets || []).filter(s => !state.type || s.typeCode === state.type.code);
    if (!visible.length) {
        bar.classList.add('hidden');
        return;
    }
    bar.classList.remove('hidden');

    for (const s of visible) {
        chips.append(el('button', {
            class: 'set-chip',
            title: `${s.typeCode} · ${s.owner}${s.shared ? ' · 已共享' : ''} · ${(s.filters || []).length} 个条件`,
            onclick: () => applySet(s)
        },
            el('span', { text: s.name }),
            el('span', { class: 'set-chip-count', text: String((s.filters || []).length) })));
    }
}
