/* global require, __dirname, module */
// Differential checks against the backed-up working version. No network/API calls.
const assert = require('node:assert/strict');
const { test } = require('node:test');
const { execFileSync } = require('node:child_process');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const ts = require('typescript');
const React = require('react');
const { renderToStaticMarkup } = require('react-dom/server');
const postcss = require('postcss');
const root = path.resolve(__dirname, '..');
const baseline = '184d99a80e4b8b75e86483e935ec0de206394a23';

function compile(module, source, filename) {
    const originalRequire = module.require.bind(module);
    module.require = id => {
        // The extracted views are pure: calling an API here must fail the test.
        if (id.includes('/api/')) return new Proxy({}, {
            get: () => () => { throw new Error('Unexpected API call during UI regression check'); },
        });
        return originalRequire(id.replace("/bots/create/", "/bots/form/"));
    };
    const output = ts.transpileModule(source, { compilerOptions: {
        target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS,
        jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true,
    } }).outputText;
    module._compile(output, filename);
}
for (const extension of ['.ts', '.tsx']) {
    require.extensions[extension] = (module, filename) => compile(module, readFileSync(filename, 'utf8'), filename);
}
require.extensions['.css'] = () => {};

function original(relative, names) {
    const source = execFileSync('git', ['show', `${baseline}:frontend/${relative}`], { encoding: 'utf8' });
    const filename = path.join(root, relative);
    const loaded = new Module(filename, module);
    loaded.filename = filename;
    loaded.paths = Module._nodeModulePaths(path.dirname(filename));
    compile(loaded, source + (names.length ? `\nexport { ${names.join(', ')} };` : ""), filename);
    return loaded.exports;
}
const oldStats = original('src/pages/StatisticsPage.tsx', [
    'ModelPicker', 'FilterPills', 'PriceTimelineChart', 'VolumeChart', 'HistogramChart', 'ModelTable',
]);
const oldPrices = original('src/pages/PriceMatrixPage.tsx', ['BrandPriceSheet', 'parseOptionalPositivePrice']);
const current = relative => require(path.join(root, 'src', relative));
const prePanelCleanup = '184d99a80e4b8b75e86483e935ec0de206394a23';
const previousSource = page => execFileSync('git', ['show', `${prePanelCleanup}:frontend/src/pages/${page}.tsx`], { encoding: 'utf8' });
function sourceModule(relative, source) {
    const filename = path.join(root, relative);
    const loaded = new Module(filename, module);
    loaded.filename = filename;
    loaded.paths = Module._nodeModulePaths(path.dirname(filename));
    compile(loaded, source, filename);
    return loaded.exports;
}
const markup = (component, props) => renderToStaticMarkup(React.createElement(component, props));
const noop = () => {};
function sameView(previous, next, scenarios) {
    for (const props of scenarios) assert.equal(markup(next, props), markup(previous, props));
}

test('Dictionary administration pages keep their initial controls and loading state', () => {
    for (const page of ['DictionariesPage', 'ManageDictionariesPage']) {
        sameView(original(`src/pages/${page}.tsx`, []).default, current(`pages/${page}.tsx`).default, [{}]);
    }
});

// Run the actual old/new loader callbacks with observable setters and API stubs.
// This compares requests, state transitions and selection rather than duplicating the algorithm.
function dictionaryCallback(source, name, context) {
    const ast = ts.createSourceFile('dictionary.tsx', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
    let callback;
    function visit(node) {
        if (ts.isVariableDeclaration(node) && node.name.getText(ast) === name) {
            callback = node.initializer.arguments[0].getText(ast);
        }
        ts.forEachChild(node, visit);
    }
    visit(ast);
    assert.ok(callback, `Missing ${name}`);
    const output = ts.transpileModule(`module.exports = ${callback};`, {
        compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
    }).outputText;
    const loaded = { exports: {} };
    new Function(...Object.keys(context), 'module', output)(...Object.values(context), loaded);
    return loaded.exports;
}

test('Bot controls preserve their state, action handlers and counters alongside batched overview loading', () => {
    function statements(source, name) {
        const ast = ts.createSourceFile('controller.tsx', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
        const declaration = ast.statements.find(node => ts.isFunctionDeclaration(node) && node.name.text === name);
        const printer = ts.createPrinter();
        return declaration.body.statements.slice(0, -1)
            // The new batch endpoint and visible single-flight polling are
            // exercised with real hook callbacks in async-requests.cjs.
            .filter(node => !(ts.isVariableStatement(node)
                && node.declarationList.declarations.some(item => item.name.getText(ast) === 'loadBots')))
            .filter(node => !(ts.isExpressionStatement(node)
                && ts.isCallExpression(node.expression)
                && node.expression.expression.getText(ast) === 'useEffect'
                && node.getText(ast).includes('refreshSupplementaryData')))
            .map(node => printer.printNode(ts.EmitHint.Unspecified, node, ast));
    }
    for (const [page, hook] of [['BotsPage', 'bots/useBotsOverview']]) {
        const source = readFileSync(path.join(root, `src/features/${hook}.ts`), 'utf8');
        assert.deepEqual(statements(source, hook.split('/')[1]), statements(previousSource(page), page));
    }
});

test('Dashboard, bots and runtime keep their initial page markup after controller extraction', () => {
    const { MemoryRouter } = require('react-router-dom');
    const render = component => renderToStaticMarkup(React.createElement(MemoryRouter, null, React.createElement(component)));
    for (const page of ['DashboardPage', 'BotsPage', 'RuntimeDashboardPage']) {
        const prior = sourceModule(`src/pages/${page}.tsx`, previousSource(page)).default;
        assert.equal(render(current(`pages/${page}.tsx`).default), render(prior), page);
    }
});

test('Moved runtime/session rows and bot counters keep every status and busy/blocked presentation', () => {
    const priorRuntime = sourceModule('src/pages/RuntimeDashboardPage.tsx', previousSource('RuntimeDashboardPage') + '\nexport { RuntimeRow };');
    const priorBots = sourceModule('src/pages/BotsPage.tsx', previousSource('BotsPage') + '\nexport { BotDailyLimitCell, BotNegotiationStepsCell, BotStatus };');
    const runtime = current('features/runtime/RuntimeBotTable.tsx');
    const bots = current('features/bots/BotOverviewCells.tsx');
    const nowMs = Date.parse('2026-10-17T12:00:00Z');
    for (const status of ['IDLE', 'QUEUED', 'WORKING', 'COOLDOWN', 'ERROR']) {
        for (const blocked of [false, true]) for (const busy of [false, true]) {
            const bot = {
                botId: 7, name: 'Fixture', botStatus: 'RUNNING', runtimeStatus: status,
                lastRunFinishedAt: null, lastRunDurationMs: 1250, nextRunAt: '2026-10-17T12:02:00Z',
                workerSlot: 0, consecutiveFailures: 2, lastError: null,
                sessionBlockedSince: blocked ? '2026-10-17T11:55:00Z' : null,
                sessionBlockCount: 2, sessionPreviewRequested: busy,
            };
            sameView(priorRuntime.RuntimeRow, runtime.RuntimeRow, [{ bot, nowMs, previewUpdating: busy, onSessionPreview: noop }]);
            for (const botStatus of ['RUNNING', 'STOPPED', 'unknown']) {
                sameView(priorBots.BotStatus, bots.BotStatus, [{ status: botStatus, runtime: bot, nowMs }]);
            }
        }
    }
    for (const used of [0, 2, 8, -1]) {
        const activity = { dailyLimit: 5, dailyLimitUsed: used, dailyLimitRemaining: 5 - used, confirmedActionsToday: 1, ambiguousActionsToday: used, usedSlotsWithoutAuditYet: used, nextStepsInNegotiationsStartedToday: 2, nextStepsInOlderNegotiations: 3 };
        for (const name of ['BotDailyLimitCell', 'BotNegotiationStepsCell']) sameView(priorBots[name], bots[name], [{ activity }]);
    }
});

test('Extracted dashboard summaries preserve labels, amounts, filters and zero/empty calculations', () => {
    const prior = previousSource('DashboardPage');
    const start = prior.indexOf('    const activityStats:');
    const end = prior.indexOf('    return (\n        <section');
    const previous = sourceModule('src/pages/DashboardPage.tsx', prior + '\nexport function priorSummary(stats, period) {\n' + prior.slice(start, end) + '\nreturn { activityStats, periodStats, purchaseRate };\n}');
    const summary = current('features/dashboard/dashboardSummary.ts').dashboardSummary;
    const stats = { activeBotsCount: 2, negotiatingCount: 8, selectedHistoryCount: 11, purchasedCount: 3, skippedByUserCount: 2, legitCount: 1, scamCount: 2, totalSpent: 1234.56, totalNegotiatedSavings: 18.22, averagePurchasePrice: 150, averageDiscountPercentage: 8.32, averageSelectedPrice: null, medianSelectedPrice: 120 };
    for (const period of ['TODAY', 'LAST_7_DAYS', 'LAST_30_DAYS', 'THIS_MONTH', 'THIS_YEAR', 'ALL']) {
        for (const data of [null, stats, { ...stats, purchasedCount: 0, skippedByUserCount: 0 }]) {
            assert.deepEqual(summary(data, period), previous.priorSummary(data, period));
        }
    }
});

function reportFixture(kind) {
    const source = readFileSync(path.join(root, kind === 'dashboard'
        ? 'src/features/dashboard/useDashboardStats.ts' : 'src/features/statistics/useStatisticsReport.ts'), 'utf8');
    const requests = [];
    const state = { data: 'previous', loading: false, error: null };
    const context = {
        latestRequest: { current: 0 }, period: 'ALL', outcomes: [], assessments: [],
        modelIds: [7], source: 'ALL', granularity: 'DAY', from: '', to: '',
        setIsLoading: value => { state.loading = value; },
        setErrorMessage: value => { state.error = value; },
        setStats: value => { state.data = value; }, setOverview: value => { state.data = value; },
        getErrorMessage: current('shared/errorMessage.ts').getErrorMessage,
    };
    const api = (...args) => new Promise((resolve, reject) => { requests.push({ args, resolve, reject }); });
    context.getDashboardStats = api;
    context.getAnalyticsOverview = api;
    const loadName = kind === 'dashboard' ? 'loadStats' : 'load';
    return { source, loadName, context, state, requests,
        load: () => dictionaryCallback(source, loadName, context)(),
    };
}

test('Statistics/dashboard ignore older success, error and loading writes in both response orders', async () => {
    for (const kind of ['dashboard', 'statistics']) {
        for (const oldFails of [false, true]) for (const oldFirst of [false, true]) {
            const fixture = reportFixture(kind);
            const older = fixture.load();
            fixture.context.period = 'TODAY';
            const newer = fixture.load();
            const settleOlder = async () => {
                if (oldFails) fixture.requests[0].reject(new Error('older failure'));
                else fixture.requests[0].resolve('older');
                await older;
            };
            if (oldFirst) {
                await settleOlder();
                assert.deepEqual(fixture.state, { data: 'previous', loading: true, error: null });
            }
            fixture.requests[1].resolve('latest');
            await newer;
            if (!oldFirst) await settleOlder();
            assert.deepEqual(fixture.state, { data: 'latest', loading: false, error: null });
            assert.equal(kind === 'dashboard' ? fixture.requests[1].args[0] : fixture.requests[1].args[0].period, 'TODAY');
        }
    }
});

test('Latest failures keep each report\'s previous-data policy and unmount invalidates pending writes', async () => {
    for (const kind of ['dashboard', 'statistics']) {
        const fixture = reportFixture(kind);
        const latest = fixture.load();
        fixture.requests[0].reject(new Error('latest failure'));
        await latest;
        assert.deepEqual(fixture.state, { data: kind === 'dashboard' ? 'previous' : null, loading: false, error: 'latest failure' });
        const ast = ts.createSourceFile('report.ts', fixture.source, ts.ScriptTarget.Latest, true);
        let effect;
        function visit(node) {
            if (ts.isCallExpression(node) && node.expression.getText(ast) === 'useEffect') effect = node.arguments[0].getText(ast);
            ts.forEachChild(node, visit);
        }
        visit(ast);
        const context = { ...fixture.context, [fixture.loadName]: fixture.load };
        const output = ts.transpileModule(`return (${effect})();`, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
        const cleanup = new Function(...Object.keys(context), output)(...Object.values(context));
        cleanup();
        const before = { ...fixture.state };
        fixture.requests[1].resolve('too late');
        await Promise.resolve();
        await Promise.resolve();
        assert.deepEqual(fixture.state, before);
    }
});

const dictionaryHookSource = readFileSync(path.join(root, 'src/features/dictionaries/useDictionaryCatalog.ts'), 'utf8');
function dictionaryLoaderTrace(source, name, scenario, creation, currentLoader) {
    const writes = [];
    const state = { brand: scenario.brand ?? '2', category: scenario.category ?? '7' };
    const setter = key => value => {
        state[key] = typeof value === 'function' ? value(state[key]) : value;
        writes.push([key, state[key]]);
    };
    const context = {
        baseRequests: new (current('shared/useLatestRequest.ts').LatestRequest)(),
        modelRequests: new (current('shared/useLatestRequest.ts').LatestRequest)(),
        selectedBrand: { current: '2' },
        getErrorMessage: current('shared/errorMessage.ts').getErrorMessage,
        keepDictionarySelection: current('features/dictionaries/useDictionaryCatalog.ts').keepDictionarySelection,
        setIsLoading: setter('loading'), setErrorMessage: setter('error'),
        setBrands: setter('brands'), setCategories: setter('categories'), setModels: setter('models'),
        setSelectedBrandId: setter('brand'), setSelectedModelCategoryId: setter('category'),
        setIsModelsLoading: setter('modelLoading'), setAreModelsLoading: setter('modelLoading'),
        getBrands: async () => {
            writes.push(['request', 'brands']);
            if (scenario.failure === 'brands') throw new Error('Brand request failed');
            return scenario.brands;
        },
        getCategories: async () => {
            writes.push(['request', 'categories']);
            if (scenario.failure === 'categories') throw new Error('Category request failed');
            return scenario.categories;
        },
        getModelsByBrand: async brandId => {
            writes.push(['request', 'models', brandId]);
            if (scenario.failure === 'models') throw new Error('Model request failed');
            return scenario.models;
        },
    };
    if (currentLoader) {
        context.onCategoriesLoaded = creation
            ? dictionaryCallback(readFileSync(path.join(root, 'src/pages/DictionariesPage.tsx'), 'utf8'), 'selectCategory', context)
            : undefined;
    }
    return dictionaryCallback(source, name, context)(2).then(() => writes);
}

test('Shared dictionary loaders preserve requests, selection, Polish sorting and failure transitions', async () => {
    const brands = Object.freeze([{ id: 3, name: 'Żubr' }, { id: 2, name: 'Apple' }]);
    const categories = Object.freeze([{ id: 8, path: 'Żeglarstwo' }, { id: 7, path: 'Elektronika' }]);
    const models = Object.freeze([{ id: 5, name: 'Żółw' }, { id: 6, name: 'Telefon' }]);
    const scenarios = [
        { brands, categories, models },
        { brands, categories, models, brand: '3', category: '8' },
        { brands, categories, models, brand: '999', category: '999' },
        { brands: [], categories: [], models: [] },
        ...['brands', 'categories', 'models'].map(failure => ({ brands, categories, models, failure })),
    ];
    for (const page of ['DictionariesPage', 'ManageDictionariesPage']) {
        const source = execFileSync('git', ['show', `${baseline}:frontend/src/pages/${page}.tsx`], { encoding: 'utf8' });
        for (const name of ['loadBaseData', 'loadModels']) {
            for (const scenario of scenarios) {
                assert.deepEqual(
                    await dictionaryLoaderTrace(dictionaryHookSource, name, scenario, page === 'DictionariesPage', true),
                    await dictionaryLoaderTrace(source, name, scenario, page === 'DictionariesPage', false),
                    `${page}/${name}/${scenario.failure ?? scenario.brand ?? 'default'}`,
                );
            }
        }
    }
});

test('Model selection and filter pills preserve labels, selected states and disabled controls', () => {
    const models = [{ modelId: 7, brand: 'Apple', model: 'iPhone' }, { modelId: 8, brand: 'Samsung', model: 'Galaxy' }];
    sameView(oldStats.ModelPicker, current('features/statistics/ModelPicker.tsx').default, [
        { models: [], selectedIds: [], disabled: false, onChange: noop },
        { models, selectedIds: [8], disabled: true, onChange: noop },
    ]);
    const options = [{ value: 'A', label: 'Pierwszy' }, { value: 'B', label: 'Drugi' }];
    sameView(oldStats.FilterPills, current('features/statistics/FilterPills.tsx').default, [
        { title: 'Wynik', options, selected: [], disabled: false, onChange: noop },
        { title: 'Okres', options, selected: ['B'], disabled: true, allMode: false, onSingleChange: noop },
    ]);
});

const point = (date, price) => ({ date, label: date, medianMarketPrice: price, averageMarketPrice: price,
    averagePurchasePrice: price === null ? null : price - 5, medianPurchasePrice: price,
    marketPriceSampleCount: 2, purchasePriceSampleCount: 1, marketListingCount: 4 });
const points = [point('2026-10-01', 200), point('2026-10-02', null), point('2026-10-03', 180)];
test('Price charts preserve missing-data gaps, source restrictions and single-point rendering', () => {
    const next = current('features/statistics/PriceTimelineChart.tsx').default;
    sameView(oldStats.PriceTimelineChart, next, ['ALL', 'OBSERVER', 'HISTORY'].flatMap(source => [
        { points, selectedSeries: [], source },
        { points: [points[0]], selectedSeries: ['MARKET_MEDIAN', 'PURCHASE_AVERAGE'], source },
        { points: [], selectedSeries: [], source },
    ]));
});

test('Distribution charts and model tables preserve empty and populated output', () => {
    const next = current('features/statistics/AnalyticsDistributions.tsx');
    sameView(oldStats.VolumeChart, next.VolumeChart, [{ points: [] }, { points }]);
    sameView(oldStats.HistogramChart, next.HistogramChart, [{ buckets: [] }, { buckets: [
        { from: 100, to: 200, count: 3 }, { from: 200, to: 300, count: 0 },
    ] }]);
    const row = { modelId: 7, brand: 'Apple', model: 'iPhone', marketListingCount: 10, purchasedCount: 1,
        averageMarketPrice: 220, medianMarketPrice: 200, averageListingsPerDay: 2,
        averageListingsPerWeek: 14, averageListingsPerMonth: 60.88, averagePurchasePrice: 180,
        medianPurchasePrice: 180, legitRejectedCount: 2, scamCount: 1,
        purchaseBelowMarketMedianAmount: 20, purchaseBelowMarketMedianPercent: 10 };
    sameView(oldStats.ModelTable, current('features/statistics/ModelTable.tsx').default, [{ rows: [] }, { rows: [row] }]);
});

test('Price matrix preserves row controls, field order and baseline/partial coverage indicators', () => {
    const model = { id: 7, brandId: 1, name: 'iPhone', targetMode: 'VINTED_MODEL', proposedOfferPrice: 180,
        expectedResalePrice: 250, marketMinPrice: null, marketMaxPrice: 300 };
    const planning = { offersToday: 0, offersCurrentWeek: 4, todayWindowComplete: false,
        currentWeekWindowComplete: true, lastStatsUpdatedAt: '2026-10-01T12:00:00',
        lastSuccessfulScanAt: '2026-10-01T12:00:00', baselineOffers: 20, currentWindowFresh: false,
        lastScanComplete: false, previousFullWeekAvailable: false, trackedDays: 3, existingBots: 2,
        recommendedBots: 3, recommendationEstimated: true, recommendationWeeklyOffers: 30,
        dailyConversationCapacityPerBot: 5, weeklyConversationCapacityPerBot: 35 };
    const props = { brand: { id: 1, name: 'Apple' }, models: [model], drafts: {}, planningByModel: {},
        expanded: true, savingModelId: null, savedModelId: null, onToggle: noop, onDraftChange: noop, onSave: noop };
    sameView(oldPrices.BrandPriceSheet, current('features/price-matrix/BrandPriceSheet.tsx').default, [
        props, { ...props, expanded: false }, { ...props, models: [] },
        { ...props, planningByModel: { 7: planning }, savingModelId: 7 },
        { ...props, planningByModel: { 7: { ...planning, previousFullWeekAvailable: true, offersPreviousFullWeek: 25,
            recommendationEstimated: false, todayWindowComplete: true } }, savedModelId: 7 },
    ]);
});

test('Price parsing preserves blank/null, decimal comma, validation order and range rejection', () => {
    const { parsePriceDraft, emptyPriceDraft } = current('features/price-matrix/priceDraft.ts');
    for (const raw of ['', '  ', '12,5', '1.25', '-1', '0', 'Infinity', 'invalid']) {
        const previous = oldPrices.parseOptionalPositivePrice(raw, 'Proponowana cena dla bota musi być większa od 0.');
        const result = parsePriceDraft({ ...emptyPriceDraft(), proposedOfferPrice: raw });
        assert.equal(result.valid, previous.valid);
        if (previous.valid) assert.equal(result.value.proposedOfferPrice, previous.value);
        else assert.equal(result.errorMessage, previous.errorMessage);
    }
    assert.equal(parsePriceDraft({ ...emptyPriceDraft(), marketMinPrice: '20', marketMaxPrice: '10' }).valid, false);
    assert.match(parsePriceDraft({ ...emptyPriceDraft(), proposedOfferPrice: '-1', marketMaxPrice: '-1' }).errorMessage, /Proponowana/);
});

test('Shared target identity preserves explicit mode, search fallback and normalized category paths', () => {
    const { resolveTargetMode, categoryPathsEqual } = current('features/bots/form/targetIdentity.ts');
    assert.equal(resolveTargetMode({ targetMode: 'VINTED_MODEL', searchQuery: 'iPhone' }), 'VINTED_MODEL');
    assert.equal(resolveTargetMode({ targetMode: null, searchQuery: ' iPhone ' }), 'SEARCH_QUERY');
    assert.equal(resolveTargetMode({ targetMode: null, searchQuery: ' ' }), 'VINTED_MODEL');
    assert.equal(resolveTargetMode({ targetMode: null, searchQuery: null }), 'VINTED_MODEL');
    assert.equal(categoryPathsEqual([' Elektronika ', 'TELEFONY  I TABLETY'], ['elektronika', 'telefony i tablety']), true);
    assert.equal(categoryPathsEqual(['Telefony'], ['Telefony', 'Apple']), false);
    const { getErrorMessage } = current('shared/errorMessage.ts');
    assert.equal(getErrorMessage(new Error('powód'), 'fallback'), 'powód');
    assert.equal(getErrorMessage(null, 'fallback'), 'fallback');
});

function expandCss(filename) {
    return readFileSync(filename, 'utf8').replace(/^@import\s+"([^"]+)";\s*$/gm,
        (_, relative) => expandCss(path.resolve(path.dirname(filename), relative)));
}
function declarations(css) {
    const result = [];
    postcss.parse(css).walkDecls(declaration => {
        if (declaration.parent.type !== 'rule') return;
        const conditions = [];
        let parent = declaration.parent.parent;
        while (parent && parent.type !== 'root') {
            if (parent.type === 'atrule') conditions.unshift([parent.name, parent.params]);
            parent = parent.parent;
        }
        result.push(JSON.stringify([conditions, declaration.parent.selector.trim(), declaration.prop,
            declaration.value, Boolean(declaration.important)]));
    });
    return result;
}
for (const relative of ['src/index.css', 'src/styles/commercial-ui.css']) {
    test(`${relative}: imports preserve cascade order; removed declarations are dominated by identical later declarations`, () => {
        const before = declarations(execFileSync('git', ['show', `${baseline}:frontend/${relative}`], { encoding: 'utf8' }));
        const after = declarations(expandCss(path.join(root, relative)));
        let j = after.length - 1;
        const later = new Set();
        for (let i = before.length - 1; i >= 0; i--) {
            if (before[i] === after[j]) { later.add(before[i]); j--; }
            else assert.ok(later.has(before[i]), `Changed, reordered or unsupported removal: ${before[i]}`);
        }
        assert.equal(j, -1, 'Refactor introduced a declaration absent from the working baseline');
    });
}

test('Shared product defaults retain the working first step and rejection waits', () => {
    const previous = original('src/features/bots/create/hooks/useCreateBotForm.ts', ['createInitialForm', 'defaultRejectionPolicy']);
    const next = current('features/bots/form/formDefaults.ts');
    assert.deepEqual(next.createInitialForm(), previous.createInitialForm());
    for (const number of [1, 2, 3, 4, 25]) {
        const step = next.createNegotiationStep(number, number, number * 2);
        const rejection = previous.defaultRejectionPolicy(number);
        assert.equal(step.rejectionAction, rejection.action);
        assert.equal(step.rejectionWaitHours, rejection.waitHours);
        assert.deepEqual(step.counterOfferRules.map(rule => rule.id), [number * 2, number * 2 + 1]);
    }
});

test('Shared product editor preserves filter, strategy and negotiation controls', () => {
    const defaults = current('features/bots/form/formDefaults.ts');
    const form = defaults.createInitialForm();
    const previousFilters = original('src/features/bots/create/BotFiltersSection.tsx', []).default;
    const previousOffers = original('src/features/bots/create/OfferStrategySection.tsx', []).default;
    const previousSteps = original('src/features/bots/create/NegotiationStepsSection.tsx', []).default;
    const Next = current('features/bots/form/ProductConfigurationFields.tsx').default;
    const actionNames = ['setCategory', 'setBrand', 'setTargetMode', 'setModel', 'setSearchQuery', 'setMinPrice', 'setMaxPrice',
        'setAutoRaiseOfferToVintedMinimum', 'setMaxAutomaticOffer', 'addNegotiationStep', 'removeNegotiationStep',
        'updateNegotiationStep', 'updateNegotiationStepPolicy', 'addCounterOfferRule', 'removeCounterOfferRule', 'updateCounterOfferRule'];
    const controller = { form, ...Object.fromEntries(actionNames.map(name => [name, noop])) };
    for (const disabled of [false, true]) {
        const dictionaries = { categories: [], brands: [], models: [], isLoadingDictionaries: false,
            areModelsLoading: false, clearDictionaryError: noop };
        const before = React.createElement(React.Fragment, {},
            React.createElement(previousFilters, { ...dictionaries,
                selectedCategoryId: '', selectedBrandId: '', targetMode: 'VINTED_MODEL', selectedModelId: '', searchQuery: '',
                minPrice: '', maxPrice: '', targetFieldsDisabled: disabled,
                onCategoryChange: noop, onBrandChange: noop, onTargetModeChange: noop, onModelChange: noop,
                onSearchQueryChange: noop, onMinPriceChange: noop, onMaxPriceChange: noop }),
            React.createElement(previousOffers, { autoRaiseOfferToVintedMinimum: false, maxAutomaticOffer: '', firstConfiguredOffer: '',
                onAutoRaiseChange: noop, onMaxAutomaticOfferChange: noop }),
            React.createElement(previousSteps, { negotiationSteps: form.negotiationSteps, dailyNegotiationBudget: '25',
                onAddStep: noop, onRemoveStep: noop, onUpdateStep: noop, onUpdateStepPolicy: noop,
                onAddCounterOfferRule: noop, onRemoveCounterOfferRule: noop, onUpdateCounterOfferRule: noop }));
        assert.equal(markup(Next, { controller, dictionaries, targetFieldsDisabled: disabled, onError: noop }), renderToStaticMarkup(before));
    }
});
