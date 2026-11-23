/* =====================================================================
   控制台共享层：DOM 工具、会话、API 封装、全局状态
   被 app.js（浏览/详情/Action）与 meta.js（元模型配置）共用
   ===================================================================== */

export const $ = (sel, root = document) => root.querySelector(sel);
export const TZ = 'Asia/Shanghai';

export function el(tag, attrs = {}, ...children) {
    const node = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
        if (v === null || v === undefined || v === false) continue;
        if (k === 'class') node.className = v;
        else if (k === 'text') node.textContent = v;
        else if (k.startsWith('on') && typeof v === 'function') {
            node.addEventListener(k.slice(2).toLowerCase(), v);
        } else if (v === true) node.setAttribute(k, '');
        else node.setAttribute(k, String(v));
    }
    for (const child of children.flat(3)) {
        if (child === null || child === undefined || child === false) continue;
        node.append(child.nodeType ? child : document.createTextNode(String(child)));
    }
    return node;
}

export function fmtValue(v) {
    if (v === null || v === undefined || v === '') return '—';
    if (Array.isArray(v)) return v.length ? v.join('、') : '—';
    if (typeof v === 'boolean') return v ? 'true' : 'false';
    if (typeof v === 'object') return JSON.stringify(v);
    return String(v);
}

export function fmtTime(iso) {
    if (!iso) return '';
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return iso;
    return d.toLocaleString('zh-CN', {
        timeZone: TZ, hour12: false,
        year: 'numeric', month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit', second: '2-digit'
    });
}

/* ---------------------------------------------------------------- 会话 */
export const session = { user: null, pass: null, role: null };
export const SESSION_KEY = 'ontology-console-session';

let busyCount = 0;
export function pushBusy() {
    busyCount += 1;
    document.body.classList.add('busy');
}
export function popBusy() {
    busyCount = Math.max(0, busyCount - 1);
    if (busyCount === 0) document.body.classList.remove('busy');
}

/** 401 时的处理由 app.js 注入，避免 core 反向依赖业务逻辑 */
let unauthorizedHandler = null;
export function setUnauthorizedHandler(fn) {
    unauthorizedHandler = fn;
}

export async function api(path, options = {}) {
    const { method = 'GET', body } = options;
    pushBusy();
    try {
        const res = await fetch(path, {
            method,
            headers: {
                'Authorization': 'Basic ' + btoa(`${session.user}:${session.pass}`),
                ...(body !== undefined ? { 'Content-Type': 'application/json' } : {})
            },
            body: body !== undefined ? JSON.stringify(body) : undefined
        });
        const text = await res.text();
        let data = null;
        if (text) {
            try { data = JSON.parse(text); } catch { data = text; }
        }
        if (!res.ok) {
            const err = new Error(data && data.message ? data.message : res.statusText);
            err.status = res.status;
            err.code = data && data.code ? data.code : 'HTTP_' + res.status;
            err.details = data && data.details ? data.details : null;
            if (res.status === 401 && $('#app') && !$('#app').classList.contains('hidden')) {
                toast('登录已失效，请重新登录', 'err', 'UNAUTHORIZED');
                if (unauthorizedHandler) unauthorizedHandler();
            }
            throw err;
        }
        return data;
    } finally {
        popBusy();
    }
}

export function toast(message, kind = 'info', code, detail) {
    const node = el('div', { class: 'toast ' + (kind === 'err' ? 'err' : kind === 'ok' ? 'ok' : '') },
        el('div', {}, message, code ? el('span', { class: 't-code', text: code }) : null),
        detail ? el('div', { class: 't-detail', text: detail }) : null);
    $('#toasts').append(node);
    setTimeout(() => node.remove(), kind === 'err' ? 7000 : 4200);
}

/* ---------------------------------------------------------------- 状态 */
export const state = {
    types: [],
    linkTypes: [],
    actions: [],
    stats: null,
    type: null,
    filters: [],
    sort: { prop: '', dir: 'asc' },
    fields: [],
    pageSize: 20,
    cursor: null,
    cursorStack: [],
    page: 0,
    items: [],
    hasMore: false,
    nextCursor: null,
    total: null,
    selectedId: null,
    detail: null,
    edits: [],
    linkType: '',
    linkDir: 'OUT',
    objectLinks: [],
    detailTab: 'detail',
    refOptions: {},
    refLoading: {},
    action: {
        code: null,
        values: {},
        useStale: false,
        replay: false,
        key: '',
        lastKey: {},
        result: null
    }
};

export const typeOf = code => state.types.find(t => t.code === code) || null;

export const propDef = (key, typeCode) => {
    const t = typeOf(typeCode || (state.type && state.type.code));
    return t && t.properties ? t.properties.find(p => p.key === key) : null;
};

export const currentAction = () => state.actions.find(a => a.code === state.action.code) || null;
