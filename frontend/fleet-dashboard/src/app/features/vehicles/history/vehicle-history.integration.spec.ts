import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter, Router } from '@angular/router';
import { App } from '../../../app';
import { routes } from '../../../app.routes';
import { API_CONFIG, DEFAULT_API_CONFIG } from '../../../core/api/api-config';
import { telemetryPage, TEST_VEHICLE } from '../../../testing/fleet-api.stub';
import { VehicleHistory } from './vehicle-history';

const id = 'bb09f827-c010-49f6-9ab3-02c7a92b06a0';
const query = {
  from: '2026-11-07T20:00:00.123456Z',
  to: '2026-11-08T20:00:00.987654Z',
  page: 0,
  size: 2,
  sort: 'observedAt,desc' as const,
};
const path = `/api/v1/vehicles/${id}/telemetry`;
const url = `/vehicles/${id}/telemetry?${new URLSearchParams(Object.entries(query).map(([key, value]) => [key, String(value)]))}`;

describe('Integrazione HTTP storico', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter(routes),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: API_CONFIG, useValue: { ...DEFAULT_API_CONFIG, readRetryCount: 0 } },
      ],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  async function setup() {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    await TestBed.inject(Router).navigateByUrl(url);
    const http = TestBed.inject(HttpTestingController);
    http.expectOne(`/api/v1/vehicles/${id}`).flush({ ...TEST_VEHICLE, id });
    const component = fixture.debugElement.query(By.directive(VehicleHistory))
      .componentInstance as VehicleHistory;
    return { fixture, http, component };
  }
  it('invia un solo valore per parametro, conserva la precisione e pagina realmente sul server', async () => {
    const { fixture, http, component } = await setup();
    const first = http.expectOne((request) => request.url === path);
    expect(first.request.method).toBe('GET');
    expect(first.request.params.keys().sort()).toEqual(['from', 'page', 'size', 'sort', 'to']);
    for (const [key, value] of Object.entries(query))
      expect(first.request.params.getAll(key)).toEqual([String(value)]);
    first.flush(telemetryPage(query));
    await fixture.whenStable();
    component.changePage(1);
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    const second = http.expectOne((request) => request.url === path);
    expect(second.request.params.get('page')).toBe('1');
    expect(second.request.params.get('from')).toBe(query.from);
    expect(second.request.params.get('to')).toBe(query.to);
    second.flush(telemetryPage({ ...query, page: 1 }));
    await fixture.whenStable();
    http.expectNone(`/api/v1/vehicles/${id}`);
  });
  it('mappa il 400 di range senza confonderlo con un risultato vuoto e recupera con Riprova', async () => {
    const { fixture, http, component } = await setup();
    http
      .expectOne((request) => request.url === path)
      .flush(
        { code: 'REQUEST_INVALID_TIME_RANGE', message: 'diagnostica privata' },
        { status: 400, statusText: 'Bad Request' },
      );
    await fixture.whenStable();
    expect(component.error()?.code).toBe('REQUEST_INVALID_TIME_RANGE');
    expect(component.result()).toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('diagnostica privata');
    component.reload();
    http.expectOne((request) => request.url === path).flush(telemetryPage(query));
    await fixture.whenStable();
    expect(component.error()).toBeNull();
    expect(component.result()?.totalElements).toBe(1);
  });
  it('annulla la richiesta HTTP precedente se vengono applicati nuovi filtri', async () => {
    const { fixture, http, component } = await setup();
    const old = http.expectOne((request) => request.url === path);
    component.form.controls.sort.setValue('observedAt,asc');
    component.applyFilters();
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    expect(old.cancelled).toBe(true);
    const current = http.expectOne((request) => request.url === path);
    expect(current.request.params.get('sort')).toBe('observedAt,asc');
    current.flush(telemetryPage({ ...query, sort: 'observedAt,asc' }));
    await fixture.whenStable();
    expect(component.loading()).toBe(false);
  });
});
