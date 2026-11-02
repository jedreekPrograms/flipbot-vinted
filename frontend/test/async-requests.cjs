/* global require, __dirname, module, global */
const assert = require('node:assert/strict');
const { test } = require('node:test');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const ts = require('typescript');
const root = path.resolve(__dirname, '../src');
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const settle = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };

// Execute the real hooks with controllable effects, promises and timers, without a DOM/network.
function fixture(relative, api) {
    const state = [], effects = [], memo = [], refs = [], pending = [];
    let cursor = 0, writes = 0;
    const same = (a, b) => a && b && a.length === b.length && a.every((x, i) => Object.is(x, b[i]));
    const react = {
        useState(initial) {
            const index = cursor++;
            if (!(index in state)) state[index] = typeof initial === 'function' ? initial() : initial;
            return [state[index], value => { state[index] = typeof value === 'function' ? value(state[index]) : value; writes++; }];
        },
        useRef(initial) { const index = cursor++; return refs[index] ??= { current: initial }; },
        useMemo(factory, deps) {
            const index = cursor++;
            if (!memo[index] || !same(memo[index].deps, deps)) memo[index] = { value: factory(), deps };
            return memo[index].value;
        },
        useCallback(callback, deps) { return react.useMemo(() => callback, deps); },
        useEffect(callback, deps) {
            const index = cursor++;
            if (!effects[index] || !same(effects[index].deps, deps)) {
                pending.push(() => {
                    effects[index]?.cleanup?.();
                    effects[index] = { deps, cleanup: callback() };
                });
            }
        },
    };
    const cache = new Map();
    function load(filename) {
        if (cache.has(filename)) return cache.get(filename);
        const source = ts.transpileModule(readFileSync(filename, 'utf8'), { compilerOptions: {
            target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS,
        } }).outputText;
        const loaded = { exports: {} };
        const requireSource = id => {
            if (id === 'react') return react;
            if (id.includes('/api/')) return api;
            return load(path.resolve(path.dirname(filename), `${id}.ts`));
        };
        new Function('require', 'module', 'exports', source)(requireSource, loaded, loaded.exports);
        cache.set(filename, loaded.exports);
        return loaded.exports;
    }
    const loaded = load(path.join(root, relative));
    const hook = loaded.default ?? loaded.useDictionaryCatalog;
    return {
        render() { cursor = 0; const value = hook(); while (pending.length) pending.shift()(); return value; },
        unmount() { for (const effect of effects) effect?.cleanup?.(); },
        get writes() { return writes; },
    };
}
function clock() {
    let id = 0;
    const timers = new Map(), listeners = new Map();
    const previous = { window: global.window, document: global.document };
    global.window = { setInterval(callback, ms) { timers.set(++id, { callback, ms }); return id; }, clearInterval(key) { timers.delete(key); } };
    global.document = { hidden: false, addEventListener(name, callback) { listeners.set(name, callback); }, removeEventListener(name) { listeners.delete(name); } };
    return {
        tick(ms) { for (const timer of timers.values()) if (timer.ms === ms) timer.callback(); },
        visibility(hidden) { global.document.hidden = hidden; listeners.get('visibilitychange')?.(); },
        restore() { global.window = previous.window; global.document = previous.document; },
        get timers() { return timers.size; }, get listeners() { return listeners.size; },
    };
}

test('Dictionary brand switches, clearing and unmount reject stale successes, failures and loading writes', async () => {
    for (const oldFails of [false, true]) {
        const requests = [];
        const f = fixture('features/dictionaries/useDictionaryCatalog.ts', {
            getBrands: async () => [{ id: 1, name: 'A' }, { id: 2, name: 'B' }], getCategories: async () => [],
            getModelsByBrand: id => { const request = { ...deferred(), id }; requests.push(request); return request.promise; },
        });
        f.render(); await settle();
        let view = f.render(); // Starts A.
        view.setSelectedBrandId('2'); view = f.render(); // Starts B.
        assert.deepEqual(requests.map(r => r.id), [1, 2]);
        if (oldFails) requests[0].reject(new Error('old failure'));
        else requests[0].resolve([{ id: 10, name: 'old A' }]);
        await settle(); view = f.render();
        assert.equal(view.isModelsLoading, true); assert.equal(view.errorMessage, null); assert.deepEqual(view.models, []);
        requests[1].resolve([{ id: 20, name: 'new B' }]); await settle(); view = f.render();
        assert.equal(view.models[0].id, 20); assert.equal(view.isModelsLoading, false);
        const oldReload = view.loadModels(2);
        view.setSelectedBrandId(''); view = f.render();
        requests[2].resolve([{ id: 21, name: 'cleared B' }]); await oldReload;
        view = f.render(); assert.deepEqual(view.models, []); assert.equal(view.isModelsLoading, false);
        const reload = view.loadBaseData(); f.unmount(); const before = f.writes;
        await reload; assert.equal(f.writes, before);
    }
});

test('Dictionary base reloads also retain only the newest result and error', async () => {
    const requests = [];
    const f = fixture('features/dictionaries/useDictionaryCatalog.ts', {
        getBrands: () => { const request = deferred(); requests.push(request); return request.promise; },
        getCategories: async () => [], getModelsByBrand: async () => [],
    });
    let view = f.render(); const newer = view.loadBaseData();
    requests[1].resolve([{ id: 2, name: 'latest' }]); await newer;
    requests[0].reject(new Error('old failure')); await settle(); view = f.render();
    assert.equal(view.brands[0].id, 2); assert.equal(view.errorMessage, null); assert.equal(view.isLoading, false); f.unmount();
});

test('Runtime polling is visible and single-flight; session preview PUT and follow-up GET never wait for a background read', async () => {
    const c = clock();
    try {
        const reads = [], actions = [];
        const f = fixture('features/runtime/useRuntimeDashboard.ts', {
            getRuntimeDashboard: () => { const request = deferred(); reads.push(request); return request.promise; },
            setRuntimeSessionPreview: (id, enabled) => { actions.push([id, enabled]); return Promise.resolve(); },
        });
        let view = f.render(); c.tick(5000); c.tick(5000); assert.equal(reads.length, 1);
        const action = view.handleSessionPreview(7, true);
        await view.handleSessionPreview(7, true);
        assert.deepEqual(actions, [[7, true]]); await settle(); assert.equal(reads.length, 2);
        reads[1].resolve({ bots: [], marker: 'after preview' }); await action;
        reads[0].reject(new Error('stale initial read')); await settle(); view = f.render();
        assert.equal(view.data.marker, 'after preview'); assert.equal(view.errorMessage, null); assert.equal(view.isLoading, false);
        c.visibility(true); c.tick(5000); assert.equal(reads.length, 2);
        c.visibility(false); c.tick(5000); assert.equal(reads.length, 3);
        f.unmount(); const before = f.writes;
        reads[2].resolve({ bots: [], marker: 'unmounted' }); await settle(); assert.equal(f.writes, before);
        assert.equal(c.timers, 0); assert.equal(c.listeners, 0);
    } finally { c.restore(); }
});

test('Bot overview batches all bots and pauses overlapping, hidden and unmounted refreshes', async () => {
    const c = clock();
    try {
        const requests = [];
        const bots = [{ id: 7, status: 'RUNNING' }, { id: 8, status: 'STOPPED' }];
        let botReads = 0;
        const f = fixture('features/bots/useBotsOverview.ts', {
            getBots: async () => { botReads++; return bots; },
            getBotsOverview: () => { const request = deferred(); requests.push(request); return request.promise; },
            getBotDailyActivity: () => { throw new Error('Unexpected per-bot activity request'); },
            getBotRuntimeState: () => { throw new Error('Unexpected per-bot runtime request'); },
        });
        f.render(); await settle();
        assert.equal(requests.length, 1);
        requests[0].resolve([
            { botId: 7, activity: { dailyLimitUsed: 2 }, runtime: { runtimeStatus: 'WORKING' } },
            { botId: 8, activity: null, runtime: null },
        ]);
        await settle();
        let view = f.render();
        assert.equal(botReads, 1);
        assert.equal(view.isInitialLoading, false);
        assert.equal(view.activityByBotId[7].dailyLimitUsed, 2);
        assert.equal(view.runtimeByBotId[7].runtimeStatus, 'WORKING');
        assert.equal(view.runtimeByBotId[8], undefined);
        c.tick(5000); c.tick(5000); assert.equal(requests.length, 2);
        requests[1].resolve([{ botId: 7, activity: null, runtime: { runtimeStatus: 'IDLE' } }]);
        await settle(); view = f.render();
        assert.equal(view.activityByBotId[7].dailyLimitUsed, 2);
        assert.equal(view.runtimeByBotId[7].runtimeStatus, 'IDLE');
        c.visibility(true); c.tick(5000); assert.equal(requests.length, 2);
        c.visibility(false); assert.equal(requests.length, 3);
        f.unmount(); const before = f.writes;
        requests[2].resolve([{ botId: 7, activity: null, runtime: { runtimeStatus: 'WORKING' } }]);
        await settle(); assert.equal(f.writes, before);
        assert.equal(c.timers, 0); assert.equal(c.listeners, 0);
    } finally { c.restore(); }
});

test('Price planning never overwrites a newer post-save snapshot and background refresh keeps unsaved price drafts', async () => {
    const c = clock();
    try {
        const requests = [];
        const model = { id: 1, brandId: 2, name: 'Model', proposedOfferPrice: 50, expectedResalePrice: 100, marketMinPrice: 60, marketMaxPrice: 120 };
        const f = fixture('features/price-matrix/usePriceMatrix.ts', {
            getBrands: async () => [{ id: 2, name: 'Brand' }], getModelsByBrand: async () => [model],
            getModelPlanning: () => { const request = deferred(); requests.push(request); return request.promise; },
            updateModelPricing: async (brand, id, prices) => ({ ...model, ...prices }),
        });
        f.render(); c.tick(15000); assert.equal(requests.length, 1);
        requests[0].resolve([{ modelId: 1, offersToday: 1 }]); await settle();
        let view = f.render(); view.updateDraft(1, 'marketMinPrice', '70'); view = f.render();
        c.tick(15000); c.tick(15000); assert.equal(requests.length, 2);
        const save = view.saveModel(model); await settle(); assert.equal(requests.length, 3);
        requests[2].resolve([{ modelId: 1, offersToday: 202 }]); await save;
        requests[1].resolve([{ modelId: 1, offersToday: 101 }]); await settle(); view = f.render();
        assert.equal(view.planningByModel[1].offersToday, 202);
        view.updateDraft(1, 'proposedOfferPrice', '55'); view = f.render();
        c.visibility(true); c.tick(15000); assert.equal(requests.length, 3);
        c.visibility(false); assert.equal(requests.length, 4);
        requests[3].resolve([{ modelId: 1, offersToday: 303 }]); await settle(); view = f.render();
        assert.equal(view.drafts[1].proposedOfferPrice, '55'); assert.equal(view.planningByModel[1].offersToday, 303);
        c.tick(15000); assert.equal(requests.length, 5); f.unmount(); const before = f.writes;
        requests[4].resolve([{ modelId: 1, offersToday: 404 }]); await settle(); assert.equal(f.writes, before);
    } finally { c.restore(); }
});
