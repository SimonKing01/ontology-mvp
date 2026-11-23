/* =====================================================================
   本体控制台 · 前端逻辑（原生 ES module，无构建链）
   只调用既有 API：查询 / 详情 / 链接 / 审计 / Action
   ===================================================================== */
import {
    $, el, fmtValue, fmtTime, session, SESSION_KEY, api, toast,
    state, typeOf, propDef, currentAction, setUnauthorizedHandler
} from './core.js';
import { initTraverse, renderTraversePane } from './views/traverse.js';
import { initAggregate, openAggregate } from './views/aggregate.js';
import { initObjectSet, loadSets, saveCurrentQuery, renderSets } from './views/objectset.js';

(() => {
    'use strict';


    let ingestTimer = null;

    function fmtInt(n) {
        return Number(n || 0).toLocaleString('zh-CN');
    }

    function fmtElapsed(ms) {
        const total = Math.floor((ms || 0) / 1000);
        if (total < 60) return total + ' 秒';
        const m = Math.floor(total / 60);
        return m + ' 分 ' + (total % 60) + ' 秒';
    }

    // ---------------------------------------------------------------- 采集进度
    let ingestWasBusy = false;
    let ingestIdleTicks = 0;

    function startIngestProgress() {
        const box = $('#ingest-progress');
        if (!box) return;
        box.classList.remove('hidden');
        $('#ip-fill').style.width = '0%';
        $('#ip-pct').textContent = '0%';
        $('#ip-count').textContent = '— / —';
        $('#ip-rate').textContent = '';
        $('#ip-elapsed').textContent = '';
        $('#ip-type').textContent = '';
        ingestWasBusy = true;
        ingestIdleTicks = 0;
        pollIngestProgress();
        if (ingestTimer) clearInterval(ingestTimer);
        ingestTimer = setInterval(pollIngestProgress, 800);
    }

    function stopIngestProgress() {
        if (ingestTimer) {
            clearInterval(ingestTimer);
            ingestTimer = null;
        }
        ingestWasBusy = false;
        ingestIdleTicks = 0;
        const box = $('#ingest-progress');
        if (box) box.classList.add('hidden');
    }

    function renderIngestProgress(p) {
        const pct = Math.max(0, Math.min(100, p.percent || 0));
        $('#ip-fill').style.width = pct + '%';
        $('#ip-pct').textContent = pct.toFixed(1) + '%';
        $('#ip-count').textContent = fmtInt(p.processed) + ' / ' + fmtInt(p.total) + ' 行';
        $('#ip-type').textContent = p.action || p.currentType || '';
        $('#ip-rate').textContent = p.rowsPerSecond ? fmtInt(p.rowsPerSecond) + ' 行/秒' : '';
        $('#ip-elapsed').textContent = p.elapsedMs ? '已用 ' + fmtElapsed(p.elapsedMs) : '';
    }

    async function fetchProgress() {
        const res = await fetch('/api/ingest/progress', {
            headers: { 'Authorization': 'Basic ' + btoa(`${session.user}:${session.pass}`) }
        });
        if (!res.ok) return null;
        return res.json();
    }

    async function pollIngestProgress() {
        try {
            const p = await fetchProgress();
            if (!p) return;
            if (p.busy) {
                ingestWasBusy = true;
                ingestIdleTicks = 0;
                renderIngestProgress(p);
                return;
            }
            // 请求刚发出时后端可能还没拿到锁，连续几次都空闲才认定采集结束
            ingestIdleTicks++;
            if (ingestIdleTicks >= 3 && ingestWasBusy) {
                stopIngestProgress();
                await loadCounts();
                await refreshStats();
                if (state.type) await runQuery(true);
            }
        } catch (e) {
            // 轮询失败忽略，下一轮再试
        }
    }

    // 页面加载时若后端正在采集，直接接上进度条（刷新页面也不丢）
    async function resumeIngestProgress() {
        try {
            const p = await fetchProgress();
            if (!p || !p.busy) return;
            $('#ingest-progress').classList.remove('hidden');
            ingestWasBusy = true;
            renderIngestProgress(p);
            if (ingestTimer) clearInterval(ingestTimer);
            ingestTimer = setInterval(pollIngestProgress, 800);
        } catch (e) {
            // 忽略
        }
    }

    function shortId(id, keep = 34) {
        if (!id) return '';
        return id.length > keep ? id.slice(0, keep - 1) + '…' : id;
    }


    /* ---------------------------------------------------------------- 登录 */
    function showLogin() {
        $('#login').classList.remove('hidden');
        $('#app').classList.add('hidden');
        session.user = null;
        session.pass = null;
        sessionStorage.removeItem(SESSION_KEY);
    }

    async function doLogin(user, pass) {
        session.user = user;
        session.pass = pass;
        try {
            const me = await api('/api/session');
            session.role = me.role;
            sessionStorage.setItem(SESSION_KEY, JSON.stringify({ user, pass }));
            $('#login').classList.add('hidden');
            $('#app').classList.remove('hidden');
            $('#user-name').textContent = me.username;
            $('#user-avatar').textContent = (me.username || '?').slice(0, 1).toUpperCase();
            const roleEl = $('#user-role');
            roleEl.textContent = me.role;
            roleEl.className = 'badge role' + (me.role === 'VIEWER' ? ' warn' : '');
            // 维护类操作按钮只对可写角色展示（服务端仍会再次校验）
            const canWrite = me.role === 'ADMIN' || me.role === 'EDITOR';
            $('#btn-ingest').classList.toggle('hidden', !canWrite);
            $('#btn-validate').classList.toggle('hidden', !canWrite);
            $('#login-err').textContent = '';
            await bootstrap();
        } catch (e) {
            session.user = null;
            session.pass = null;
            sessionStorage.removeItem(SESSION_KEY);
            $('#login-err').textContent = e.status === 401 ? '账号或密码不正确' : (e.message || '登录失败');
            throw e;
        }
    }

    function bindLogin() {
        $('#login-form').addEventListener('submit', async ev => {
            ev.preventDefault();
            const user = $('#login-user').value.trim();
            const pass = $('#login-pass').value;
            if (!user) return;
            try { await doLogin(user, pass); } catch { /* 已提示 */ }
        });
        document.querySelectorAll('.login-accounts [data-user]').forEach(btn => {
            btn.addEventListener('click', () => {
                const u = btn.dataset.user;
                $('#login-user').value = u;
                $('#login-pass').value = u;
                $('#login-form').requestSubmit();
            });
        });
    }

    /* ---------------------------------------------------------------- 启动 */
    async function bootstrap() {
        try {
            const [types, links, actions, stats] = await Promise.all([
                api('/api/meta/object-types'),
                api('/api/meta/link-types'),
                api('/api/meta/action-types'),
                api('/api/ingest/stats').catch(() => null)
            ]);
            state.types = types.map(v => ({ ...v.definition, revision: v.revision }));
            state.linkTypes = links.map(v => ({ ...v.definition, revision: v.revision }));
            state.actions = actions.map(v => ({ ...v.definition, revision: v.revision }));
            state.stats = stats;
            renderStats();
            renderLinks();
            await loadCounts();
            await loadSets();

            if (!state.types.length) {
                renderNoModel();
                return;
            }
            const preferred = state.types.find(t => t.code === 'Flight' && t.readable)
                || state.types.find(t => t.readable)
                || state.types[0];
            if (preferred) await selectType(preferred.code);
            await resumeIngestProgress();
        } catch (e) {
            toast('初始化失败：' + e.message, 'err', e.code, e.details ? JSON.stringify(e.details) : null);
        }
    }

    async function loadCounts() {
        await Promise.all(state.types.map(async t => {
            try {
                const r = await api('/api/objects/count', { method: 'POST', body: { typeCode: t.code } });
                t.count = r.count;
                t.readable = true;
            } catch (e) {
                t.readable = false;
                t.count = null;
            }
        }));
        renderSidebar();
    }

    function renderSidebar() {
        const list = $('#type-list');
        list.innerHTML = '';
        const readable = state.types.filter(t => t.readable !== false);
        $('#type-count').textContent = String(readable.length);

        if (!readable.length) {
            list.append(el('div', { class: 'hint', text: '当前角色没有可读的对象类型' }));
            return;
        }
        for (const t of readable) {
            const active = state.type && state.type.code === t.code;
            list.append(el('button', {
                class: 'type-item' + (active ? ' active' : ''),
                onclick: () => selectType(t.code)
            },
                el('span', { class: 'dot' }),
                el('span', { text: t.name || t.code }),
                el('span', { class: 'type-code', text: t.code }),
                el('span', { class: 'count', text: t.count === null || t.count === undefined ? '–' : String(t.count) })
            ));
        }
    }

    function renderStats() {
        const s = state.stats || {};
        $('#stat-objects').textContent = s.objects ?? '–';
        $('#stat-props').textContent = s.properties ?? '–';
        $('#stat-links').textContent = s.links ?? '–';
    }

    function renderLinks() {
        const list = $('#link-list');
        list.innerHTML = '';
        if (!state.linkTypes.length) {
            list.append(el('li', { class: 'muted', text: '暂无链接类型' }));
            return;
        }
        for (const l of state.linkTypes) {
            list.append(el('li', {},
                el('span', { class: 'mono', text: l.code }),
                el('span', { class: 'muted', text: '·' }),
                el('span', { text: `${l.fromTypeCode} → ${l.toTypeCode}` }),
                el('span', { class: 'badge link', text: l.cardinality })
            ));
        }
    }

    function renderNoModel() {
        $('#type-title').textContent = '还没有对象类型';
        $('#type-sub').textContent = '先运行 ./demo.sh，或调用元模型接口创建对象类型';
        $('#type-meta').innerHTML = '';
        $('#results-body').innerHTML = '';
        $('#detail-pane').innerHTML = '';
        $('#detail-pane').append(el('div', { class: 'empty' },
            el('strong', { text: '元模型为空' }),
            el('div', { text: '对象类型是数据源映射：datasetId + idField + properties[sourcePath]' })));
    }

    /* ---------------------------------------------------------------- 类型 */
    async function selectType(code) {
        const type = typeOf(code);
        if (!type) return;
        state.type = type;
        state.filters = [];
        state.sort = { prop: '', dir: 'asc' };
        state.fields = [];
        state.pageSize = Number($('#page-size').value) || 20;
        state.cursor = null;
        state.cursorStack = [];
        state.page = 0;
        state.items = [];
        state.total = null;
        state.selectedId = null;
        state.detail = null;
        state.edits = [];
        state.objectLinks = [];
        state.action.code = null;
        state.action.values = {};
        state.action.result = null;

        renderSidebar();
        renderTypeHeader();
        renderFilterRows();
        renderSortOptions();
        renderFields();
        setTab('detail');
        renderDetail();
        renderActionPanel();
        await runQuery(true);
    }

    function renderTypeHeader() {
        const t = state.type;
        if (!t) return;
        $('#type-title').textContent = t.name || t.code;
        $('#type-sub').textContent = t.code + ' · dataset ' + (t.datasetId || '—')
            + ' · idField ' + (t.idField || '—');
        const meta = $('#type-meta');
        meta.innerHTML = '';
        meta.append(el('span', { class: 'badge', text: 'rev ' + (t.revision ?? 1) }));
        const props = t.properties || [];
        const owners = { source: 0, ontology: 0, derived: 0 };
        props.forEach(p => { owners[p.owner] = (owners[p.owner] || 0) + 1; });
        meta.append(el('span', { class: 'badge owner-source', text: `source ${owners.source}` }));
        meta.append(el('span', { class: 'badge owner-ontology', text: `ontology ${owners.ontology}` }));
        if (owners.derived) meta.append(el('span', { class: 'badge owner-derived', text: `derived ${owners.derived}` }));
        const actions = state.actions.filter(a => a.targetTypeCode === t.code);
        meta.append(el('span', { class: 'badge', text: `Action ${actions.length}` }));
    }

    /* ---------------------------------------------------------------- 查询条件 */
    function renderFilterRows() {
        const box = $('#filters');
        box.innerHTML = '';
        if (!state.filters.length) {
            box.append(el('div', { class: 'hint', text: '没有过滤条件，将查询该类型的全部对象（keyset 分页）' }));
            return;
        }
        state.filters.forEach((f, idx) => {
            const def = propDef(f.prop);
            const numeric = def && ['integer', 'long', 'decimal'].includes(def.type);
            const isNull = f.op === 'isNull';

            const propSelect = el('select', {
                onchange: e => { f.prop = e.target.value; renderFilterRows(); }
            }, (state.type.properties || []).map(p =>
                el('option', { value: p.key, selected: p.key === f.prop },
                    `${p.label || p.key} · ${p.key}`)));

            const opSelect = el('select', {
                onchange: e => { f.op = e.target.value; renderFilterRows(); }
            }, [
                ['eq', '等于 eq'], ['in', '属于 in'], ['gt', '大于 gt'], ['gte', '大于等于 gte'],
                ['lt', '小于 lt'], ['lte', '小于等于 lte'], ['contains', '包含 contains'], ['isNull', '为空 isNull']
            ].map(([v, label]) => el('option', { value: v, selected: v === f.op }, label)));

            let valueInput;
            if (isNull) {
                valueInput = el('div', { class: 'value-hint', text: '不需要值（属性缺失即视为空）' });
            } else {
                valueInput = el('input', {
                    class: 'value',
                    type: numeric && f.op !== 'in' ? 'number' : 'text',
                    placeholder: f.op === 'in' ? '多个值用英文逗号分隔' : (numeric ? '数值' : '值'),
                    value: f.value ?? '',
                    oninput: e => { f.value = e.target.value; }
                });
            }

            box.append(el('div', { class: 'filter-row' },
                propSelect, opSelect, valueInput,
                el('button', {
                    class: 'remove', title: '删除条件',
                    onclick: () => { state.filters.splice(idx, 1); renderFilterRows(); }
                }, '×')
            ));
        });
    }

    function renderSortOptions() {
        const sel = $('#sort-prop');
        sel.innerHTML = '';
        const options = [
            ['', '默认（object_id）'],
            ['objectId', 'objectId'],
            ['businessKey', 'businessKey'],
            ['createdAt', 'createdAt'],
            ['updatedAt', 'updatedAt']
        ];
        (state.type.properties || []).filter(p => !p.isArray).forEach(p => {
            options.push([p.key, `${p.label || p.key} · ${p.key}`]);
        });
        options.forEach(([v, label]) => sel.append(el('option', { value: v, selected: v === state.sort.prop }, label)));
        sel.onchange = e => { state.sort.prop = e.target.value; };
        $('#sort-dir').value = state.sort.dir;
        $('#sort-dir').onchange = e => { state.sort.dir = e.target.value; };
        $('#page-size').onchange = e => { state.pageSize = Number(e.target.value); };
    }

    function renderFields() {
        const box = $('#fields');
        box.innerHTML = '';
        (state.type.properties || []).forEach(p => {
            const on = state.fields.includes(p.key);
            const chip = el('label', { class: 'chip' + (on ? ' on' : '') },
                el('input', { type: 'checkbox', checked: on }),
                `${p.label || p.key}`,
                el('span', { class: 'muted', text: p.key })
            );
            chip.addEventListener('click', ev => {
                ev.preventDefault();
                const i = state.fields.indexOf(p.key);
                if (i >= 0) state.fields.splice(i, 1); else state.fields.push(p.key);
                renderFields();
            });
            box.append(chip);
        });
        if (!(state.type.properties || []).length) {
            box.append(el('span', { class: 'muted', text: '该类型没有属性' }));
        }
    }

    function coerce(prop, raw) {
        if (raw === '' || raw === null || raw === undefined) return raw;
        if (!prop) return raw;
        if (['integer', 'long', 'decimal'].includes(prop.type)) {
            const n = Number(raw);
            return Number.isNaN(n) ? raw : n;
        }
        if (prop.type === 'boolean') return raw === true || raw === 'true';
        return raw;
    }

    function buildFilters() {
        const out = [];
        for (const f of state.filters) {
            if (!f.prop || !f.op) continue;
            const def = propDef(f.prop);
            if (f.op === 'isNull') {
                out.push({ prop: f.prop, op: 'isNull' });
                continue;
            }
            if (f.value === '' || f.value === null || f.value === undefined) continue;
            if (f.op === 'in') {
                const list = String(f.value).split(',').map(s => s.trim()).filter(Boolean).map(s => coerce(def, s));
                if (!list.length) continue;
                out.push({ prop: f.prop, op: 'in', value: list });
            } else {
                out.push({ prop: f.prop, op: f.op, value: coerce(def, f.value) });
            }
        }
        return out;
    }

    /* ---------------------------------------------------------------- 查询 */
    async function runQuery(reset = true) {
        if (!state.type) return;
        if (reset) {
            state.cursor = null;
            state.cursorStack = [];
            state.page = 0;
        }
        const filters = buildFilters();
        const body = { typeCode: state.type.code, pageSize: state.pageSize };
        if (filters.length) body.filters = filters;
        if (state.sort.prop) body.sort = [{ prop: state.sort.prop, op: state.sort.dir }];
        if (state.fields.length) body.fields = state.fields;
        if (state.cursor) body.cursor = state.cursor;

        try {
            const page = await api('/api/objects/query', { method: 'POST', body });
            state.items = page.items || [];
            state.hasMore = !!page.hasMore;
            state.nextCursor = page.nextCursor || null;
            renderResults();

            const cnt = await api('/api/objects/count', {
                method: 'POST', body: { typeCode: state.type.code, filters }
            });
            state.total = cnt.count;
        } catch (e) {
            state.items = [];
            state.hasMore = false;
            state.total = null;
            toast(e.message, 'err', e.code, e.details ? JSON.stringify(e.details) : null);
        } finally {
            renderResults();
        }
    }

    function renderResults() {
        const tbody = $('#results-body');
        tbody.innerHTML = '';
        const meta = $('#results-meta');
        const badge = $('#count-badge');

        if (!state.items.length) {
            tbody.append(el('tr', {},
                el('td', { colspan: 4 },
                    el('div', { class: 'empty' },
                        el('strong', { text: '没有查到对象' }),
                        el('div', { text: '换个过滤条件，或点击左侧「重新采集」把上游数据拉进来' })))));
            meta.textContent = '0 条';
            badge.textContent = '—';
            badge.className = 'badge';
            $('#next-page').disabled = true;
            $('#prev-page').disabled = state.page === 0;
            $('#page-label').textContent = `第 ${state.page + 1} 页`;
            return;
        }

        for (const item of state.items) {
            const selected = state.selectedId === item.objectId;
            const chips = el('div', { class: 'prop-chips' },
                Object.entries(item.properties || {}).slice(0, 3).map(([k, v]) =>
                    el('span', { class: 'prop-chip' }, el('b', {}, k), ' ', fmtValue(v))));

            tbody.append(el('tr', {
                class: 'row' + (selected ? ' sel' : '') + (item.stale ? ' stale' : ''),
                onclick: () => selectObject(item.objectId)
            },
                el('td', {}, el('div', { class: 'obj-id', text: shortId(item.objectId) })),
                el('td', {}, el('span', { text: item.title || item.businessKey }),
                    item.stale ? el('span', { class: 'badge warn', style: 'margin-left:6px', text: '上游已删除' }) : null,
                    item.stateOwner === 'ontology' ? el('span', { class: 'badge owner-ontology', style: 'margin-left:6px', text: '本体创建' }) : null),
                el('td', { class: 'num', text: String(item.version) }),
                el('td', {}, chips)
            ));
        }

        const shown = state.items.length;
        meta.textContent = `本页 ${shown} 条 · 共 ${state.total ?? '—'} 条 · keyset 游标分页`;
        badge.textContent = (state.total ?? '?') + ' 条';
        badge.className = 'badge ok';
        $('#next-page').disabled = !state.hasMore;
        $('#prev-page').disabled = state.page === 0;
        $('#page-label').textContent = `第 ${state.page + 1} 页`;
    }

    $('#next-page').addEventListener('click', async () => {
        if (!state.hasMore || !state.nextCursor) return;
        state.cursorStack.push(state.cursor);
        state.cursor = state.nextCursor;
        state.page += 1;
        await runQuery(false);
    });

    $('#prev-page').addEventListener('click', async () => {
        if (state.page === 0) return;
        state.cursor = state.cursorStack.pop() ?? null;
        state.page -= 1;
        await runQuery(false);
    });

    /* ---------------------------------------------------------------- 详情 */
    async function selectObject(objectId, options = {}) {
        state.selectedId = objectId;
        if (!options.keepTab) setTab('detail');
        renderResults();
        try {
            const [detail, edits] = await Promise.all([
                api('/api/objects/' + encodeURIComponent(objectId)),
                api('/api/audit/objects/' + encodeURIComponent(objectId) + '/edits?limit=20').catch(() => [])
            ]);
            state.detail = detail;
            state.edits = Array.isArray(edits) ? edits : [];
            state.linkType = '';
            state.linkDir = 'OUT';
            renderDetail();
            renderActionPanel();
            await loadLinks();
        } catch (e) {
            toast(e.message, 'err', e.code, e.details ? JSON.stringify(e.details) : null);
        }
    }

    async function loadLinks() {
        const d = state.detail;
        if (!d) return;
        const params = new URLSearchParams({ direction: state.linkDir, pageSize: '20' });
        if (state.linkType) params.set('linkType', state.linkType);
        try {
            const page = await api(`/api/objects/${encodeURIComponent(d.objectId)}/links?${params}`);
            state.objectLinks = page.items || [];
        } catch (e) {
            state.objectLinks = [];
            if (e.status !== 403) toast(e.message, 'err', e.code);
        }
        renderLinksSection();
    }

    function renderDetail() {
        const pane = $('#detail-pane');
        pane.innerHTML = '';
        const d = state.detail;
        if (!d) {
            pane.append(el('div', { class: 'empty' },
                el('strong', { text: '未选择对象' }),
                el('div', { text: '在左侧结果里点一行，或者执行一次 Action 后再看' })));
            return;
        }
        const type = typeOf(d.typeCode);

        pane.append(el('div', { class: 'detail-head' },
            el('div', { class: 'detail-title' },
                el('h2', { text: d.title || d.businessKey }),
                el('span', { class: 'badge', text: d.typeCode })),
            el('div', { class: 'detail-id', text: d.objectId }),
            el('div', { class: 'detail-badges' },
                el('span', { class: 'badge', text: 'v' + d.version }),
                el('span', { class: 'badge owner-' + d.stateOwner, text: d.stateOwner === 'ontology' ? '本体创建' : '来自上游' }),
                el('span', { class: 'badge', text: (type && type.properties ? type.properties.length : 0) + ' 个声明属性' })
            )
        ));

        pane.append(el('div', { class: 'section-title' }, '属性', el('span', { class: 'line' })));
        const entries = Object.entries(d.properties || {});
        if (!entries.length) {
            pane.append(el('div', { class: 'hint', text: '没有可返回的属性（可能被 ACL 裁剪，或该对象还没有属性值）' }));
        } else {
            const list = el('div', { class: 'prop-list' });
            for (const [key, value] of entries) {
                const def = propDef(key, d.typeCode);
                list.append(el('div', { class: 'prop-row' },
                    el('div', { class: 'prop-key' },
                        (def && def.label ? def.label : key),
                        el('code', { text: key })),
                    el('div', { class: 'prop-val' + (value === null || value === '' ? ' empty' : ''),
                        text: fmtValue(value) }),
                    def ? el('span', { class: 'badge owner-' + def.owner, text: def.owner }) : null
                ));
            }
            pane.append(list);
        }

        pane.append(el('div', { class: 'section-title' }, '一跳链接', el('span', { class: 'line' })));
        pane.append(el('div', { id: 'link-section' }));
        renderLinksSection();

        pane.append(el('div', { class: 'section-title' }, '变更历史（act_edit）', el('span', { class: 'line' })));
        if (!state.edits.length) {
            pane.append(el('div', { class: 'hint', text: '还没有 Action 修改过这个对象' }));
        } else {
            const tl = el('div', { class: 'timeline' });
            for (const e of state.edits) {
                const isLink = (e.editType || '').startsWith('LINK');
                tl.append(el('div', { class: 'tl-item' },
                    el('div', { class: 'tl-dot ' + (isLink ? 'link' : e.editType === 'CREATE' ? 'create' : '') }),
                    el('div', { class: 'tl-body' },
                        el('div', { class: 'tl-top' },
                            el('span', { class: 'badge ' + (isLink ? 'link' : 'ok'), text: e.editType }),
                            el('span', { class: 'mono', text: e.propKey }),
                            el('span', { class: 'tl-time', text: fmtTime(e.createdAt) })),
                        el('div', { class: 'tl-change' }, timelineChange(e))
                    )));
            }
            pane.append(tl);
        }
    }

    function timelineChange(e) {
        const isLink = String(e.editType || '').startsWith('LINK');
        const hasOld = e.oldValue !== undefined && e.oldValue !== null;
        const hasNew = e.newValue !== undefined && e.newValue !== null;
        if (isLink) {
            if (e.editType === 'LINK_CREATE') return ['建立链接 → ', el('span', { class: 'new', text: fmtValue(e.newValue) })];
            if (hasOld) return [el('span', { class: 'old', text: fmtValue(e.oldValue) }), ' 撤销'];
            return ['移除该类型全部链接'];
        }
        return [
            hasOld ? el('span', { class: 'old', text: fmtValue(e.oldValue) }) : '空',
            ' → ',
            el('span', { class: 'new', text: hasNew ? fmtValue(e.newValue) : '空' })
        ];
    }

    function renderLinksSection() {
        const box = $('#link-section');
        if (!box) return;
        box.innerHTML = '';
        const d = state.detail;
        if (!d) return;

        const related = state.linkTypes.filter(l =>
            (l.fromTypeCode === d.typeCode || l.toTypeCode === d.typeCode));
        const options = el('select', {
            onchange: e => { state.linkType = e.target.value; loadLinks(); }
        }, el('option', { value: '', selected: state.linkType === '' }, '全部链接类型'),
            related.map(l => el('option', { value: l.code, selected: state.linkType === l.code },
                `${l.code}（${l.fromTypeCode}→${l.toTypeCode}）`)));

        const dir = el('select', {
            onchange: e => { state.linkDir = e.target.value; loadLinks(); }
        },
            el('option', { value: 'OUT', selected: state.linkDir === 'OUT' }, '出向 OUT'),
            el('option', { value: 'IN', selected: state.linkDir === 'IN' }, '入向 IN'));

        box.append(el('div', { class: 'link-controls' }, options, dir));

        if (!state.objectLinks.length) {
            box.append(el('div', { class: 'hint', text: '这个方向没有链接（采集后才会物化链接）' }));
            return;
        }
        const list = el('div', { class: 'link-items' });
        for (const l of state.objectLinks) {
            list.append(el('button', { class: 'link-item', onclick: () => selectObject(l.objectId) },
                el('span', { class: 'badge link', text: l.linkType }),
                el('span', { class: 'mono', text: shortId(l.objectId, 30) }),
                el('span', { class: 'arrow', text: '·' }),
                el('span', { text: l.title || l.businessKey }),
                el('span', { class: 'spacer' }),
                el('span', { class: 'badge', text: 'v' + (l.version ?? 0) })
            ));
        }
        box.append(list);
    }

    /* ---------------------------------------------------------------- Action */
    function renderActionPanel() {
        const pane = $('#action-pane');
        pane.innerHTML = '';
        if (!state.type) {
            pane.append(el('div', { class: 'empty', text: '先选择对象类型' }));
            return;
        }
        const actions = state.actions.filter(a => a.targetTypeCode === state.type.code);
        if (!actions.length) {
            pane.append(el('div', { class: 'hint', text: '该类型还没有声明 Action（写操作必须通过 Action）' }));
            return;
        }
        if (!state.action.code || !actions.some(a => a.code === state.action.code)) {
            state.action.code = actions[0].code;
            state.action.values = {};
            state.action.result = null;
            state.action.key = newKey(state.action.code);
        }
        const action = currentAction();

        pane.append(el('div', { class: 'action-list' },
            actions.map(a => el('button', {
                class: 'action-pill' + (a.code === state.action.code ? ' active' : ''),
                onclick: () => {
                    state.action.code = a.code;
                    state.action.values = {};
                    state.action.result = null;
                    state.action.key = newKey(a.code);
                    renderActionPanel();
                }
            }, a.name || a.code))));

        pane.append(el('div', { class: 'action-desc' },
            describeAction(action)));

        const form = el('div', { class: 'form-grid' });
        for (const p of action.params || []) {
            form.append(renderParamRow(p, action));
        }
        pane.append(form);

        const pre = action.preconditions || [];
        if (pre.length) {
            const box = el('div', { class: 'precond-box' }, el('h4', {}, '前置条件（不满足返回 422）'));
            for (const c of pre) {
                box.append(el('div', { class: 'precond' },
                    el('span', { class: 'badge warn', text: c.errorCode }),
                    el('code', { text: describePrecondition(c) })));
            }
            pane.append(box);
        }

        pane.append(el('div', { class: 'action-foot' },
            el('button', { class: 'btn primary', onclick: executeAction }, '执行 Action'),
            el('label', { class: 'check' },
                el('input', {
                    type: 'checkbox', checked: state.action.useStale,
                    onchange: e => { state.action.useStale = e.target.checked; }
                }), '故意用旧版本（演示 409）'),
            el('label', { class: 'check' },
                el('input', {
                    type: 'checkbox', checked: state.action.replay,
                    onchange: e => { state.action.replay = e.target.checked; }
                }), '复用上次幂等键（演示重放）')
        ));

        pane.append(el('div', { class: 'hint', style: 'margin-top:10px' },
            el('div', {}, el('span', { class: 'muted' }, '幂等键 '), el('code', { text: state.action.key || '—' })),
            el('div', { class: 'muted', text: '版本号自动从当前对象/引用对象携带，不需要手填' })));

        renderActionResult();
    }

    function renderParamRow(p, action) {
        if (p.type === 'objectRef') {
            const opts = state.refOptions[p.refType];
            if (opts === undefined) {
                ensureRefOptions(p.refType);
                return el('div', { class: 'form-row' },
                    el('label', {}, p.name, el('code', { text: `objectRef · ${p.refType}` })),
                    el('div', { class: 'hint', text: '正在加载 ' + p.refType + ' 候选…' }));
            }
            const selected = state.action.values[p.name];
            if (p.array) {
                const sel = el('select', {
                    multiple: true, size: 5,
                    onchange: e => {
                        state.action.values[p.name] = Array.from(e.target.selectedOptions).map(o => o.value);
                    }
                }, opts.map(o => el('option', {
                    value: o.objectId,
                    selected: Array.isArray(selected) && selected.includes(o.objectId)
                }, `${o.objectId} · ${o.title || o.businessKey} · v${o.version}`)));
                return el('div', { class: 'form-row' },
                    el('label', {}, p.name, el('code', { text: `objectRef[] · ${p.refType}` })), sel);
            }
            const sel = el('select', {
                onchange: e => { state.action.values[p.name] = e.target.value; }
            }, el('option', { value: '' }, '— 选择 ' + p.refType + ' —'),
                opts.map(o => el('option', {
                    value: o.objectId,
                    selected: selected === o.objectId
                }, `${o.objectId} · ${o.title || o.businessKey} · v${o.version}`)));
            if (!selected && state.detail && state.detail.typeCode === p.refType && opts.some(o => o.objectId === state.detail.objectId)) {
                state.action.values[p.name] = state.detail.objectId;
                sel.value = state.detail.objectId;
            }
            return el('div', { class: 'form-row' },
                el('label', {}, p.name, el('code', { text: `objectRef · ${p.refType}` })), sel);
        }

        const numeric = ['integer', 'long', 'decimal'].includes(p.type);
        const input = el('input', {
            type: numeric ? 'number' : 'text',
            value: state.action.values[p.name] ?? '',
            placeholder: p.required ? '必填' : '可选',
            maxlength: p.maxLength || null,
            oninput: e => { state.action.values[p.name] = e.target.value; }
        });
        return el('div', { class: 'form-row' },
            el('label', {}, p.name, el('code', { text: p.type + (p.required ? ' · 必填' : '') })), input);
    }

    async function ensureRefOptions(typeCode) {
        if (state.refOptions[typeCode] !== undefined || state.refLoading[typeCode]) return;
        state.refLoading[typeCode] = true;
        try {
            const page = await api('/api/objects/query', {
                method: 'POST', body: { typeCode, pageSize: 100 }
            });
            state.refOptions[typeCode] = page.items || [];
        } catch (e) {
            state.refOptions[typeCode] = [];
        } finally {
            state.refLoading[typeCode] = false;
            if (state.detailTab === 'action') renderActionPanel();
        }
    }

    function describeAction(action) {
        const muts = (action.mutations || []).map(m => {
            if (m.op === 'update') return `改 ${m.prop}`;
            if (m.op === 'create') return `新建对象（${m.businessKeyExpr}）`;
            if (m.op === 'createLink') return `建链接 ${m.linkType}`;
            if (m.op === 'deleteLink') return `删链接 ${m.linkType}`;
            return m.op;
        });
        return `${action.code} · 目标 ${action.targetTypeCode} · 上限 ${action.limits ? action.limits.maxObjects : 1000} 个对象`
            + (muts.length ? ' · ' + muts.join('，') : '');
    }

    function describePrecondition(c) {
        if (c.prop) {
            const v = c.value === undefined ? '' : ' ' + JSON.stringify(c.value);
            return `${c.prop} ${c.type}${v}`;
        }
        if (c.linkType) {
            return `${c.linkType} ${c.type}${c.subject ? ' on ' + c.subject : ''}${c.value !== undefined ? ' < ' + c.value : ''}`;
        }
        return c.type;
    }

    function newKey(code) {
        return `ui-${code}-${Date.now().toString(36)}`;
    }

    function targetIds(action, params) {
        const ids = [];
        for (const p of action.params || []) {
            if (p.type !== 'objectRef' || p.refType !== action.targetTypeCode) continue;
            const v = params[p.name];
            if (Array.isArray(v)) ids.push(...v);
            else if (v) ids.push(v);
        }
        return Array.from(new Set(ids));
    }

    function versionOf(objectId, action) {
        if (state.detail && state.detail.objectId === objectId) return state.detail.version;
        for (const p of action.params || []) {
            if (p.type !== 'objectRef') continue;
            const opts = state.refOptions[p.refType] || [];
            const hit = opts.find(o => o.objectId === objectId);
            if (hit) return hit.version;
        }
        return 0;
    }

    async function executeAction() {
        const action = currentAction();
        if (!action) return;

        const params = {};
        for (const p of action.params || []) {
            let v = state.action.values[p.name];
            if (p.type === 'objectRef') {
                if (p.array) {
                    v = Array.isArray(v) ? v.filter(Boolean) : [];
                    if (!v.length) continue;
                } else if (!v) {
                    continue;
                }
                params[p.name] = v;
                continue;
            }
            if (v === '' || v === null || v === undefined) continue;
            if (['integer', 'long', 'decimal'].includes(p.type)) {
                const n = Number(v);
                if (Number.isNaN(n)) { toast(`参数 ${p.name} 需要数字`, 'err', 'PARAM_INVALID'); return; }
                params[p.name] = n;
            } else {
                params[p.name] = v;
            }
        }

        const targets = targetIds(action, params);
        const key = state.action.replay && state.action.lastKey[action.code]
            ? state.action.lastKey[action.code]
            : (state.action.key || newKey(action.code));

        const body = { params, idempotencyKey: key };
        if (!targets.length) {
            body.expectedVersion = 0;
        } else {
            const versions = {};
            for (const id of targets) {
                const base = versionOf(id, action);
                versions[id] = state.action.useStale ? Math.max(0, base - 1) : base;
            }
            body.expectedVersions = versions;
        }

        try {
            const res = await api(`/api/actions/${encodeURIComponent(action.code)}/execute`, {
                method: 'POST', body
            });
            state.action.lastKey[action.code] = key;
            state.action.result = { ok: true, data: res, request: body };
            toast(res.idempotentReplay ? '命中幂等键，返回上次结果' : 'Action 执行成功',
                'ok', res.status, 'runId ' + res.runId);
            if (state.selectedId && targets.includes(state.selectedId)) {
                await selectObject(state.selectedId, { keepTab: true });
            }
            await runQuery(false);
            await loadCounts();
            await refreshStats();
            renderActionPanel();
        } catch (e) {
            state.action.result = { ok: false, error: e, request: body };
            toast(e.message, 'err', e.code, e.details ? JSON.stringify(e.details) : null);
            renderActionResult();
        }
    }

    async function refreshStats() {
        try {
            state.stats = await api('/api/ingest/stats');
            renderStats();
        } catch { /* 忽略 */ }
    }

    function renderActionResult() {
        const pane = $('#action-pane');
        const old = pane.querySelector('.result-box');
        if (old) old.remove();
        const result = state.action.result;
        if (!result) return;

        const box = el('div', { class: 'result-box' });
        if (result.ok) {
            const d = result.data;
            box.append(el('div', { class: 'result-head' },
                el('span', { class: 'badge ok', text: 'SUCCESS' }),
                el('span', { class: 'badge', text: 'runId ' + d.runId }),
                d.idempotentReplay ? el('span', { class: 'badge warn', text: '幂等重放' }) : null));

            const targets = (d.result && d.result.targets) || [];
            const created = (d.result && d.result.created) || [];
            if (targets.length) {
                box.append(el('div', { class: 'muted', style: 'margin-bottom:6px' },
                    '目标版本：' + targets.map(t => `${t.objectId} → v${t.version}`).join('，')));
            }
            if (created.length) {
                box.append(el('div', { class: 'muted', style: 'margin-bottom:6px' },
                    '新建对象：' + created.join('，')));
            }

            const edits = (d.result && d.result.edits) || [];
            if (edits.length) {
                const table = el('table', { class: 'edit-table' },
                    el('thead', {}, el('tr', {},
                        el('th', {}, '属性'), el('th', {}, '前值'), el('th', {}, '后值'), el('th', {}, '类型'))),
                    el('tbody', {}, edits.map(e => el('tr', {},
                        el('td', {}, el('code', { text: e.propKey })),
                        el('td', {}, el('span', { class: 'old-val', text: fmtValue(e.oldValue) })),
                        el('td', {}, el('span', { class: 'new-val', text: fmtValue(e.newValue) })),
                        el('td', {}, el('span', { class: 'badge ' + (String(e.editType).startsWith('LINK') ? 'link' : 'ok'), text: e.editType }))
                    ))));
                box.append(table);
            }

            box.append(el('details', { style: 'margin-top:8px' },
                el('summary', { class: 'muted', style: 'cursor:pointer;font-size:11px' }, '原始响应 / 请求'),
                el('pre', { class: 'result-json', text: JSON.stringify({ request: result.request, response: d }, null, 2) })));
        } else {
            const e = result.error;
            box.append(el('div', { class: 'result-head' },
                el('span', { class: 'badge err', text: e.code || 'ERROR' }),
                el('span', { class: 'muted', text: e.message || '' })));
            box.append(el('pre', {
                class: 'result-json',
                text: JSON.stringify({ request: result.request, error: { code: e.code, message: e.message, details: e.details } }, null, 2)
            }));
        }
        pane.append(box);
    }

    /* ---------------------------------------------------------------- Tab */
    function setTab(name) {
        state.detailTab = name;
        document.querySelectorAll('.tab').forEach(t => {
            t.classList.toggle('active', t.dataset.tab === name);
        });
        $('#detail-pane').classList.toggle('hidden', name !== 'detail');
        $('#action-pane').classList.toggle('hidden', name !== 'action');
        $('#traverse-pane').classList.toggle('hidden', name !== 'traverse');
        if (name === 'action') renderActionPanel();
        if (name === 'traverse') renderTraversePane($('#traverse-pane'));
    }

    /* ---------------------------------------------------------------- 事件 */
    function bindApp() {
        $('#btn-agg').addEventListener('click', () => openAggregate());
        $('#btn-save-set').addEventListener('click', () => saveCurrentQuery());

        $('#add-filter').addEventListener('click', () => {
            const props = state.type ? (state.type.properties || []) : [];
            if (!props.length) return;
            state.filters.push({ prop: props[0].key, op: 'eq', value: '' });
            renderFilterRows();
        });
        $('#run-query').addEventListener('click', () => runQuery(true));
        $('#logout').addEventListener('click', showLogin);
        document.querySelectorAll('.tab').forEach(t => {
            t.addEventListener('click', () => setTab(t.dataset.tab));
        });
        $('#btn-ingest').addEventListener('click', async () => {
            const btn = $('#btn-ingest');
            if (btn.dataset.busy === '1') return;
            btn.dataset.busy = '1';
            btn.disabled = true;
            startIngestProgress();
            try {
                const r = await api('/api/ingest/all', { method: 'POST' });
                const sum = k => (r.objectTypes || []).reduce((a, t) => a + (t[k] || 0), 0);
                toast('采集完成', 'ok', 'ingest',
                    `新建 ${sum('created')} · 更新 ${sum('updated')} · 链接 ${r.links ? r.links.linksCreated : 0} 条`);
                await loadCounts();
                await refreshStats();
                if (state.type) await runQuery(true);
                stopIngestProgress();
            } catch (e) {
                if (e.status === 409) {
                    toast('已有采集正在进行', 'warn', e.code, '这里显示它的实时进度，结束后自动刷新');
                } else {
                    toast(e.message, 'err', e.code, e.details ? JSON.stringify(e.details) : null);
                    stopIngestProgress();
                }
            } finally {
                btn.dataset.busy = '';
                btn.disabled = false;
            }
        });

        // 采集进度：轮询后端进度，采集请求本身保持 pending
        $('#btn-validate').addEventListener('click', async () => {
            try {
                const r = await api('/api/meta/validate');
                if (r.valid) {
                    toast('元模型校验通过', 'ok', 'VALID',
                        `${r.objectTypes} 类型 / ${r.linkTypes} 链接 / ${r.actions} Action`);
                } else {
                    toast('元模型校验失败', 'err', 'METAMODEL_INVALID', (r.errors || []).slice(0, 3).join('；'));
                }
            } catch (e) {
                toast(e.message, 'err', e.code);
            }
        });
    }

    /* ---------------------------------------------------------------- 元模型刷新 / 视图切换 */
    async function refreshMetamodel() {
        const [types, links, actions] = await Promise.all([
            api('/api/meta/object-types'),
            api('/api/meta/link-types'),
            api('/api/meta/action-types')
        ]);
        state.types = types.map(v => ({ ...v.definition, revision: v.revision }));
        state.linkTypes = links.map(v => ({ ...v.definition, revision: v.revision }));
        state.actions = actions.map(v => ({ ...v.definition, revision: v.revision }));
        renderLinks();
        await loadCounts();
        if (state.type) {
            const still = typeOf(state.type.code);
            state.type = still || null;
            if (still) {
                state.filters = state.filters.filter(f => still.find(f.prop));
                state.fields = state.fields.filter(k => still.find(k));
                if (state.sort.prop && !still.find(state.sort.prop)
                    && !['objectId', 'businessKey', 'createdAt', 'updatedAt'].includes(state.sort.prop)) {
                    state.sort.prop = '';
                }
                renderTypeHeader();
                renderSortOptions();
                renderFilterRows();
                renderFields();
                renderActionPanel();
            }
        }
    }

    function setView(view) {
        document.querySelectorAll('.view-btn').forEach(b =>
            b.classList.toggle('active', b.dataset.view === view));
        const layout = document.querySelector('.layout');
        if (layout) layout.classList.toggle('hidden', view !== 'browse');
        const metaView = $('#meta-view');
        if (metaView) metaView.classList.toggle('hidden', view !== 'meta');
        if (view === 'meta' && window.__meta) window.__meta.open();
    }

    /* ---------------------------------------------------------------- 启动 */
    function boot() {
        bindLogin();
        bindApp();
        initTraverse({ selectObject });
        initAggregate({ onClose: () => { if (state.type) runQuery(true); } });
        initObjectSet({
            onApply: (filters, sort) => {
                state.filters = filters.map(f => ({ ...f }));
                state.sort = { prop: sort && sort.length ? sort[0].prop : '', dir: sort && sort.length ? (sort[0].op === 'desc' ? 'desc' : 'asc') : 'asc' };
                renderFilterRows();
                renderSortOptions();
            },
            onRerun: () => runQuery(true)
        });
        document.querySelectorAll('.view-btn').forEach(b =>
            b.addEventListener('click', () => setView(b.dataset.view)));
        const saved = sessionStorage.getItem(SESSION_KEY);
        if (saved) {
            try {
                const s = JSON.parse(saved);
                doLogin(s.user, s.pass).catch(() => { /* 回登录页 */ });
                return;
            } catch { /* ignore */ }
        }
    }

    window.__onto = { api, toast, el, state, typeOf, refreshMetamodel, setView, fmtValue, fmtTime };

    boot();
})();
