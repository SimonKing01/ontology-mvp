/* =====================================================================
   元模型配置视图
   只调用既有 /api/meta/** 与 /api/datasets，不新增写路径
   ===================================================================== */
(() => {
    'use strict';

    let G = null;
    const meta = {
        tab: 'object',
        selected: null,
        draft: null,
        editingNew: false,
        datasets: [],
        sources: [],
        loaded: false,
        errors: [],
        text: { pre: '', mut: '' }
    };

    const PATH = { object: 'object-types', link: 'link-types', action: 'action-types' };
    const TITLE = { object: '对象类型', link: '链接类型', action: 'Action 类型', dataset: '数据集' };

    const el = (...args) => G.el(...args);
    const clone = value => JSON.parse(JSON.stringify(value));

    /* ------------------------------------------------------------ 入口 */
    async function open() {
        if (!G) G = window.__onto;
        if (!meta.loaded) await loadLists();
        render();
    }

    async function loadLists() {
        try {
            const [datasets, sources] = await Promise.all([
                G.api('/api/datasets'),
                G.api('/api/datasources')
            ]);
            meta.datasets = datasets || [];
            meta.sources = sources || [];
            meta.loaded = true;
        } catch (e) {
            G.toast('加载数据集失败：' + e.message, 'err', e.code);
        }
    }

    /* ------------------------------------------------------------ 渲染 */
    function render() {
        const root = document.getElementById('meta-view');
        root.innerHTML = '';
        root.append(el('div', { class: 'meta-shell' }, nav(), main()));
    }

    function nav() {
        const items = [
            ['object', '对象类型', G.state.types.length],
            ['link', '链接类型', G.state.linkTypes.length],
            ['action', 'Action 类型', G.state.actions.length],
            ['dataset', '数据集', meta.datasets.length]
        ];
        const box = el('aside', { class: 'card pad-sm meta-nav' });
        for (const [key, label, count] of items) {
            box.append(el('button', {
                class: 'meta-nav-btn' + (meta.tab === key ? ' active' : ''),
                onclick: () => {
                    meta.tab = key;
                    meta.selected = null;
                    meta.draft = null;
                    meta.editingNew = false;
                    meta.errors = [];
                    render();
                }
            }, label, el('span', { class: 'count', text: String(count) })));
        }
        box.append(el('div', { class: 'hint', text: '保存后立即生效：revision + 1，旧版本进历史表；保存前会做全量校验。' }));
        return box;
    }

    function main() {
        const list = listOf(meta.tab);
        const head = el('section', { class: 'card meta-head' },
            el('h2', {}, TITLE[meta.tab]),
            el('div', { class: 'meta-chips' },
                list.map(item => el('button', {
                    class: 'meta-chip' + (meta.selected === item.code ? ' active' : ''),
                    onclick: () => openDef(item.code)
                }, item.code, el('span', { class: 'rev', text: 'r' + (item.revision || 1) })))),
            meta.tab !== 'dataset'
                ? el('button', { class: 'btn primary sm', onclick: startNew }, '＋ 新建')
                : null);

        const body = el('section', { class: 'card' });
        if (meta.tab === 'dataset') body.append(datasetView());
        else if (!meta.draft) body.append(emptyState());
        else if (meta.tab === 'object') body.append(objectForm());
        else if (meta.tab === 'link') body.append(linkForm());
        else body.append(actionForm());

        return el('div', { class: 'meta-main' }, head, body);
    }

    function listOf(tab) {
        if (tab === 'object') return G.state.types;
        if (tab === 'link') return G.state.linkTypes;
        if (tab === 'action') return G.state.actions;
        return [];
    }

    function emptyState() {
        return el('div', { class: 'empty' },
            el('strong', { text: '选一个定义开始编辑' }),
            el('div', { text: '也可以点右上角「＋ 新建」从零配一个对象类型' }),
            el('button', { class: 'btn ghost sm', onclick: startNew }, '＋ 新建'));
    }

    /* ------------------------------------------------------------ 选 / 新建 */
    function openDef(code) {
        const item = listOf(meta.tab).find(x => x.code === code);
        if (!item) return;
        meta.selected = code;
        meta.editingNew = false;
        meta.draft = clone(item);
        meta.errors = [];
        if (meta.tab === 'action') {
            meta.text.pre = JSON.stringify(item.preconditions || [], null, 2);
            meta.text.mut = JSON.stringify(item.mutations || [], null, 2);
        }
        render();
    }

    function startNew() {
        meta.editingNew = true;
        meta.selected = null;
        meta.errors = [];
        if (meta.tab === 'object') {
            meta.draft = {
                code: '', name: '', datasetId: (meta.datasets[0] && meta.datasets[0].id) || '',
                idField: '', titleField: '', status: 'active',
                properties: [{ key: '', label: '', type: 'string', owner: 'source', required: false, isArray: false, sourcePath: '', derivedExpr: '' }]
            };
        } else if (meta.tab === 'link') {
            const first = G.state.types[0] ? G.state.types[0].code : '';
            meta.draft = {
                code: '', name: '', fromTypeCode: first, toTypeCode: first, cardinality: 'N:M',
                fromField: '', toField: '', forwardName: '', reverseName: ''
            };
        } else {
            const first = G.state.types[0] ? G.state.types[0].code : '';
            meta.draft = {
                code: '', name: '', targetTypeCode: first,
                params: [{ name: '', type: 'string', required: true, refType: '', array: false, maxLength: null }],
                limits: { maxObjects: 1000 }
            };
            meta.text.pre = '[]';
            meta.text.mut = '[]';
        }
        render();
    }

    /* ------------------------------------------------------------ 通用控件 */
    function fld(label, hint, input) {
        return el('div', { class: 'fld' },
            el('label', {}, label, hint ? el('code', {}, hint) : null), input);
    }

    function input(value, oninput, attrs = {}) {
        const node = el('input', Object.assign({ value: value ?? '' }, attrs));
        node.addEventListener('input', e => oninput(e.target.value));
        return node;
    }

    function select(value, options, onchange, attrs = {}) {
        const node = el('select', attrs, options.map(([v, label]) =>
            el('option', { value: v }, label)));
        node.value = value ?? '';
        node.addEventListener('change', e => onchange(e.target.value));
        return node;
    }

    function errorsBox() {
        if (!meta.errors.length) return null;
        return el('div', { class: 'err-box' },
            el('h4', {}, '保存失败，共 ' + meta.errors.length + ' 处问题'),
            el('ul', {}, meta.errors.map(e => el('li', { text: e }))));
    }

    function actionsBar(kind, existing) {
        return el('div', { class: 'form-actions' },
            el('button', { class: 'btn primary', onclick: save }, '保存'),
            meta.selected ? el('button', {
                class: 'btn ghost', onclick: () => openDef(meta.selected)
            }, '重置') : null,
            meta.selected && existing ? el('button', {
                class: 'btn ghost', onclick: () => openHistory(kind, meta.selected)
            }, '历史版本') : null,
            el('span', { class: 'hint-inline', text: meta.editingNew ? '新建后 code 不可修改' : '修改会生成新 revision' }));
    }

    /* ------------------------------------------------------------ 对象类型 */
    function objectForm() {
        const d = meta.draft;
        const wrap = el('div');

        wrap.append(el('div', { class: 'form-section' },
            fld('code', 'code', input(d.code, v => d.code = v, { disabled: !meta.editingNew, placeholder: 'Flight' })),
            fld('name', 'name', input(d.name, v => d.name = v, { placeholder: '航班' })),
            fld('status', 'status', select(d.status, [
                ['experimental', 'experimental'], ['active', 'active'], ['deprecated', 'deprecated']
            ], v => d.status = v)),
            fld('datasetId', '映射到哪个数据集',
                select(d.datasetId, meta.datasets.map(x => [x.id, `${x.id} · ${x.schema || ''}.${x.table}`]), v => d.datasetId = v)),
            fld('idField', '业务主键属性', input(d.idField, v => d.idField = v, { placeholder: 'flightNo' })),
            fld('titleField', '展示名属性', input(d.titleField, v => d.titleField = v, { placeholder: 'flightNo' }))
        ));

        wrap.append(el('div', { class: 'sub-head' },
            '属性', el('span', { class: 'line' }),
            el('button', { class: 'btn ghost sm', onclick: () => { d.properties.push(blankProp()); render(); } }, '＋ 添加属性')));

        const table = el('table', { class: 'prop-editor' },
            el('thead', {}, el('tr', {},
                el('th', { style: 'width:96px' }, 'key'),
                el('th', { style: 'width:96px' }, 'label'),
                el('th', { style: 'width:88px' }, 'type'),
                el('th', { style: 'width:96px' }, 'owner'),
                el('th', { style: 'width:44px;text-align:center' }, '必填'),
                el('th', { style: 'width:44px;text-align:center' }, '多值'),
                el('th', { style: 'width:112px' }, 'sourcePath'),
                el('th', { style: 'width:150px' }, 'derivedExpr'),
                el('th', { style: 'width:34px' }, ''))),
            el('tbody', {}, d.properties.map((p, i) => propRow(d, p, i))));

        wrap.append(el('div', { class: 'prop-table-wrap' }, table));
        wrap.append(el('div', { class: 'hint', style: 'margin-top:8px' },
            el('div', {}, 'source：上游列，必须填 sourcePath；只读。'),
            el('div', {}, 'ontology：上游没有的列，不能填 sourcePath；只有 Action 能写。'),
            el('div', {}, 'derived：必须填 derivedExpr，如 src:weather_code / link:DelayedBy.minutes / count:FlownBy / sum:DelayedBy.minutes。')));

        const errBox = errorsBox();
        if (errBox) wrap.append(errBox);
        wrap.append(actionsBar('object', true));
        return wrap;
    }

    function blankProp() {
        return { key: '', label: '', type: 'string', owner: 'source', required: false, isArray: false, sourcePath: '', derivedExpr: '' };
    }

    function propRow(d, p, i) {
        const chk = (key) => {
            const node = el('input', { type: 'checkbox' });
            node.checked = !!p[key];
            node.addEventListener('change', e => { p[key] = e.target.checked; });
            return el('div', { class: 'chk' }, node);
        };
        return el('tr', {},
            el('td', {}, input(p.key, v => p.key = v, { placeholder: 'flightNo' })),
            el('td', {}, input(p.label, v => p.label = v, { placeholder: '航班号' })),
            el('td', {}, select(p.type, [
                ['string', 'string'], ['integer', 'integer'], ['long', 'long'], ['decimal', 'decimal'],
                ['boolean', 'boolean'], ['date', 'date'], ['timestamp', 'timestamp']
            ], v => { p.type = v; })),
            el('td', {}, select(p.owner, [
                ['source', 'source'], ['ontology', 'ontology'], ['derived', 'derived']
            ], v => { p.owner = v; })),
            el('td', {}, chk('required')),
            el('td', {}, chk('isArray')),
            el('td', {}, input(p.sourcePath || '', v => p.sourcePath = v, { title: '仅 owner=source 需要填源列名' })),
            el('td', {}, input(p.derivedExpr || '', v => p.derivedExpr = v, { title: '仅 owner=derived 需要填表达式' })),
            el('td', {}, el('button', {
                class: 'del', title: '删除属性',
                onclick: () => { d.properties.splice(i, 1); render(); }
            }, '×')));
    }

    /* ------------------------------------------------------------ 链接类型 */
    function linkForm() {
        const d = meta.draft;
        const typeOptions = G.state.types.map(t => [t.code, t.code]);
        const wrap = el('div');
        wrap.append(el('div', { class: 'form-section' },
            fld('code', 'code', input(d.code, v => d.code = v, { disabled: !meta.editingNew, placeholder: 'FlownBy' })),
            fld('name', 'name', input(d.name, v => d.name = v, { placeholder: '执飞' })),
            fld('cardinality', '基数', select(d.cardinality, [
                ['1:1', '1:1'], ['1:N', '1:N'], ['N:1', 'N:1'], ['N:M', 'N:M']
            ], v => d.cardinality = v)),
            fld('fromTypeCode', '起点类型', select(d.fromTypeCode, typeOptions, v => d.fromTypeCode = v)),
            fld('toTypeCode', '终点类型', select(d.toTypeCode, typeOptions, v => d.toTypeCode = v)),
            fld('fromField', '起点源列', input(d.fromField, v => d.fromField = v, { placeholder: 'tail_no' })),
            fld('toField', '终点源列', input(d.toField, v => d.toField = v, { placeholder: 'tail_no' })),
            fld('forwardName', '正向名', input(d.forwardName, v => d.forwardName = v, { placeholder: 'flownBy' })),
            fld('reverseName', '反向名', input(d.reverseName, v => d.reverseName = v, { placeholder: 'flights' }))
        ));
        wrap.append(el('div', { class: 'hint' },
            'fromField / toField 是两侧数据集的源列名。采集时按这两列 JOIN，把链接物化进 obj_link；不是运行期猜关系。'));
        const errBox = errorsBox();
        if (errBox) wrap.append(errBox);
        wrap.append(actionsBar('link', true));
        return wrap;
    }

    /* ------------------------------------------------------------ Action */
    function actionForm() {
        const d = meta.draft;
        const wrap = el('div');
        wrap.append(el('div', { class: 'form-section' },
            fld('code', 'code', input(d.code, v => d.code = v, { disabled: !meta.editingNew, placeholder: 'CancelFlight' })),
            fld('name', 'name', input(d.name, v => d.name = v, { placeholder: '取消航班' })),
            fld('targetTypeCode', '目标类型', select(d.targetTypeCode, G.state.types.map(t => [t.code, t.code]), v => d.targetTypeCode = v)),
            fld('maxObjects', 'limits.maxObjects', input(d.limits ? d.limits.maxObjects : 1000, v => d.limits = { maxObjects: Number(v) || 0 }, { type: 'number' }))
        ));

        wrap.append(el('div', { class: 'sub-head' },
            '参数', el('span', { class: 'line' }),
            el('button', {
                class: 'btn ghost sm',
                onclick: () => { d.params.push({ name: '', type: 'string', required: true, refType: '', array: false, maxLength: null }); render(); }
            }, '＋ 添加参数')));

        const table = el('table', { class: 'prop-editor' },
            el('thead', {}, el('tr', {},
                el('th', { style: 'width:110px' }, 'name'),
                el('th', { style: 'width:110px' }, 'type'),
                el('th', { style: 'width:44px;text-align:center' }, '必填'),
                el('th', { style: 'width:110px' }, 'refType'),
                el('th', { style: 'width:44px;text-align:center' }, '数组'),
                el('th', { style: 'width:80px' }, 'maxLength'),
                el('th', { style: 'width:34px' }, ''))),
            el('tbody', {}, d.params.map((p, i) => el('tr', {},
                el('td', {}, input(p.name, v => p.name = v, { placeholder: 'flightId' })),
                el('td', {}, select(p.type, [
                    ['string', 'string'], ['integer', 'integer'], ['long', 'long'], ['decimal', 'decimal'],
                    ['boolean', 'boolean'], ['date', 'date'], ['timestamp', 'timestamp'], ['objectRef', 'objectRef']
                ], v => { p.type = v; render(); })),
                el('td', {}, (() => {
                    const n = el('input', { type: 'checkbox' });
                    n.checked = !!p.required;
                    n.addEventListener('change', e => p.required = e.target.checked);
                    return el('div', { class: 'chk' }, n);
                })()),
                el('td', {}, p.type === 'objectRef'
                    ? select(p.refType || '', G.state.types.map(t => [t.code, t.code]).concat([['', '—']]), v => p.refType = v)
                    : el('span', { class: 'muted', text: '—' })),
                el('td', {}, (() => {
                    const n = el('input', { type: 'checkbox' });
                    n.checked = !!p.array;
                    n.disabled = p.type !== 'objectRef';
                    n.addEventListener('change', e => p.array = e.target.checked);
                    return el('div', { class: 'chk' }, n);
                })()),
                el('td', {}, p.type === 'string'
                    ? input(p.maxLength ?? '', v => p.maxLength = v ? Number(v) : null, { type: 'number' })
                    : el('span', { class: 'muted', text: '—' })),
                el('td', {}, el('button', {
                    class: 'del', title: '删除参数',
                    onclick: () => { d.params.splice(i, 1); render(); }
                }, '×')))))
        );
        wrap.append(el('div', { class: 'prop-table-wrap' }, table));

        wrap.append(el('div', { class: 'sub-head' }, '前置条件（JSON 数组）', el('span', { class: 'line' })));
        const pre = el('textarea', {
            class: 'json-area', spellcheck: 'false',
            placeholder: '[{"type":"notEquals","prop":"status","value":"DEPARTED","errorCode":"ORDER_DEPARTED"}]'
        });
        pre.value = meta.text.pre;
        pre.addEventListener('input', e => { meta.text.pre = e.target.value; });
        wrap.append(pre);

        wrap.append(el('div', { class: 'sub-head' }, '变更（JSON 数组）', el('span', { class: 'line' }),
            el('button', {
                class: 'btn ghost sm',
                onclick: () => {
                    try {
                        meta.text.pre = JSON.stringify(JSON.parse(meta.text.pre), null, 2);
                        meta.text.mut = JSON.stringify(JSON.parse(meta.text.mut), null, 2);
                        render();
                    } catch (e) { G.toast('JSON 解析失败：' + e.message, 'err', 'PARAM_INVALID'); }
                }
            }, '格式化')));
        const mut = el('textarea', {
            class: 'json-area', spellcheck: 'false',
            placeholder: '[{"op":"update","prop":"status","valueExpr":"literal:\'CANCELLED\'"}]'
        });
        mut.value = meta.text.mut;
        mut.addEventListener('input', e => { meta.text.mut = e.target.value; });
        wrap.append(mut);

        wrap.append(el('div', { class: 'hint', style: 'margin-top:8px' },
            '变更只支持 update / create / createLink / deleteLink；update 只能改 owner=ontology 的属性，否则保存时会被拒绝。'));

        const errBox = errorsBox();
        if (errBox) wrap.append(errBox);
        wrap.append(actionsBar('action', true));
        return wrap;
    }

    /* ------------------------------------------------------------ 数据集 */
    function datasetView() {
        const wrap = el('div');
        const table = el('table', { class: 'meta-table' },
            el('thead', {}, el('tr', {},
                el('th', {}, 'id'), el('th', {}, '名称'), el('th', {}, '源'),
                el('th', {}, 'schema.table'), el('th', {}, 'maxRows'))),
            el('tbody', {}, meta.datasets.map(d => el('tr', {},
                el('td', {}, el('code', { text: d.id })),
                el('td', { text: d.name }),
                el('td', {}, el('code', { text: '#' + d.sourceId })),
                el('td', {}, el('code', { text: `${d.schema || ''}.${d.table}` })),
                el('td', { text: String(d.maxRows) })
            ))));
        wrap.append(el('div', { class: 'prop-table-wrap' }, table));

        wrap.append(el('div', { class: 'sub-head', style: 'margin-top:16px' }, '新建数据集', el('span', { class: 'line' })));
        const draft = meta.datasetDraft || (meta.datasetDraft = {
            id: '', name: '', sourceId: meta.sources[0] ? meta.sources[0].id : '',
            schema: 'airline_domain', table: '', maxRows: 10000
        });
        wrap.append(el('div', { class: 'form-section' },
            fld('id', '对象类型会引用它', input(draft.id, v => draft.id = v, { placeholder: 'ds_gate' })),
            fld('name', 'name', input(draft.name, v => draft.name = v)),
            fld('数据源', 'sourceId', select(String(draft.sourceId || ''), meta.sources.map(s => [String(s.id), `#${s.id} · ${s.name}`]), v => draft.sourceId = v)),
            fld('schema', 'schema', input(draft.schema, v => draft.schema = v)),
            fld('table', '表/视图名', input(draft.table, v => draft.table = v, { placeholder: 'gate' })),
            fld('maxRows', '采集上限', input(draft.maxRows, v => draft.maxRows = Number(v) || 0, { type: 'number' }))
        ));
        wrap.append(el('div', { class: 'form-actions' },
            el('button', { class: 'btn primary', onclick: createDataset }, '创建数据集'),
            el('span', { class: 'hint-inline', text: '数据源（含密码）请用 API 创建：POST /api/datasources，密码会加密落库' })));
        return wrap;
    }

    async function createDataset() {
        const d = meta.datasetDraft;
        try {
            await G.api('/api/datasets', {
                method: 'POST', body: {
                    id: d.id, name: d.name || d.id, sourceId: Number(d.sourceId),
                    schema: d.schema, table: d.table, keyFields: '', maxRows: Number(d.maxRows) || 10000
                }
            });
            G.toast('数据集已创建', 'ok', d.id);
            meta.datasetDraft = null;
            await loadLists();
            render();
        } catch (e) {
            G.toast(e.message, 'err', e.code, e.details ? JSON.stringify(e.details) : null);
        }
    }

    /* ------------------------------------------------------------ 保存 */
    async function save() {
        meta.errors = [];
        try {
            if (meta.tab === 'object') await saveObject();
            else if (meta.tab === 'link') await saveLink();
            else await saveAction();
        } catch (e) {
            if (e.code === 'METAMODEL_INVALID' && e.details && e.details.errors) {
                meta.errors = e.details.errors;
            } else {
                meta.errors = [e.message + (e.code ? '（' + e.code + '）' : '')];
            }
            render();
            G.toast('保存失败：' + e.message, 'err', e.code,
                e.details ? JSON.stringify(e.details).slice(0, 300) : null);
        }
    }

    function clientErrors(list) {
        if (list.length) {
            meta.errors = list;
            render();
            G.toast('还有 ' + list.length + ' 处需要修正', 'err', 'PARAM_INVALID');
            return true;
        }
        return false;
    }

    async function saveObject() {
        const d = meta.draft;
        const errs = [];
        if (!/^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(d.code || '')) errs.push('code 不合法：字母开头，只能含字母/数字/下划线');
        if (!d.datasetId) errs.push('datasetId 不能为空');
        if (!d.idField) errs.push('idField 不能为空');
        if (!d.properties.length) errs.push('至少需要一个属性');
        const keys = new Set();
        d.properties.forEach((p, i) => {
            const at = `属性[${i + 1}]`;
            if (!/^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(p.key || '')) errs.push(`${at} key 不合法`);
            if (keys.has(p.key)) errs.push(`${at} key 重复：${p.key}`);
            keys.add(p.key);
            if (p.owner === 'source' && !p.sourcePath) errs.push(`${at} ${p.key}：owner=source 必须填 sourcePath`);
            if (p.owner === 'ontology' && p.sourcePath) errs.push(`${at} ${p.key}：owner=ontology 不能填 sourcePath`);
            if (p.owner === 'derived' && !p.derivedExpr) errs.push(`${at} ${p.key}：owner=derived 必须填 derivedExpr`);
        });
        if (d.idField && !keys.has(d.idField)) errs.push('idField 必须是已声明的属性');
        if (d.titleField && !keys.has(d.titleField)) errs.push('titleField 必须是已声明的属性');
        if (clientErrors(errs)) return;

        const payload = {
            code: d.code, name: d.name || d.code, datasetId: d.datasetId,
            idField: d.idField, titleField: d.titleField || null, status: d.status || 'active',
            properties: d.properties.map(p => {
                const out = {
                    key: p.key, label: p.label || p.key, type: p.type, owner: p.owner,
                    required: !!p.required, isArray: !!p.isArray
                };
                if (p.sourcePath) out.sourcePath = p.sourcePath;
                if (p.derivedExpr) out.derivedExpr = p.derivedExpr;
                return out;
            })
        };
        const path = meta.editingNew ? '/api/meta/object-types' : `/api/meta/object-types/${encodeURIComponent(d.code)}`;
        const res = await G.api(path, { method: meta.editingNew ? 'POST' : 'PUT', body: payload });
        await afterSave('object', payload.code, res);
    }

    async function saveLink() {
        const d = meta.draft;
        const errs = [];
        if (!/^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(d.code || '')) errs.push('code 不合法');
        if (!d.fromTypeCode || !d.toTypeCode) errs.push('起点/终点类型不能为空');
        if (!d.fromField || !d.toField) errs.push('fromField / toField 不能为空（源列名）');
        if (clientErrors(errs)) return;
        const payload = {
            code: d.code, name: d.name || d.code,
            fromTypeCode: d.fromTypeCode, toTypeCode: d.toTypeCode,
            cardinality: d.cardinality, fromField: d.fromField, toField: d.toField,
            forwardName: d.forwardName || null, reverseName: d.reverseName || null
        };
        const path = meta.editingNew ? '/api/meta/link-types' : `/api/meta/link-types/${encodeURIComponent(d.code)}`;
        const res = await G.api(path, { method: meta.editingNew ? 'POST' : 'PUT', body: payload });
        await afterSave('link', payload.code, res);
    }

    async function saveAction() {
        const d = meta.draft;
        const errs = [];
        if (!/^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(d.code || '')) errs.push('code 不合法');
        if (!d.targetTypeCode) errs.push('targetTypeCode 不能为空');
        let pre;
        let mut;
        try { pre = JSON.parse(meta.text.pre || '[]'); } catch (e) { errs.push('前置条件 JSON 解析失败：' + e.message); }
        try { mut = JSON.parse(meta.text.mut || '[]'); } catch (e) { errs.push('变更 JSON 解析失败：' + e.message); }
        if (clientErrors(errs)) return;

        const params = d.params.map(p => {
            const out = { name: p.name, type: p.type, required: !!p.required };
            if (p.type === 'objectRef') {
                out.refType = p.refType;
                out.array = !!p.array;
            }
            if (p.maxLength) out.maxLength = Number(p.maxLength);
            return out;
        });
        const payload = {
            code: d.code, name: d.name || d.code, targetTypeCode: d.targetTypeCode,
            params, preconditions: pre, mutations: mut,
            limits: { maxObjects: Number(d.limits && d.limits.maxObjects) || 1000 }
        };
        const path = meta.editingNew ? '/api/meta/action-types' : `/api/meta/action-types/${encodeURIComponent(d.code)}`;
        const res = await G.api(path, { method: meta.editingNew ? 'POST' : 'PUT', body: payload });
        await afterSave('action', payload.code, res);
    }

    async function afterSave(kind, code, res) {
        G.toast('已保存', 'ok', 'revision ' + (res && res.revision ? res.revision : '?'));
        await G.refreshMetamodel();
        meta.tab = kind;
        meta.editingNew = false;
        meta.errors = [];
        openDef(code);
    }

    /* ------------------------------------------------------------ 历史版本 */
    async function openHistory(kind, code) {
        try {
            const list = await G.api(`/api/meta/${PATH[kind]}/${encodeURIComponent(code)}/history`);
            showModal(`${code} 的历史版本`, body => {
                if (!list.length) {
                    body.append(el('div', { class: 'hint', text: '还没有历史版本（这是第一次定义）' }));
                    return;
                }
                const revs = el('div', { class: 'rev-list' });
                const pre = el('pre', { class: 'result-json', text: '选择上面的版本查看内容' });
                for (const rev of list) {
                    revs.append(el('div', { class: 'rev-item' },
                        el('span', { class: 'badge', text: 'r' + rev.revision }),
                        el('span', { class: 'mono', text: fmtTime(rev.createdAt) }),
                        el('span', { class: 'spacer' }),
                        el('button', {
                            class: 'btn ghost sm',
                            onclick: async () => {
                                const detail = await G.api(`/api/meta/${PATH[kind]}/${encodeURIComponent(code)}?revision=${rev.revision}`);
                                pre.textContent = JSON.stringify(detail.definition, null, 2);
                            }
                        }, '查看'),
                        el('button', {
                            class: 'btn ghost sm',
                            onclick: () => restore(kind, code, rev.revision)
                        }, '以此版本新建 revision')));
                }
                body.append(revs, pre);
            });
        } catch (e) {
            G.toast(e.message, 'err', e.code);
        }
    }

    async function restore(kind, code, revision) {
        if (!window.confirm(`用 revision ${revision} 的内容覆盖当前定义？会生成一个新的 revision。`)) return;
        try {
            const detail = await G.api(`/api/meta/${PATH[kind]}/${encodeURIComponent(code)}?revision=${revision}`);
            await G.api(`/api/meta/${PATH[kind]}/${encodeURIComponent(code)}`, {
                method: 'PUT', body: detail.definition
            });
            closeModal();
            await G.refreshMetamodel();
            meta.tab = kind;
            openDef(code);
            G.toast('已按旧版本生成新 revision', 'ok', 'r' + revision);
        } catch (e) {
            G.toast(e.message, 'err', e.code, e.details ? JSON.stringify(e.details) : null);
        }
    }

    function showModal(title, fill) {
        closeModal();
        const body = el('div', { class: 'modal-body' });
        const backdrop = el('div', { class: 'modal-backdrop', onclick: e => { if (e.target === backdrop) closeModal(); } },
            el('div', { class: 'modal' },
                el('div', { class: 'modal-head' },
                    el('h3', {}, title),
                    el('button', { class: 'close', onclick: closeModal }, '×')),
                body));
        body.id = 'modal-body';
        fill(body);
        document.body.append(backdrop);
        window.__modal = backdrop;
    }

    function closeModal() {
        if (window.__modal) { window.__modal.remove(); window.__modal = null; }
    }

    const fmtTime = iso => iso ? new Date(iso).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '';

    window.__meta = { open };
})();
