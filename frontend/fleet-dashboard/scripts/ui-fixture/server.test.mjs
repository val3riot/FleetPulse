import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createUiFixture } from './server.mjs';
import { ALERT_ID, VEHICLE_ID } from './data.mjs';

async function setup(t, options) {
  const fixture = createUiFixture(options);
  const base = await fixture.listen(0);
  t.after(() => fixture.close());
  async function request(path, method = 'GET', body, signal) {
    return fetch(base + '/api/v1' + path, {
      method,
      body: body === undefined ? undefined : JSON.stringify(body),
      headers: { 'Content-Type': 'application/json' },
      signal,
    });
  }
  return { request };
}
const vehicleBody = {
  externalCode: 'NUOVO',
  plate: 'NUOVA',
  serviceIntervalKm: 15000,
  nextServiceAtKm: 100000,
};
const alertPath = '/alerts/' + ALERT_ID;
const vehiclePath = '/vehicles/' + VEHICLE_ID;

test('tutte le letture usate dalla UI rispondono con dati coerenti e timestamp deterministici', async (t) => {
  const { request } = await setup(t);
  const dashboard = await (await request('/dashboard')).json();
  assert.equal(dashboard.totalVehicles, 3);
  assert.equal(dashboard.openAlerts, 1);
  assert.equal(dashboard.relevantAlerts.length, 2);
  const vehicles = await (await request('/vehicles')).json();
  assert.equal(vehicles.content[0].externalCode, 'DEMO-003');
  for (const path of [
    '/vehicles',
    vehiclePath,
    vehiclePath + '/state',
    vehiclePath + '/telemetry?from=2026-11-08T00:00:00Z&to=2026-11-09T00:00:00Z',
    vehiclePath + '/alerts',
    '/alerts',
    alertPath,
  ]) {
    const response = await request(path);
    assert.equal(response.status, 200, path);
    assert.equal(response.headers.get('x-fleetpulse-fixture'), 'ui');
    assert.equal(response.headers.get('cache-control'), 'no-store');
  }
});
test('filtri e pagine non riordinano i dati in modo casuale; data scoped e globale coerenti', async (t) => {
  const { request } = await setup(t);
  const page = await (
    await request(
      '/alerts?status=OPEN&type=ENGINE_TEMPERATURE_HIGH&severity=HIGH&vehicleId=' + VEHICLE_ID,
    )
  ).json();
  assert.equal(page.totalElements, 1);
  assert.equal(page.content[0].id, ALERT_ID);
  const first = await (await request('/vehicles?page=0&size=2&sort=externalCode,asc')).json();
  const second = await (await request('/vehicles?page=1&size=2&sort=externalCode,asc')).json();
  assert.equal(first.totalPages, 2);
  assert.equal(first.content[0].externalCode, 'DEMO-001');
  assert.equal(second.content.length, 1);
  assert.equal(second.last, true);
  const missing = await (
    await request('/alerts?vehicleId=00000000-0000-0000-0000-000000000000')
  ).json();
  assert.equal(missing.totalElements, 0);
  assert.equal(
    (await request('/vehicles/00000000-0000-0000-0000-000000000000/alerts')).status,
    404,
  );
});
test('sequenza e dati sono isolati per istanza; ogni tentativo al target consuma un passo', async (t) => {
  const first = await setup(t, { sequence: ['success', 'unavailable', 'unavailable', 'success'] });
  const original = await (await first.request('/dashboard')).json();
  await first.request('/vehicles'); // The context must not consume the target sequence.
  assert.equal((await first.request('/dashboard')).status, 503);
  assert.equal((await first.request('/dashboard')).status, 503);
  assert.deepEqual(await (await first.request('/dashboard')).json(), original);
  const second = await setup(t, { sequence: ['success', 'unavailable', 'unavailable', 'success'] });
  assert.equal((await second.request('/dashboard')).status, 200);
  await first.request(alertPath, 'PATCH', { status: 'CLOSED' });
  assert.equal((await (await second.request(alertPath)).json()).status, 'OPEN');
});
for (const [scenario, status, code] of [
  ['unavailable', 503, 'SERVICE_UNAVAILABLE'],
  ['internal', 500, 'INTERNAL_ERROR'],
  ['unknown', 503, 'FUTURE_ERROR'],
]) {
  test(`scenario ${scenario} preserva il codice HTTP e il body`, async (t) => {
    const { request } = await setup(t, { sequence: [scenario] });
    const response = await request('/dashboard');
    assert.equal(response.status, status);
    assert.equal((await response.json()).code, code);
  });
}
test('proxy HTML è una risposta HTTP 502, non un falso errore di rete status0', async (t) => {
  const { request } = await setup(t, { sequence: ['html'] });
  const response = await request('/dashboard');
  assert.equal(response.status, 502);
  assert.match(response.headers.get('content-type'), /text\/html/);
  assert.match(await response.text(), /PROXY DIAGNOSTICS/);
});
for (const target of ['dashboard', 'vehicles', 'alerts', 'telemetry', 'state']) {
  test(`vuoto valido per ${target} senza cancellare i dati del contesto`, async (t) => {
    const { request } = await setup(t, { target, sequence: ['empty'] });
    const paths = {
      dashboard: '/dashboard',
      vehicles: '/vehicles',
      alerts: '/alerts',
      state: vehiclePath + '/state',
      telemetry: vehiclePath + '/telemetry?from=2026-11-08T00:00:00Z&to=2026-11-09T00:00:00Z',
    };
    const response = await request(paths[target]);
    const body = await response.json();
    if (target === 'state') {
      assert.equal(response.status, 404);
      assert.equal(body.code, 'VEHICLE_STATE_NOT_AVAILABLE');
    } else {
      assert.equal(response.status, 200);
      assert.equal(target === 'dashboard' ? body.totalVehicles : body.totalElements, 0);
    }
    assert.equal((await request(vehiclePath)).status, 200);
  });
}
test('creazione, stato veicolo e macchina stati alert sono persistiti solo nella singola fixture', async (t) => {
  const { request } = await setup(t);
  const createdResponse = await request('/vehicles', 'POST', vehicleBody);
  assert.equal(createdResponse.status, 201);
  const created = await createdResponse.json();
  assert.equal((await (await request('/vehicles/' + created.id)).json()).externalCode, 'NUOVO');
  assert.equal((await request('/vehicles', 'POST', vehicleBody)).status, 409);
  assert.equal(
    (await (await request(vehiclePath + '/status', 'PATCH', { status: 'DISABLED' })).json()).status,
    'DISABLED',
  );
  const acknowledged = await (await request(alertPath, 'PATCH', { status: 'ACKNOWLEDGED' })).json();
  assert.ok(acknowledged.acknowledgedAt);
  const closed = await (await request(alertPath, 'PATCH', { status: 'CLOSED' })).json();
  assert.equal(closed.acknowledgedAt, acknowledged.acknowledgedAt);
  assert.ok(closed.closedAt);
  assert.deepEqual(await (await request(alertPath, 'PATCH', { status: 'CLOSED' })).json(), closed);
  assert.equal((await request(alertPath, 'PATCH', { status: 'ACKNOWLEDGED' })).status, 409);
});
test('conflitto simulato riflette il cambiamento concorrente nella successiva GET', async (t) => {
  const { request } = await setup(t, { target: 'alert-status', sequence: ['conflict'] });
  assert.equal((await request(alertPath, 'PATCH', { status: 'ACKNOWLEDGED' })).status, 409);
  assert.equal((await (await request(alertPath)).json()).status, 'CLOSED');
});
test('lettura lenta mantiene lo stesso contratto e non cambia i dati', async (t) => {
  const { request } = await setup(t, { sequence: ['slow'], delayMs: 1 });
  assert.equal((await request('/dashboard')).status, 200);
});
for (const target of ['create-vehicle', 'vehicle-status', 'alert-status']) {
  test(`committed-timeout ${target}: verifica successiva vede la scrittura anche senza risposta`, async (t) => {
    let received;
    const accepted = new Promise((resolve) => {
      received = resolve;
    });
    const { request } = await setup(t, {
      target,
      sequence: ['committed-timeout'],
      onRequest: ({ route }) => {
        if (route === target) received();
      },
    });
    const controller = new AbortController();
    const pending =
      target === 'create-vehicle'
        ? request('/vehicles', 'POST', vehicleBody, controller.signal)
        : target === 'vehicle-status'
          ? request(vehiclePath + '/status', 'PATCH', { status: 'DISABLED' }, controller.signal)
          : request(alertPath, 'PATCH', { status: 'CLOSED' }, controller.signal);
    const failed = assert.rejects(pending, { name: 'AbortError' });
    await accepted;
    controller.abort();
    await failed;
    if (target === 'create-vehicle') {
      const list = await (await request('/vehicles?query=NUOVO')).json();
      assert.equal(list.totalElements, 1);
    } else if (target === 'vehicle-status')
      assert.equal((await (await request(vehiclePath)).json()).status, 'DISABLED');
    else assert.equal((await (await request(alertPath)).json()).status, 'CLOSED');
  });
}
test('timeout senza commit si annulla e non modifica i dati', async (t) => {
  let received;
  const accepted = new Promise((resolve) => {
    received = resolve;
  });
  const { request } = await setup(t, {
    target: 'alert-status',
    sequence: ['timeout'],
    onRequest: ({ route }) => {
      if (route === 'alert-status') received();
    },
  });
  const controller = new AbortController();
  const pending = request(alertPath, 'PATCH', { status: 'CLOSED' }, controller.signal);
  const failed = assert.rejects(pending, { name: 'AbortError' });
  await accepted;
  controller.abort();
  await failed;
  assert.equal((await (await request(alertPath)).json()).status, 'OPEN');
});
test('path non supportati non vengono inoltrati e input di controllo invalido è rifiutato', async (t) => {
  const { request } = await setup(t);
  assert.equal((await request('/unknown')).status, 404);
  assert.equal((await request('/vehicles/invalid')).status, 400);
  assert.equal((await request('/alerts?size=2&size=3')).status, 400);
  assert.equal((await request('/vehicles?page=-1')).status, 400);
  assert.equal((await request('/vehicles', 'POST', {})).status, 400);
  assert.equal(
    (await request(vehiclePath + '/telemetry?from=2026-11-09T00:00:00Z&to=2026-11-08T00:00:00Z'))
      .status,
    400,
  );
});
test('opzioni di scenario non valide falliscono prima di aprire un server', () => {
  assert.throws(() => createUiFixture({ target: 'unknown' }));
  assert.throws(() => createUiFixture({ sequence: ['network'] }));
  assert.throws(() => createUiFixture({ target: 'alert', sequence: ['empty'] }));
  assert.throws(() => createUiFixture({ sequence: ['committed-timeout'] }));
  assert.throws(() => createUiFixture({ delayMs: -1 }));
});

test('gli estremi dello storico sono inclusivi senza perdere microsecondi', async (t) => {
  const { request } = await setup(t);
  const range =
    vehiclePath + '/telemetry?from=2026-11-08T12:00:00.123456Z&to=2026-11-08T12:00:00.123456Z';
  const exact = await (await request(range)).json();
  assert.equal(exact.totalElements, 1);
  const outside = await (
    await request(
      range
        .replace('from=2026-11-08T12:00:00.123456Z', 'from=2026-11-08T12:00:00.123457Z')
        .replace('to=2026-11-08T12:00:00.123456Z', 'to=2026-11-08T12:00:00.123458Z'),
    )
  ).json();
  assert.equal(outside.totalElements, 0);
});

for (const [target, path, code] of [
  ['vehicle', vehiclePath, 'VEHICLE_NOT_FOUND'],
  ['alert', alertPath, 'ALERT_NOT_FOUND'],
]) {
  test(`assenza simulata ${target} è distinta dal vuoto e recupera senza cancellare i dati`, async (t) => {
    const { request } = await setup(t, { target, sequence: ['not-found', 'success'] });
    const missing = await request(path);
    assert.equal(missing.status, 404);
    assert.equal((await missing.json()).code, code);
    assert.equal((await request(path)).status, 200);
  });
}
