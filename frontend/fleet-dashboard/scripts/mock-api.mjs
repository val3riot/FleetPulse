import { createServer } from 'node:http';

// Fixture di sviluppo: nessuna scrittura, nessuna connessione al backend reale.
const server = createServer((request, response) => {
  console.log(`${request.method} ${request.url}`);
  response.setHeader('Content-Type', 'application/json');
  function send(status, body) {
    response.writeHead(status);
    response.end(JSON.stringify(body));
  }
  function fail(status, code, details = []) {
    send(status, {
      timestamp: new Date().toISOString(),
      status,
      code,
      message: 'Messaggio diagnostico da NON mostrare come testo principale',
      path: request.url,
      details,
    });
  }
  if (request.method !== 'GET') return fail(405, 'REQUEST_METHOD_NOT_ALLOWED');
  if (request.url === '/api/v1/dashboard') {
    return send(200, {
      totalVehicles: 0,
      vehiclesByStatus: { ACTIVE: 0, DISABLED: 0 },
      recentlyReportingVehicles: 0,
      openAlerts: 0,
      relevantAlerts: [],
    });
  }
  const scenario = request.url?.split('/').at(-1);
  switch (scenario) {
    case 'ok':
      return send(200, {
        id: '97e194a8-64b3-4885-b1e6-25fd482f58c0',
        externalCode: 'VAN-001',
        plate: 'FP001AA',
        status: 'ACTIVE',
        serviceIntervalKm: 15000,
        nextServiceAtKm: 90000,
        createdAt: '2026-08-01T10:00:00Z',
      });
    case 'validation':
      return fail(400, 'REQUEST_INVALID', [{ field: 'plate', message: 'deve essere valorizzata' }]);
    case 'conflict':
      return fail(409, 'VEHICLE_PLATE_CONFLICT');
    case 'unavailable':
      return fail(503, 'SERVICE_UNAVAILABLE');
    case 'internal':
      return fail(500, 'INTERNAL_ERROR');
    case 'unknown':
      return fail(400, 'FUTURE_ERROR');
    case 'html':
      response.setHeader('Content-Type', 'text/html');
      response.writeHead(502);
      return response.end('<html>Proxy error</html>');
    case 'missing-state':
      return fail(404, 'VEHICLE_STATE_NOT_AVAILABLE');
    default:
      return fail(404, 'VEHICLE_NOT_FOUND');
  }
});
server.listen(4301, '127.0.0.1', () => console.log('Fixture FP-061: http://127.0.0.1:4301'));
