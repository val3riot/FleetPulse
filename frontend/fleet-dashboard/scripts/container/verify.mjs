import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomUUID, createHash } from 'node:crypto';
import { parseArgs } from 'node:util';

const exec = promisify(execFile);
const directory = dirname(fileURLToPath(import.meta.url));
const frontend = resolve(directory, '../..');
const root = resolve(frontend, '../..');
const { values } = parseArgs({ options: { image: { type: 'string' } } });
const prefix = 'fleet-ui-test-' + randomUUID().slice(0, 8);
const image = values.image ?? prefix + ':local';
const network = prefix + '-network';
const containers = [];
let createdNetwork = false;
let building = false;
let cleaning;
let passed = 0;
async function docker(...args) {
  const result = await exec('docker', args, {
    cwd: root,
    timeout: args[0] === 'build' || args[0] === 'pull' ? 300000 : 30000,
    maxBuffer: 16 * 1024 * 1024,
  });
  return result.stdout.trim();
}
async function cleanup() {
  cleaning ??= (async () => {
    const failures = [];
    async function remove(args, absent) {
      try {
        await docker(...args);
      } catch (error) {
        if (!absent.test(error.stderr ?? '')) failures.push(args.slice(0, 3).join(' '));
      }
    }
    for (const name of [...containers].reverse())
      await remove(['rm', '-f', name], /No such container/);
    if (createdNetwork) await remove(['network', 'rm', network], /not found/);
    if (building) await remove(['image', 'rm', image], /No such image/);
    if (failures.length) {
      console.error('Cleanup incompleto:', failures.join(', '));
      process.exitCode = 1;
    }
  })();
  return cleaning;
}
for (const signal of ['SIGINT', 'SIGTERM'])
  process.once(signal, () => {
    void cleanup().finally(() => process.exit(signal === 'SIGINT' ? 130 : 143));
  });
async function check(name, operation) {
  await operation();
  passed++;
  console.log('PASS', name);
}
async function until(operation, label, timeout = 20000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    try {
      const result = await operation();
      if (result) return result;
    } catch {
      /* service may still be starting */
    }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  throw new Error('Timeout: ' + label);
}
async function response(base, path, options = {}) {
  const result = await fetch(base + path, { ...options, signal: AbortSignal.timeout(5000) });
  return { status: result.status, headers: result.headers, text: await result.text() };
}
async function info(name) {
  return JSON.parse(await docker('inspect', name))[0];
}
function own(name) {
  const value = prefix + '-' + name;
  containers.push(value);
  return value;
}
const runtimeFlags = [
  '--read-only',
  '--cap-drop',
  'ALL',
  '--security-opt',
  'no-new-privileges:true',
  '--tmpfs',
  '/tmp:rw,noexec,nosuid,size=32m,mode=1777',
];
let nodeImage;
async function upstream(name, alias, tag, ip) {
  const container = own(name);
  await docker(
    'run',
    '-d',
    '--name',
    container,
    '--network',
    network,
    '--network-alias',
    alias,
    ...(ip ? ['--ip', ip] : []),
    '--user',
    '1000:1000',
    ...runtimeFlags,
    '-e',
    'RESPONSE_TAG=' + tag,
    '-v',
    resolve(directory, 'upstream.mjs') + ':/mock/upstream.mjs:ro',
    nodeImage,
    'node',
    '/mock/upstream.mjs',
  );
  return container;
}
async function ui(name, origin) {
  const container = own(name);
  await docker(
    'run',
    '-d',
    '--name',
    container,
    '--network',
    network,
    ...runtimeFlags,
    '-p',
    '127.0.0.1::8080',
    '-e',
    'FLEET_API_UPSTREAM=' + origin,
    image,
  );
  const binding = (await info(container)).NetworkSettings.Ports['8080/tcp'][0];
  assert.equal(binding.HostIp, '127.0.0.1');
  const base = 'http://127.0.0.1:' + binding.HostPort;
  await until(
    async () => (await info(container)).State.Health.Status === 'healthy',
    'frontend healthy',
  );
  return { container, base };
}
try {
  if (!values.image) {
    building = true;
    console.log('Building isolated frontend image…');
    await docker('build', '-t', image, frontend);
  }
  nodeImage = /^ARG FRONTEND_NODE_IMAGE=(.+)$/m.exec(
    await readFile(resolve(frontend, 'Dockerfile'), 'utf8'),
  )[1];
  await docker('pull', nodeImage);
  createdNetwork = true;
  await docker('network', 'create', network);
  const primary = await ui('frontend', 'http://api:8080');
  const startedAt = (await info(primary.container)).State.StartedAt;
  await check('startup e health indipendenti dal backend assente', async () => {
    assert.equal((await response(primary.base, '/healthz')).text, 'ok\n');
    assert.equal((await response(primary.base, '/')).status, 200);
    assert.ok([502, 504].includes((await response(primary.base, '/api/v1/dashboard')).status));
    await docker('exec', primary.container, '/usr/local/bin/check-frontend');
  });
  const backend = await upstream('api', 'api', 'primary');
  await until(async () => {
    const result = await response(primary.base, '/api/v1/dashboard');
    return result.status === 200 && JSON.parse(result.text).tag === 'primary';
  }, 'cold backend discovery');
  const rootResponse = await response(primary.base, '/');
  const js = /<script[^>]*src="([^"]+\.js)"/.exec(rootResponse.text)[1];
  const css = /<link[^>]*href="([^"]+\.css)"/.exec(rootResponse.text)[1];
  const scriptHash = createHash('sha256')
    .update((await response(primary.base, '/' + js)).text)
    .digest('hex');
  await check('route SPA diretta e index senza cache persistente', async () => {
    const result = await response(
      primary.base,
      '/vehicles/97e194a8-64b3-4885-b1e6-25fd482f58c0/telemetry?from=2026-11-08',
    );
    assert.equal(result.status, 200);
    assert.equal(result.text, rootResponse.text);
    assert.equal(result.headers.get('cache-control'), 'no-store');
  });
  await check('asset reali con MIME corretto e cache immutable', async () => {
    for (const [path, mime] of [
      [js, /javascript/],
      [css, /text\/css/],
    ]) {
      const result = await response(primary.base, '/' + path);
      assert.equal(result.status, 200);
      assert.match(result.headers.get('content-type'), mime);
      assert.match(result.headers.get('cache-control'), /immutable/);
      assert.equal(result.headers.get('x-content-type-options'), 'nosniff');
    }
  });
  await check('asset assenti e file di sviluppo non ricevono fallback HTML', async () => {
    for (const path of [
      '/missing.js',
      '/missing.css',
      '/package.json',
      '/browser-stats.json',
      '/scripts/ui-fixture/server.mjs',
      '/main.js.map',
    ]) {
      const result = await response(primary.base, path);
      assert.equal(result.status, 404, path);
      assert.doesNotMatch(result.text, /<app-root/);
    }
  });
  await check('proxy GET conserva path e query codificata', async () => {
    const path = '/api/v1/vehicles?query=A%20B&page=0&sort=plate%2Casc';
    const result = await response(primary.base, path);
    assert.equal(result.status, 200);
    assert.equal(JSON.parse(result.text).url, path);
  });
  await check('proxy POST e PATCH conservano metodo e body', async () => {
    for (const method of ['POST', 'PATCH']) {
      const body = JSON.stringify({ status: 'CLOSED', description: 'Accenti à e €' });
      const result = await response(primary.base, '/api/v1/alerts/example', {
        method,
        body,
        headers: { 'Content-Type': 'application/json', 'X-Forwarded-For': '203.0.113.11' },
      });
      const data = JSON.parse(result.text);
      assert.equal(result.status, 200);
      assert.equal(data.body, body);
      assert.equal(data.method, method);
      assert.notEqual(data.forwardedFor, '203.0.113.11');
    }
  });
  await check('errori API preservati, senza index o falsi successi', async () => {
    const unavailable = await response(primary.base, '/api/v1/unavailable');
    assert.equal(unavailable.status, 503);
    assert.equal(JSON.parse(unavailable.text).code, 'SERVICE_UNAVAILABLE');
    const missing = await response(primary.base, '/api');
    assert.equal(missing.status, 404);
    assert.equal(JSON.parse(missing.text).code, 'REQUEST_INVALID');
  });
  await check('nessun retry proxy di una scrittura con connessione interrotta', async () => {
    const result = await response(primary.base, '/api/v1/abort', { method: 'POST', body: '{}' });
    assert.equal(result.status, 502);
    const counts = JSON.parse(
      await docker(
        'exec',
        backend,
        'node',
        '-e',
        'fetch("http://127.0.0.1:8080/counts").then(r=>r.text()).then(console.log)',
      ),
    );
    assert.equal(counts['/api/v1/abort'], 1);
  });
  await check('runtime non-root, filesystem read-only e sola distribuzione', async () => {
    const container = await info(primary.container);
    assert.equal(container.Config.User, '101:101');
    assert.equal(container.HostConfig.ReadonlyRootfs, true);
    assert.equal((await docker('exec', primary.container, 'id', '-u')).trim(), '101');
    const status = await docker('exec', primary.container, 'cat', '/proc/1/status');
    assert.ok(
      status
        .split('\n')
        .find((line) => line.startsWith('Uid:'))
        .split(/\s+/)
        .slice(1)
        .every((uid) => Number(uid) === 101),
    );
    for (const path of [
      '/workspace',
      '/usr/share/nginx/html/node_modules',
      '/usr/share/nginx/html/src',
      '/usr/share/nginx/html/browser-stats.json',
    ])
      await docker('exec', primary.container, 'test', '!', '-e', path);
    await assert.rejects(
      docker('exec', primary.container, 'touch', '/usr/share/nginx/html/write-probe'),
    );
  });
  await check('stessa immagine con destinazione API differente, JS invariato', async () => {
    await upstream('alternate-api', 'alternate-api', 'alternate');
    const alternate = await ui('alternate-frontend', 'http://alternate-api:8080');
    const result = await response(alternate.base, '/api/v1/dashboard');
    assert.equal(JSON.parse(result.text).tag, 'alternate');
    assert.equal((await info(primary.container)).Image, (await info(alternate.container)).Image);
    assert.equal(
      createHash('sha256')
        .update((await response(alternate.base, '/' + js)).text)
        .digest('hex'),
      scriptHash,
    );
  });
  await check('backend ricreato con IP diverso recuperato senza restart frontend', async () => {
    const originalIp = (await info(backend)).NetworkSettings.Networks[network].IPAddress;
    await docker('rm', '-f', backend);
    assert.ok([502, 504].includes((await response(primary.base, '/api/v1/dashboard')).status));
    await docker('exec', primary.container, '/usr/local/bin/check-frontend');
    // Reserve the previous address so the replacement cannot accidentally reuse it.
    await upstream('reserved-ip', 'reserved-ip', 'reserved', originalIp);
    const replacement = await upstream('replacement-api', 'api', 'recovered');
    assert.notEqual(
      (await info(replacement)).NetworkSettings.Networks[network].IPAddress,
      originalIp,
    );
    await until(async () => {
      const result = await response(primary.base, '/api/v1/dashboard');
      return result.status === 200 && JSON.parse(result.text).tag === 'recovered';
    }, 'DNS recovery');
    assert.equal((await info(primary.container)).State.StartedAt, startedAt);
  });
  await check('configurazioni runtime invalide rifiutate prima del server', async () => {
    for (const origin of [
      'https://api:8080',
      'http://api:0',
      'http://api:65536',
      'http://api:8080/path',
      'http://api:8080\npid /tmp/evil;',
    ]) {
      const name = own('invalid-' + containers.length);
      await assert.rejects(
        docker(
          'run',
          '--rm',
          '--name',
          name,
          '--network',
          'none',
          '-e',
          'FLEET_API_UPSTREAM=' + origin,
          image,
        ),
        (error) => /FLEET_API_UPSTREAM non valido/.test(error.stderr),
      );
    }
  });
  await check('healthcheck rifiuta un runtime senza index', async () => {
    const name = own('missing-index');
    await assert.rejects(
      docker(
        'run',
        '--rm',
        '--name',
        name,
        '--network',
        'none',
        '--tmpfs',
        '/usr/share/nginx/html',
        '--entrypoint',
        '/usr/local/bin/check-frontend',
        image,
      ),
    );
  });
  console.log(JSON.stringify({ result: 'passed', checks: passed, isolated: true }));
} catch (error) {
  console.error('Container verification failed:', error.message);
  if (error.stderr) console.error(error.stderr.slice(-2000));
  process.exitCode = 1;
} finally {
  await cleanup();
}
