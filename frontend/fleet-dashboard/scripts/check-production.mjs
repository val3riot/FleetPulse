import { readFile, readdir, stat } from 'node:fs/promises';
import { resolve, relative, sep } from 'node:path';
import { pathToFileURL } from 'node:url';

const forbiddenPath =
  /(?:^|\/)(?:testing|ui-fixture|fixtures)(?:\/|$)|\.(?:spec|test)\.[cm]?[jt]s$|(?:^|\/)(?:mock-api|api-check)\.[cm]?[jt]s$/i;
const forbiddenText = [
  'app-api-check',
  'Verifica Fleet API',
  'dev/api',
  'DIAGNOSTICA FIXTURE',
  'PROXY DIAGNOSTICS',
  'committed-timeout',
  'DEMO-001',
];

/** Validate the emitted build and its dependency graph, without executing the application. */
export async function checkProduction(buildDirectory) {
  const root = resolve(buildDirectory);
  const browser = resolve(root, 'browser');
  const metadata = JSON.parse(await readFile(resolve(root, 'browser-stats.json'), 'utf8'));
  if (!metadata.inputs || !metadata.outputs || !metadata.inputs['src/main.ts'])
    throw new Error('Metadati produzione mancanti o incompleti.');
  for (const source of Object.keys(metadata.inputs)) {
    const path = source.replaceAll('\\', '/');
    // The Angular replacement retains the original module name in the metafile.
    const devModule = /\/dev\//.test(path) && path !== 'src/app/dev/dev.routes.ts';
    if (forbiddenPath.test(path) || devModule)
      throw new Error(`Modulo di sviluppo nella build: ${source}`);
  }
  const replacement = metadata.inputs['src/app/dev/dev.routes.ts'];
  if (!replacement || replacement.imports?.some((item) => !item.external))
    throw new Error('La rotta diagnostica ha dipendenze applicative in produzione.');
  const filePath = (name) => {
    const path = resolve(browser, name);
    if (
      !relative(browser, path) ||
      relative(browser, path).startsWith('..' + sep) ||
      relative(browser, path) === '..'
    )
      throw new Error(`Percorso artefatto non valido: ${name}`);
    return path;
  };
  const emitted = Object.entries(metadata.outputs).filter(([, info]) => !info['ng-component']);
  const entry = emitted.find(([, info]) => info.entryPoint === 'src/main.ts');
  if (!entry || !entry[0].endsWith('.js'))
    throw new Error('Entrypoint applicativo JavaScript assente.');
  for (const [name, info] of emitted) {
    if (!(await stat(filePath(name))).isFile()) throw new Error(`Artefatto assente: ${name}`);
    for (const dependency of info.imports ?? []) {
      if (!dependency.external && !metadata.outputs[dependency.path])
        throw new Error(`Dipendenza non presente nei metadati: ${dependency.path}`);
    }
  }
  const html = await readFile(filePath('index.html'), 'utf8');
  if (!/<app-root(?:\s|>)/.test(html)) throw new Error('Host applicativo assente da index.html.');
  const references = Array.from(
    html.matchAll(/<(?:script|link)\b[^>]*\b(?:src|href)=["']([^"']+)["']/g),
    (match) => match[1],
  );
  const local = references.filter((value) => !/^(?:[a-z]+:|\/\/)/i.test(value));
  if (!local.includes(entry[0]) && !local.includes('/' + entry[0]))
    throw new Error('Entrypoint non collegato da index.html.');
  for (const ref of local) {
    const name = decodeURIComponent(ref.split(/[?#]/)[0]).replace(/^\//, '');
    if (!(await stat(filePath(name))).isFile()) throw new Error(`Risorsa HTML assente: ${ref}`);
  }
  let files = 0;
  async function scan(directory) {
    for (const item of await readdir(directory, { withFileTypes: true })) {
      const path = resolve(directory, item.name);
      const name = relative(browser, path).replaceAll('\\', '/');
      if (item.isSymbolicLink()) throw new Error(`Link simbolico nella distribuzione: ${name}`);
      if (
        forbiddenPath.test(name) ||
        /(?:^|\/)(?:dev|scripts)(?:\/|$)/.test(name) ||
        /(?:stats\.json|\.map)$/.test(name)
      )
        throw new Error(`Artefatto di sviluppo distribuito: ${name}`);
      if (item.isDirectory()) await scan(path);
      else {
        files++;
        if (/\.(?:[cm]?js|html|json|css)$/.test(name)) {
          const text = await readFile(path, 'utf8');
          const marker = forbiddenText.find((value) => text.includes(value));
          if (marker) throw new Error(`Contenuto di sviluppo distribuito: ${name} (${marker})`);
        }
      }
    }
  }
  await scan(browser);
  return {
    files,
    modules: Object.keys(metadata.inputs).length,
    chunks: emitted.filter(([name]) => name.endsWith('.js')).length,
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    console.log(
      'Produzione verificata:',
      await checkProduction(process.argv[2] ?? 'dist/fleet-dashboard'),
    );
  } catch (error) {
    console.error('Verifica produzione fallita:', error.message);
    process.exitCode = 1;
  }
}
