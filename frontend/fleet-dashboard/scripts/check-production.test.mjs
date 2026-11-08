import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rm, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { checkProduction } from './check-production.mjs';

async function build(t) {
  const root = await mkdtemp(join(tmpdir(), 'fleet-production-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  await mkdir(join(root, 'browser'));
  const metadata = {
    inputs: {
      'src/main.ts': { imports: [] },
      'src/app/dev/dev.routes.ts': { imports: [{ path: '@angular/router', external: true }] },
    },
    outputs: {
      'main-HASH.js': {
        entryPoint: 'src/main.ts',
        imports: [{ path: 'chunk-HASH.js', kind: 'dynamic-import' }],
      },
      'chunk-HASH.js': { imports: [] },
      'styles-HASH.css': { imports: [] },
      'component-HASH.css': { 'ng-component': true },
    },
  };
  await writeFile(join(root, 'browser-stats.json'), JSON.stringify(metadata));
  await writeFile(
    join(root, 'browser/index.html'),
    '<app-root></app-root><script src="main-HASH.js"></script><link rel="stylesheet" href="styles-HASH.css">',
  );
  await writeFile(join(root, 'browser/main-HASH.js'), 'import("./chunk-HASH.js")');
  await writeFile(join(root, 'browser/chunk-HASH.js'), 'console.log("FleetPulse")');
  await writeFile(join(root, 'browser/styles-HASH.css'), 'body { margin: 0; }');
  return root;
}
async function changeMetadata(root, change) {
  const path = join(root, 'browser-stats.json');
  const metadata = JSON.parse(await readFile(path));
  change(metadata);
  await writeFile(path, JSON.stringify(metadata));
}

test('una distribuzione completa passa senza richiedere CSS componenti incorporato nei JS', async (t) => {
  assert.deepEqual(await checkProduction(await build(t)), { files: 4, modules: 2, chunks: 2 });
});
for (const module of [
  'src/app/dev/api-check.ts',
  'src/app/testing/fleet-api.stub.ts',
  'scripts/ui-fixture/server.mjs',
  'src/app/example.spec.ts',
]) {
  test(`rifiuta il modulo ${module} anche senza marker testuali nel bundle`, async (t) => {
    const root = await build(t);
    await changeMetadata(root, (metadata) => {
      metadata.inputs[module] = { imports: [] };
    });
    await assert.rejects(checkProduction(root), /Modulo di sviluppo/);
  });
}
test('rifiuta la rotta dev con import interno, tenendo conto dei nomi originali del replacement', async (t) => {
  const root = await build(t);
  await changeMetadata(root, (metadata) => {
    metadata.inputs['src/app/dev/dev.routes.ts'].imports.push({ path: 'src/app/dev/api-check.ts' });
  });
  await assert.rejects(checkProduction(root), /rotta diagnostica/);
});
for (const [name, text] of [
  ['debug.js', 'app-api-check'],
  ['data.json', 'DIAGNOSTICA FIXTURE'],
  ['mock-api.mjs', '{}'],
  ['bundle.js.map', '{}'],
  ['stats.json', '{}'],
]) {
  test(`rifiuta l'asset non tracciato ${name}`, async (t) => {
    const root = await build(t);
    await writeFile(join(root, 'browser', name), text);
    await assert.rejects(checkProduction(root), /sviluppo/);
  });
}
test('rifiuta la distribuzione se manca un chunk lazy', async (t) => {
  const root = await build(t);
  await rm(join(root, 'browser/chunk-HASH.js'));
  await assert.rejects(checkProduction(root), /ENOENT/);
});
test('rifiuta metadati mancanti o incompleti', async (t) => {
  const root = await build(t);
  await writeFile(join(root, 'browser-stats.json'), '{}');
  await assert.rejects(checkProduction(root), /Metadati/);
});
test('rifiuta index senza collegamento al vero entrypoint', async (t) => {
  const root = await build(t);
  await writeFile(join(root, 'browser/index.html'), '<app-root></app-root>');
  await assert.rejects(checkProduction(root), /Entrypoint non collegato/);
});
test('rifiuta un riferimento HTML a una risorsa assente', async (t) => {
  const root = await build(t);
  await writeFile(
    join(root, 'browser/index.html'),
    '<app-root></app-root><script src="main-HASH.js"></script><link href="missing.css">',
  );
  await assert.rejects(checkProduction(root), /ENOENT/);
});
test('rifiuta riferimenti che escono dalla directory di distribuzione', async (t) => {
  const root = await build(t);
  await changeMetadata(root, (metadata) => {
    metadata.outputs['../outside.js'] = {};
  });
  await assert.rejects(checkProduction(root), /Percorso artefatto/);
});
