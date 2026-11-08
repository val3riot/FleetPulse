import { createServer } from 'node:http';
import { fixtureData, INSTANT } from './data.mjs';

export const TARGETS = [
  'dashboard',
  'vehicles',
  'vehicle',
  'create-vehicle',
  'vehicle-status',
  'state',
  'telemetry',
  'vehicle-alerts',
  'alerts',
  'alert',
  'alert-status',
];
export const SCENARIOS = [
  'success',
  'slow',
  'empty',
  'unavailable',
  'internal',
  'unknown',
  'html',
  'timeout',
  'committed-timeout',
  'not-found',
  'conflict',
];
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const failure = (status, code, details = []) => ({
  status,
  body: {
    timestamp: INSTANT,
    status,
    code,
    message: 'DIAGNOSTICA FIXTURE: non mostrare come messaggio principale',
    details,
  },
});
const ok = (body, status = 200) => ({ status, body });

function targetFor(method, path) {
  if (method === 'GET' && path === '/dashboard') return 'dashboard';
  if (path === '/vehicles')
    return method === 'GET' ? 'vehicles' : method === 'POST' ? 'create-vehicle' : null;
  if (path === '/alerts' && method === 'GET') return 'alerts';
  if (/^\/vehicles\/[^/]+$/.test(path) && method === 'GET') return 'vehicle';
  if (/^\/vehicles\/[^/]+\/status$/.test(path) && method === 'PATCH') return 'vehicle-status';
  if (/^\/vehicles\/[^/]+\/(state|telemetry|alerts)$/.test(path) && method === 'GET')
    return { state: 'state', telemetry: 'telemetry', alerts: 'vehicle-alerts' }[
      path.split('/').at(-1)
    ];
  if (/^\/alerts\/[^/]+$/.test(path))
    return method === 'GET' ? 'alert' : method === 'PATCH' ? 'alert-status' : null;
  return null;
}

function paginated(
  items,
  params,
  defaultSort,
  sizeDefault = 50,
  allowed = [defaultSort.split(',')[0]],
) {
  const page = Number(params.get('page') ?? 0);
  const size = Number(params.get('size') ?? sizeDefault);
  const sort = params.get('sort') ?? defaultSort;
  const [field, direction] = sort.split(',');
  if (
    !Number.isInteger(page) ||
    page < 0 ||
    page > 2147483647 ||
    !Number.isInteger(size) ||
    size < 1 ||
    size > 100 ||
    !allowed.includes(field) ||
    !['asc', 'desc'].includes(direction)
  )
    return failure(400, 'REQUEST_INVALID');
  const ordered = [...items].sort((a, b) => {
    const result =
      String(a[field]).localeCompare(String(b[field])) || String(a.id).localeCompare(String(b.id));
    return direction === 'asc' ? result : -result;
  });
  return ok({
    content: ordered.slice(page * size, (page + 1) * size),
    page,
    size,
    totalElements: items.length,
    totalPages: Math.ceil(items.length / size),
    first: page === 0,
    last: page + 1 >= Math.ceil(items.length / size),
  });
}

function instant(value) {
  // Date.parse preserves milliseconds; add the remaining six fractional digits.
  const fraction = /\.(\d+)/.exec(value)?.[1] ?? '';
  return BigInt(Date.parse(value)) * 1000000n + BigInt(fraction.padEnd(9, '0').slice(3, 9));
}
function inRange(value, params) {
  const time = instant(value);
  return (
    (!params.has('from') || time >= instant(params.get('from'))) &&
    (!params.has('to') || time <= instant(params.get('to')))
  );
}

/** Local-only, never a proxy: no outbound HTTP, real API credentials or database. */
export function createUiFixture({
  target = 'dashboard',
  sequence = ['success'],
  delayMs = 2000,
  timeoutMs = 30000,
  onRequest = () => {},
} = {}) {
  if (
    !TARGETS.includes(target) ||
    !sequence.length ||
    sequence.some((step) => !SCENARIOS.includes(step))
  )
    throw new Error('Target o sequenza non supportati.');
  if (
    sequence.includes('empty') &&
    !['dashboard', 'vehicles', 'state', 'telemetry', 'vehicle-alerts', 'alerts'].includes(target)
  )
    throw new Error('empty richiede una collection, dashboard o state.');
  if (
    sequence.includes('committed-timeout') &&
    !['create-vehicle', 'vehicle-status', 'alert-status'].includes(target)
  )
    throw new Error('committed-timeout richiede una scrittura.');
  if (sequence.includes('conflict') && !['create-vehicle', 'alert-status'].includes(target))
    throw new Error('conflict supportato soltanto per creazione veicolo o stato alert.');
  if (
    ![delayMs, timeoutMs].every(
      (value) => Number.isSafeInteger(value) && value > 0 && value <= 2147483647,
    )
  )
    throw new Error('Durata non valida.');
  if (
    sequence.includes('not-found') &&
    ![
      'vehicle',
      'vehicle-status',
      'state',
      'telemetry',
      'vehicle-alerts',
      'alert',
      'alert-status',
    ].includes(target)
  )
    throw new Error('not-found richiede una risorsa identificata.');
  const data = fixtureData();
  let matched = 0;
  let nextId = 1;
  const timers = new Set();
  function normal(route, params, id, body, empty = false) {
    if (route === 'dashboard') {
      const vehicles = empty ? [] : data.vehicles;
      const alerts = empty ? [] : data.alerts;
      return ok({
        totalVehicles: vehicles.length,
        vehiclesByStatus: {
          ACTIVE: vehicles.filter((item) => item.status === 'ACTIVE').length,
          DISABLED: vehicles.filter((item) => item.status === 'DISABLED').length,
        },
        recentlyReportingVehicles: empty ? 0 : 1,
        openAlerts: alerts.filter((item) => item.status === 'OPEN').length,
        relevantAlerts: alerts
          .filter((item) => item.status !== 'CLOSED')
          .map(({ id, vehicleId, type, severity, status, description, createdAt }) => ({
            id,
            vehicleId,
            type,
            severity,
            status,
            description,
            createdAt,
          })),
      });
    }
    if (route === 'vehicles') {
      const query = (params.get('query') ?? '').toLowerCase();
      const vehicles = empty
        ? []
        : data.vehicles.filter(
            (item) =>
              (!params.has('status') || item.status === params.get('status')) &&
              (!query || `${item.externalCode} ${item.plate}`.toLowerCase().includes(query)),
          );
      return paginated(vehicles, params, 'createdAt,desc', 20, [
        'createdAt',
        'externalCode',
        'plate',
        'status',
      ]);
    }
    if (route === 'create-vehicle') {
      if (
        !body ||
        typeof body.externalCode !== 'string' ||
        !body.externalCode.trim() ||
        body.externalCode.length > 64 ||
        typeof body.plate !== 'string' ||
        !body.plate.trim() ||
        body.plate.length > 16 ||
        !Number.isInteger(body.serviceIntervalKm) ||
        body.serviceIntervalKm < 1 ||
        body.serviceIntervalKm > 2147483647 ||
        !Number.isSafeInteger(body.nextServiceAtKm) ||
        body.nextServiceAtKm < 0
      )
        return failure(400, 'REQUEST_INVALID');
      if (data.vehicles.some((item) => item.plate === body.plate))
        return failure(409, 'VEHICLE_PLATE_CONFLICT');
      if (data.vehicles.some((item) => item.externalCode === body.externalCode))
        return failure(409, 'VEHICLE_EXTERNAL_CODE_CONFLICT');
      const vehicle = {
        ...body,
        id: `a2607610-5100-4723-93d0-${String(nextId++).padStart(12, '0')}`,
        status: 'ACTIVE',
        createdAt: INSTANT,
      };
      data.vehicles.push(vehicle);
      return ok(vehicle, 201);
    }
    if (['vehicle', 'vehicle-status', 'state', 'telemetry', 'vehicle-alerts'].includes(route)) {
      const vehicle = data.vehicles.find((item) => item.id === id);
      if (!vehicle) return failure(404, 'VEHICLE_NOT_FOUND');
      if (route === 'vehicle') return ok(vehicle);
      if (route === 'vehicle-status') {
        if (!['ACTIVE', 'DISABLED'].includes(body?.status))
          return failure(400, 'REQUEST_MALFORMED_JSON');
        vehicle.status = body.status;
        return ok(vehicle);
      }
      if (route === 'state') {
        const sample = !empty && data.samples.filter((item) => item.vehicleId === id).at(-1);
        if (!sample) return failure(404, 'VEHICLE_STATE_NOT_AVAILABLE');
        const { speedKmh, engineTemperatureC, batteryVoltage, odometerKm, latitude, longitude } =
          sample;
        return ok({
          vehicleId: id,
          lastSequenceNumber: sample.sequenceNumber,
          lastSeenAt: sample.observedAt,
          stale: true,
          speedKmh,
          engineTemperatureC,
          batteryVoltage,
          odometerKm,
          latitude,
          longitude,
        });
      }
      if (route === 'telemetry') {
        if (!params.has('from') || !params.has('to')) return failure(400, 'REQUEST_INVALID');
        return paginated(
          empty
            ? []
            : data.samples.filter(
                (item) => item.vehicleId === id && inRange(item.observedAt, params),
              ),
          params,
          'observedAt,desc',
        );
      }
    }
    if (route === 'alerts' || route === 'vehicle-alerts') {
      const alerts = empty
        ? []
        : data.alerts.filter(
            (item) =>
              (route !== 'vehicle-alerts' || item.vehicleId === id) &&
              ['vehicleId', 'status', 'type', 'severity'].every(
                (key) => !params.has(key) || item[key] === params.get(key),
              ) &&
              inRange(item.createdAt, params),
          );
      return paginated(alerts, params, 'createdAt,desc');
    }
    if (route === 'alert' || route === 'alert-status') {
      const alert = data.alerts.find((item) => item.id === id);
      if (!alert) return failure(404, 'ALERT_NOT_FOUND');
      if (route === 'alert') return ok(alert);
      if (!['ACKNOWLEDGED', 'CLOSED'].includes(body?.status))
        return failure(400, 'REQUEST_MALFORMED_JSON');
      if (alert.status === body.status) return ok(alert);
      if (alert.status === 'CLOSED') return failure(409, 'ALERT_STATUS_TRANSITION_CONFLICT');
      alert.status = body.status;
      if (body.status === 'ACKNOWLEDGED') alert.acknowledgedAt = INSTANT;
      else alert.closedAt = INSTANT;
      return ok(alert);
    }
    return failure(404, 'REQUEST_INVALID');
  }
  const server = createServer(async (request, response) => {
    response.setHeader('Cache-Control', 'no-store');
    response.setHeader('X-FleetPulse-Fixture', 'ui');
    const url = new URL(request.url, 'http://127.0.0.1');
    const path = url.pathname.startsWith('/api/v1/') ? url.pathname.slice(7) : '';
    const route = targetFor(request.method, path);
    const emit = ({ status, body, html }) => {
      if (response.destroyed) return;
      response.writeHead(status, { 'Content-Type': html ? 'text/html' : 'application/json' });
      response.end(html ?? JSON.stringify(body));
    };
    if (!route) return emit(failure(404, 'REQUEST_INVALID'));
    const parts = path.split('/');
    const id = parts[2];
    if (id && !UUID.test(id)) return emit(failure(400, 'REQUEST_INVALID'));
    for (const key of url.searchParams.keys()) {
      if (url.searchParams.getAll(key).length !== 1 || url.searchParams.get(key) === '')
        return emit(failure(400, 'REQUEST_INVALID'));
    }
    for (const key of ['from', 'to'])
      if (url.searchParams.has(key) && !Number.isFinite(Date.parse(url.searchParams.get(key))))
        return emit(failure(400, 'REQUEST_INVALID'));
    if (
      url.searchParams.has('from') &&
      url.searchParams.has('to') &&
      instant(url.searchParams.get('from')) > instant(url.searchParams.get('to'))
    )
      return emit(failure(400, 'REQUEST_INVALID_TIME_RANGE'));
    let body;
    if (request.method !== 'GET') {
      try {
        let text = '';
        for await (const chunk of request) {
          text += chunk;
          if (text.length > 8192) return emit(failure(413, 'REQUEST_INVALID'));
        }
        body = JSON.parse(text);
      } catch {
        return emit(failure(400, 'REQUEST_MALFORMED_JSON'));
      }
    }
    const attempt = route === target ? ++matched : 0;
    const scenario = attempt ? sequence[Math.min(attempt - 1, sequence.length - 1)] : 'success';
    onRequest({ method: request.method, path, route, attempt, scenario });
    if (scenario === 'unavailable') return emit(failure(503, 'SERVICE_UNAVAILABLE'));
    if (scenario === 'internal') return emit(failure(500, 'INTERNAL_ERROR'));
    if (scenario === 'unknown') return emit(failure(503, 'FUTURE_ERROR'));
    if (scenario === 'html')
      return emit({ status: 502, html: '<html>PROXY DIAGNOSTICS: upstream failed</html>' });
    if (scenario === 'not-found')
      return emit(
        failure(404, route.startsWith('alert') ? 'ALERT_NOT_FOUND' : 'VEHICLE_NOT_FOUND'),
      );
    if (scenario === 'conflict') {
      // A second operator closed this synthetic alert. A subsequent GET reveals it.
      if (route === 'alert-status') {
        const alert = data.alerts.find((item) => item.id === id);
        if (alert) {
          alert.status = 'CLOSED';
          alert.closedAt = INSTANT;
        }
      }
      return emit(
        failure(
          409,
          route === 'alert-status' ? 'ALERT_STATUS_TRANSITION_CONFLICT' : 'VEHICLE_PLATE_CONFLICT',
        ),
      );
    }
    const result =
      scenario === 'timeout'
        ? failure(503, 'SERVICE_UNAVAILABLE')
        : normal(route, url.searchParams, id, body, scenario === 'empty');
    if (['slow', 'timeout', 'committed-timeout'].includes(scenario)) {
      const timer = setTimeout(
        () => {
          timers.delete(timer);
          emit(result);
        },
        scenario === 'slow' ? delayMs : timeoutMs,
      );
      timers.add(timer);
      response.once('close', () => {
        clearTimeout(timer);
        timers.delete(timer);
      });
    } else emit(result);
  });
  return {
    async listen(port = 4302) {
      await new Promise((resolve, reject) => {
        server.once('error', reject);
        server.listen(port, '127.0.0.1', resolve);
      });
      return `http://127.0.0.1:${server.address().port}`;
    },
    async close() {
      for (const timer of timers) clearTimeout(timer);
      timers.clear();
      server.closeAllConnections();
      await new Promise((resolve, reject) =>
        server.close((error) => (error ? reject(error) : resolve())),
      );
    },
  };
}
