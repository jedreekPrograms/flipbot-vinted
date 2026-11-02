import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync, existsSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { bundleMatches, publishBundle, sourceFingerprint } from '../build-panel.mjs';

test('Bundle cache detects source, configuration, lockfile and asset changes', () => {
    const root = mkdtempSync(path.join(os.tmpdir(), 'flipbot panel-'));
    try {
        const frontend = path.join(root, 'frontend');
        const dist = path.join(frontend, 'dist');
        const destination = path.join(root, 'backend/target/classes/static');
        for (const directory of ['src', 'public', 'dist/assets', 'node_modules']) mkdirSync(path.join(frontend, directory), { recursive: true });
        const inputs = { 'src/App.tsx': 'current UI', 'public/icon.svg': '<svg/>', 'package-lock.json': '{}', 'vite.config.ts': 'export default {}', 'index.html': '<html/>', '.env.local': 'VITE_EXAMPLE=local' };
        for (const [name, content] of Object.entries(inputs)) writeFileSync(path.join(frontend, name), content);
        writeFileSync(path.join(dist, 'index.html'), '<script src="/assets/current.js"></script>');
        writeFileSync(path.join(dist, 'assets/current.js'), 'current code');
        const fingerprint = sourceFingerprint(frontend);
        publishBundle(dist, destination, fingerprint);
        assert.equal(bundleMatches(destination, fingerprint), true);
        writeFileSync(path.join(frontend, 'node_modules/cache'), 'not a build input');
        assert.equal(sourceFingerprint(frontend), fingerprint);
        for (const [name, content] of Object.entries(inputs)) {
            writeFileSync(path.join(frontend, name), content + ' changed');
            assert.notEqual(sourceFingerprint(frontend), fingerprint, name);
            writeFileSync(path.join(frontend, name), content);
        }
        writeFileSync(path.join(destination, 'assets/current.js'), 'stale/tampered code');
        assert.equal(bundleMatches(destination, fingerprint), false);
        publishBundle(dist, destination, fingerprint);
        rmSync(path.join(destination, 'assets/current.js'));
        assert.equal(bundleMatches(destination, fingerprint), false);
        writeFileSync(path.join(destination, 'obsolete.js'), 'old code');
        assert.equal(bundleMatches(destination, fingerprint), false);
        publishBundle(dist, destination, fingerprint);
        assert.equal(existsSync(path.join(destination, 'obsolete.js')), false);
        assert.equal(bundleMatches(destination, fingerprint), true);
        assert.equal(JSON.parse(readFileSync(path.join(destination, 'panel-build.json'))).assets['index.html'].length, 64);
    } finally {
        rmSync(root, { recursive: true, force: true });
    }
});
