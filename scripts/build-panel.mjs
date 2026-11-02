import { createHash } from 'node:crypto';
import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';

const scriptPath = fileURLToPath(import.meta.url);
const repository = path.resolve(path.dirname(scriptPath), '..');
const digest = bytes => createHash('sha256').update(bytes).digest('hex');

function files(directory, prefix = '') {
    return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
        const name = prefix + entry.name;
        return entry.isDirectory()
            ? files(path.join(directory, entry.name), name + '/')
            : [name];
    }).sort();
}

export function sourceFingerprint(frontend) {
    const inputs = readdirSync(frontend, { withFileTypes: true }).flatMap(entry => {
        if (entry.isDirectory()) {
            return ['src', 'public'].includes(entry.name)
                ? files(path.join(frontend, entry.name), entry.name + '/') : [];
        }
        return /\.(json|[cm]?[jt]s)$/.test(entry.name) || entry.name === 'index.html'
            || entry.name.startsWith('.env') ? [entry.name] : [];
    }).sort();
    const hash = createHash('sha256').update(readFileSync(scriptPath));
    for (const name of inputs) hash.update(name + '\0').update(readFileSync(path.join(frontend, name))).update('\0');
    return hash.digest('hex');
}

export function bundleMatches(directory, fingerprint) {
    try {
        const manifest = JSON.parse(readFileSync(path.join(directory, 'panel-build.json'), 'utf8'));
        return manifest.sourcesSha256 === fingerprint && Boolean(manifest.assets['index.html'])
            && files(directory).length === Object.keys(manifest.assets).length + 1
            && Object.entries(manifest.assets).every(([name, sha]) => digest(readFileSync(path.join(directory, name))) === sha);
    } catch {
        return false;
    }
}

export function publishBundle(dist, destination, fingerprint) {
    if (!existsSync(path.join(dist, 'index.html'))) throw new Error('Frontend build did not produce index.html.');
    const assets = Object.fromEntries(files(dist).map(name => [name, digest(readFileSync(path.join(dist, name)))]));
    rmSync(destination, { recursive: true, force: true });
    mkdirSync(destination, { recursive: true });
    cpSync(dist, destination, { recursive: true });
    writeFileSync(path.join(destination, 'panel-build.json'), JSON.stringify({ sourcesSha256: fingerprint, assets }, null, 2) + '\n');
}

export function buildPanel() {
    const frontend = path.join(repository, 'frontend');
    const destination = path.join(repository, 'backend/target/classes/static');
    const fingerprint = sourceFingerprint(frontend);
    if (bundleMatches(destination, fingerprint)) {
        console.log('Panel already matches the current frontend sources and asset hashes.');
        return;
    }
    for (const args of [['ci'], ['run', 'build']]) {
        const result = spawnSync('npm', args, { cwd: frontend, stdio: 'inherit', shell: process.platform === 'win32' });
        if (result.error) throw result.error;
        if (result.status !== 0) throw new Error(`npm ${args.join(' ')} failed (${result.status}).`);
    }
    if (sourceFingerprint(frontend) !== fingerprint) throw new Error('Frontend sources changed during the build. Run the build again.');
    publishBundle(path.join(frontend, 'dist'), destination, fingerprint);
    console.log(`Bundled current frontend into the backend (${fingerprint}).`);
}

if (process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url) buildPanel();
