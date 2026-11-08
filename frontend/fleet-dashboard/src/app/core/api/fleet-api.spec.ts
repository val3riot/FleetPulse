import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Observable } from 'rxjs';
import { API_CONFIG, DEFAULT_API_CONFIG } from './api-config';
import { ApiError } from './api-error';
import { FleetApi } from './fleet-api';

const vehicle = {
  externalCode: 'VAN-001',
  plate: 'FP001AA',
  serviceIntervalKm: 15000,
  nextServiceAtKm: 90000,
};
const range = { from: '2026-08-01T10:00:00Z', to: '2026-08-01T11:00:00Z' };

describe('FleetApi', () => {
  let api: FleetApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: API_CONFIG,
          useValue: { ...DEFAULT_API_CONFIG, retryDelayMs: 100, timeoutMs: 1000 },
        },
      ],
    });
    api = TestBed.inject(FleetApi);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  const endpoints: [string, (client: FleetApi) => Observable<unknown>, string, string, unknown][] =
    [
      ['dashboard', (c) => c.getDashboard(), 'GET', '/dashboard', null],
      ['veicoli', (c) => c.getVehicles(), 'GET', '/vehicles', null],
      ['veicolo', (c) => c.getVehicle('id'), 'GET', '/vehicles/id', null],
      ['creazione', (c) => c.createVehicle(vehicle), 'POST', '/vehicles', vehicle],
      [
        'stato amministrativo',
        (c) => c.changeVehicleStatus('id', 'DISABLED'),
        'PATCH',
        '/vehicles/id/status',
        { status: 'DISABLED' },
      ],
      ['stato telemetrico', (c) => c.getVehicleState('id'), 'GET', '/vehicles/id/state', null],
      ['storico', (c) => c.getTelemetry('id', range), 'GET', '/vehicles/id/telemetry', null],
      ['alert globali', (c) => c.getAlerts(), 'GET', '/alerts', null],
      ['alert veicolo', (c) => c.getVehicleAlerts('id'), 'GET', '/vehicles/id/alerts', null],
      ['alert', (c) => c.getAlert('id'), 'GET', '/alerts/id', null],
      [
        'transizione alert',
        (c) => c.changeAlertStatus('id', 'CLOSED'),
        'PATCH',
        '/alerts/id',
        { status: 'CLOSED' },
      ],
    ];

  it.each(endpoints)('usa il contratto HTTP per %s', (_label, call, method, path, body) => {
    const next = vi.fn();
    call(api).subscribe(next);
    const request = http.expectOne((r) => r.url === `/api/v1${path}`);
    expect(request.request.method).toBe(method);
    expect(request.request.body).toEqual(body);
    request.flush({ fixture: true });
    expect(next).toHaveBeenCalledWith({ fixture: true });
  });

  it('usa la base URL configurata e rimuove gli slash finali', () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: API_CONFIG,
          useValue: { ...DEFAULT_API_CONFIG, baseUrl: 'https://fleet.example/custom/api/v1///' },
        },
      ],
    });
    api = TestBed.inject(FleetApi);
    http = TestBed.inject(HttpTestingController);
    api.getDashboard().subscribe();
    http.expectOne('https://fleet.example/custom/api/v1/dashboard').flush({});
  });

  it('omette filtri vuoti e conserva page=0, un solo sort e i valori con spazi', () => {
    api.getVehicles({ query: '  ', page: 0, size: 20, sort: 'plate,asc' }).subscribe();
    const request = http.expectOne((r) => r.url === '/api/v1/vehicles');
    expect(request.request.params.keys()).toEqual(['page', 'size', 'sort']);
    expect(request.request.params.get('page')).toBe('0');
    expect(request.request.params.getAll('sort')).toEqual(['plate,asc']);
    request.flush({});
    api.getVehicles({ query: 'VAN 001', status: 'ACTIVE' }).subscribe();
    const search = http.expectOne((r) => r.url === '/api/v1/vehicles');
    expect(search.request.params.get('query')).toBe('VAN 001');
    expect(search.request.params.get('status')).toBe('ACTIVE');
    search.flush({});
  });

  it('trasmette intervallo UTC e paginazione dello storico', () => {
    api.getTelemetry('id', { ...range, page: 0, size: 50, sort: 'observedAt,asc' }).subscribe();
    const request = http.expectOne((r) => r.url.endsWith('/telemetry'));
    for (const [key, value] of Object.entries({
      ...range,
      page: 0,
      size: 50,
      sort: 'observedAt,asc',
    })) {
      expect(request.request.params.get(key)).toBe(String(value));
    }
    request.flush({});
  });

  it('trasmette i filtri alert senza ripetizioni', () => {
    const query = {
      vehicleId: 'vehicle',
      status: 'OPEN' as const,
      type: 'SERVICE_DUE' as const,
      severity: 'HIGH' as const,
      ...range,
      page: 0,
      size: 25,
      sort: 'createdAt,desc' as const,
    };
    api.getAlerts(query).subscribe();
    const request = http.expectOne((r) => r.url === '/api/v1/alerts');
    for (const [key, value] of Object.entries(query))
      expect(request.request.params.getAll(key)).toEqual([String(value)]);
    request.flush({});
  });

  it('codifica identificativi come singolo segmento di path', () => {
    api.getVehicle('a/b?x=1').subscribe();
    http.expectOne('/api/v1/vehicles/a%2Fb%3Fx%3D1').flush({});
  });

  it('ritenta una lettura temporaneamente indisponibile e recupera', async () => {
    vi.useFakeTimers();
    const next = vi.fn();
    api.getDashboard().subscribe(next);
    http
      .expectOne('/api/v1/dashboard')
      .flush({ code: 'SERVICE_UNAVAILABLE' }, { status: 503, statusText: 'Unavailable' });
    http.expectNone('/api/v1/dashboard');
    await vi.advanceTimersByTimeAsync(100);
    http.expectOne('/api/v1/dashboard').flush({ totalVehicles: 1 });
    expect(next).toHaveBeenCalledWith({ totalVehicles: 1 });
  });

  it('limita il retry e propaga un errore normalizzato', async () => {
    vi.useFakeTimers();
    const failure = vi.fn();
    api.getDashboard().subscribe({ error: failure });
    http.expectOne('/api/v1/dashboard').error(new ProgressEvent('error'));
    await vi.advanceTimersByTimeAsync(100);
    http.expectOne('/api/v1/dashboard').error(new ProgressEvent('error'));
    await vi.advanceTimersByTimeAsync(500);
    http.expectNone('/api/v1/dashboard');
    expect(failure).toHaveBeenCalledOnce();
    expect(failure.mock.calls[0][0]).toMatchObject({ code: 'NETWORK_ERROR', retryable: true });
  });

  it.each([
    [400, 'REQUEST_INVALID'],
    [404, 'VEHICLE_NOT_FOUND'],
    [409, 'ALERT_STATUS_TRANSITION_CONFLICT'],
    [500, 'INTERNAL_ERROR'],
    [503, 'FUTURE_ERROR'],
  ])('non ritenta HTTP %s con codice %s', async (status, code) => {
    vi.useFakeTimers();
    const failure = vi.fn();
    api.getDashboard().subscribe({ error: failure });
    http.expectOne('/api/v1/dashboard').flush({ code }, { status, statusText: 'Error' });
    await vi.advanceTimersByTimeAsync(2000);
    http.expectNone('/api/v1/dashboard');
    expect(failure.mock.calls[0][0]).toBeInstanceOf(ApiError);
    expect(failure.mock.calls[0][0].code).toBe(code);
  });

  it.each(['POST', 'PATCH'] as const)(
    'non ripete una scrittura %s dopo errore temporaneo',
    async (method) => {
      vi.useFakeTimers();
      const failure = vi.fn();
      const call: Observable<unknown> =
        method === 'POST'
          ? api.createVehicle(vehicle)
          : api.changeAlertStatus('id', 'ACKNOWLEDGED');
      call.subscribe({ error: failure });
      http
        .expectOne((r) => r.method === method)
        .flush({ code: 'SERVICE_UNAVAILABLE' }, { status: 503, statusText: 'Unavailable' });
      await vi.advanceTimersByTimeAsync(2000);
      http.expectNone((r) => r.method === method);
      expect(failure).toHaveBeenCalledOnce();
    },
  );

  it('interrompe letture lente, esaurisce il retry e segnala timeout', async () => {
    vi.useFakeTimers();
    const failure = vi.fn();
    api.getDashboard().subscribe({ error: failure });
    const first = http.expectOne('/api/v1/dashboard');
    await vi.advanceTimersByTimeAsync(1000);
    expect(first.cancelled).toBe(true);
    await vi.advanceTimersByTimeAsync(100);
    const second = http.expectOne('/api/v1/dashboard');
    await vi.advanceTimersByTimeAsync(1000);
    expect(second.cancelled).toBe(true);
    expect(failure.mock.calls[0][0].code).toBe('REQUEST_TIMEOUT');
  });

  it('annulla il retry quando il consumatore si disiscrive', async () => {
    vi.useFakeTimers();
    const subscription = api.getDashboard().subscribe();
    http.expectOne('/api/v1/dashboard').error(new ProgressEvent('error'));
    subscription.unsubscribe();
    await vi.advanceTimersByTimeAsync(2000);
    http.expectNone('/api/v1/dashboard');
  });
});
