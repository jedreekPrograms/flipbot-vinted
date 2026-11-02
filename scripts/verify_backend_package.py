"""Check the actual executable JAR, not just a directory before packaging."""
import hashlib
import json
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parents[1]
target = root / 'backend/target'
jar, = target.glob('backend-*.jar')
prefix = 'BOOT-INF/classes/static/'
expected = json.loads((target / 'classes/static/panel-build.json').read_text())
with zipfile.ZipFile(jar) as archive:
    actual = json.loads(archive.read(prefix + 'panel-build.json'))
    assert actual == expected, 'The packaged panel manifest differs from the current build'
    for name, sha in actual['assets'].items():
        assert hashlib.sha256(archive.read(prefix + name)).hexdigest() == sha, name
    paths = {name[len(prefix):] for name in archive.namelist() if name.startswith(prefix) and not name.endswith('/')}
    assert paths == set(actual['assets']) | {'panel-build.json'}, 'Stale/missing packaged panel files'
print(f'Verified {jar.name}: {len(actual["assets"])} panel assets, sources SHA-256 {actual["sourcesSha256"]}')
