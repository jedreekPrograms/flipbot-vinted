// Run with: node src/test/js/ui-guard-scheduling.cjs (from playwright/).
// Exercise the actual injected scripts with a control that remains visible and
// mutates the document on click, as an asynchronous CMP/dialog can do.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const base = path.resolve(__dirname, '../../main/java/pl/flipbot/playwright');
const resources = path.resolve(__dirname, '../../main/resources/pl/flipbot/playwright');
// Parse delivered resource bytes; parsing raw Java text hid Java escape errors.
for (const file of fs.readdirSync(resources, { recursive: true })) {
  if (file.endsWith('.js')) new vm.Script(fs.readFileSync(path.join(resources, file), 'utf8'), { filename: file });
}
const sources = [
  ['browser/OneTrustConsentGuard.java', 'SCRIPT', 'consent'],
  ['browser/VintedInformationalDialogGuard.java', 'SCRIPT', 'dialog'],
  ['context/observer-model-labels.js', null, 'observer'],
];

function script(file, field) {
  if (field === null) return fs.readFileSync(path.join(resources, file), 'utf8');
  const source = fs.readFileSync(path.join(base, file), 'utf8');
  const declaration = source.indexOf('String ' + field);
  if (declaration < 0) throw new Error(`Missing script field ${field} in ${file}`);
  const start = source.indexOf('"""', declaration) + 3;
  const code = source.slice(start, source.indexOf('"""', start)).replaceAll('\\\\', '\\');
  return code.includes('__FLIPBOT_CONSENT_RESERVATION__')
    ? code.replace('__FLIPBOT_CONSENT_RESERVATION__', script('verification/CookieConsentClickBudget.java', 'SCRIPT'))
    : code;
}

function fixture(kind, embedded = false, host = 'www.vinted.pl') {
  let now = 0, clicks = 0, modelScans = 0;
  const timers = [], observers = [], intervals = [];
  class Element {
    innerText = kind === 'dialog' ? 'Rozumiem' : 'Zgoda na wszystkie';
    hasAttribute() { return false; }
    getAttribute() { return null; }
    getBoundingClientRect() { return { width: 100, height: 30 }; }
    querySelectorAll() { return []; }
    click() { clicks++; observers.forEach(callback => callback()); }
  }
  let button = new Element();
  const overlay = new Element();
  overlay.innerText = 'Weryfikacja elektroniki';
  overlay.querySelectorAll = () => [button];
  const window = { getComputedStyle: () => ({ display: 'block', visibility: 'visible', opacity: '1' }) };
  window.top = embedded ? {} : window;
  const document = {
    readyState: 'complete',
    getElementById: id => kind === 'consent' && id === 'onetrust-accept-btn-handler' ? button : null,
    querySelector: selector => ['consent', 'observer'].includes(kind) && selector === '#onetrust-accept-btn-handler' ? button : null,
    querySelectorAll: selector => {
      if (selector.includes('selectable-item-brand_collection-')) modelScans++;
      return kind === 'dialog' && selector.includes('electronics-verification') ? [overlay] : [];
    },
  };
  return {
    context: { window, document, location: { hostname: host }, Element, HTMLElement: Element,
      Date: { now: () => now },
      MutationObserver: class { constructor(callback) { observers.push(callback); } observe() {} },
      setTimeout: (callback, delay) => timers.push({ callback, due: now + delay }),
      setInterval: callback => intervals.push(callback), clearInterval() {},
    },
    mutate: () => observers.forEach(callback => callback()),
    replaceButton: () => { button = new Element(); observers.forEach(callback => callback()); },
    flush(time) {
      now = time;
      const due = timers.filter(timer => timer.due <= now);
      for (const timer of due) timers.splice(timers.indexOf(timer), 1);
      due.forEach(timer => timer.callback());
    },
    get clicks() { return clicks; },
    get modelScans() { return modelScans; },
    get pending() { return timers.length; },
    get observerCount() { return observers.length; },
  };
}

for (const [file, field, kind] of sources) {
  const code = script(file, field);
  const f = fixture(kind);
  vm.runInNewContext(code, f.context, { timeout: 1000 });
  for (let i = 0; i < 1000; i++) f.mutate();
  assert.equal(f.pending, 1, file + ': mutation burst schedules one deferred probe');
  f.flush(150);
  assert.equal(f.clicks, kind === 'observer' ? 0 : 1, file + ': only the owning guard clicks consent/dialog controls');
  if (kind === 'observer') assert.equal(f.modelScans, 1, file + ': model-label work still runs once for the burst');
  for (let i = 0; i < 1000; i++) f.mutate();
  f.flush(350);
  assert.equal(f.clicks, kind === 'observer' ? 0 : 1, file + ': asynchronous control is not immediately reclicked');
  assert.ok(f.pending <= 1, file + ': no unbounded observer queue');
  f.mutate(); f.flush(6500);
  assert.equal(f.clicks, kind === 'observer' ? 0 : 2, file + ': bounded retry remains with the owning guard');
  for (const blocked of [fixture(kind, true), fixture(kind, false, 'ads.example')]) {
    vm.runInNewContext(code, blocked.context, { timeout: 1000 });
    assert.equal(blocked.pending, 0);
    assert.equal(blocked.observerCount, 0);
  }
  console.log('PASS ' + file + ': coalescing, click cooldown, bounded retry, frame/host scope');
}

// Both scripts are installed in a real observer context. Their independent
// cooldowns used to allow two clicks within 150 ms on a closing CMP button.
const combined = fixture('consent');
vm.runInNewContext(script('browser/OneTrustConsentGuard.java', 'SCRIPT'), combined.context);
vm.runInNewContext(script('context/observer-model-labels.js', null), combined.context);
combined.flush(150);
assert.equal(combined.clicks, 1, 'combined guards: the same visible CMP control has one click owner');
for (let i = 0; i < 1000; i++) combined.mutate();
combined.flush(350);
assert.equal(combined.clicks, 1, 'combined guards: click mutations do not trigger another consent actor');
combined.mutate(); combined.flush(6500);
assert.equal(combined.clicks, 2, 'combined guards: shared consent guard can retry a persistent control');
console.log('PASS combined OneTrust/observer scripts: single consent click owner');

// A denied consent save leaves the control visible and may replace its DOM node.
// The finite document budget must survive both replacement and Java/JS races.
const denied = fixture('consent');
vm.runInNewContext(script('browser/OneTrustConsentGuard.java', 'SCRIPT'), denied.context);
const javaAction = () => vm.runInNewContext(`(() => {
  if ((${script('verification/CookieConsentClickBudget.java', 'SCRIPT')})()) {
    document.getElementById('onetrust-accept-btn-handler').click();
  }
})()`, denied.context);
javaAction();
denied.flush(150);
assert.equal(denied.clicks, 1, 'Java action and injected action share the same cooldown');
for (let time = 6000; time <= 120000; time += 6000) {
  denied.replaceButton();
  for (let i = 0; i < 1000; i++) denied.mutate();
  denied.flush(time);
  javaAction();
}
assert.equal(denied.clicks, 3, 'replaced buttons and continuing mutations cannot restart exhausted consent attempts');
vm.runInNewContext("document.getElementById('onetrust-accept-btn-handler').click()", denied.context);
denied.flush(126000);
assert.equal(denied.clicks, 4, 'manual consent action remains possible after the automation budget is exhausted');
const anotherDocument = fixture('consent');
vm.runInNewContext(script('browser/OneTrustConsentGuard.java', 'SCRIPT'), anotherDocument.context);
anotherDocument.flush(150);
assert.equal(anotherDocument.clicks, 1, 'a new document has its own consent-action budget');
console.log('PASS denied consent save: shared Java/JS budget, replaced nodes, finite retries, manual action and new document');
