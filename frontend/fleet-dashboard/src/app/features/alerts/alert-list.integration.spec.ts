import { filter, firstValueFrom } from 'rxjs';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter, Router, NavigationEnd } from '@angular/router';
import { App } from '../../app';
import { routes } from '../../app.routes';
import { API_CONFIG, DEFAULT_API_CONFIG } from '../../core/api/api-config';
import { alertsPage, TEST_ALERT } from '../../testing/fleet-api.stub';
import { AlertList } from './alert-list';

describe('Integrazione HTTP elenco alert', () => {
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
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    vi.useRealTimers();
  });
  it('trasmette tutti i filtri una sola volta e pagina sul server senza polling', async () => {
    const query = {
      vehicleId: TEST_ALERT.vehicleId,
      status: 'OPEN',
      type: 'ENGINE_TEMPERATURE_HIGH',
      severity: 'HIGH',
      from: '2026-11-08T20:00:00.123456Z',
      to: '2026-11-08T21:00:00.987654Z',
      page: '0',
      size: '2',
      sort: 'createdAt,asc',
    };
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    await TestBed.inject(Router).navigateByUrl('/alerts?' + new URLSearchParams(query));
    const http = TestBed.inject(HttpTestingController);
    const request = http.expectOne((request) => request.url === '/api/v1/alerts');
    expect(request.request.method).toBe('GET');
    for (const [key, value] of Object.entries(query))
      expect(request.request.params.getAll(key)).toEqual([value]);
    request.flush(alertsPage({ page: 0, size: 2 }));
    await fixture.whenStable();
    vi.useFakeTimers();
    await vi.advanceTimersByTimeAsync(15000);
    http.expectNone((request) => request.url === '/api/v1/alerts');
    vi.useRealTimers();
    const component = fixture.debugElement.query(By.directive(AlertList))
      .componentInstance as AlertList;
    const navigation = firstValueFrom(
      TestBed.inject(Router).events.pipe(filter((event) => event instanceof NavigationEnd)),
    );
    component.changePage(1);
    await navigation;
    const next = http.expectOne((request) => request.url === '/api/v1/alerts');
    expect(next.request.params.get('page')).toBe('1');
    expect(next.request.params.get('from')).toBe(query.from);
    expect(next.request.params.get('to')).toBe(query.to);
    next.flush(alertsPage({ page: 1, size: 2 }));
    await fixture.whenStable();
  });
  it('usa collection globale per un UUID senza risultati e omette i filtri assenti', async () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    await TestBed.inject(Router).navigateByUrl(
      `/alerts?vehicleId=${TEST_ALERT.vehicleId}&from=2026-11-08T20:00:00Z&page=0&size=50&sort=createdAt,desc`,
    );
    const http = TestBed.inject(HttpTestingController);
    const request = http.expectOne((request) => request.url === '/api/v1/alerts');
    expect(request.request.params.keys().sort()).toEqual([
      'from',
      'page',
      'size',
      'sort',
      'vehicleId',
    ]);
    request.flush({ ...alertsPage(), content: [], totalElements: 0, totalPages: 0 });
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Nessun alert corrisponde');
    http.expectNone((request) => request.url.startsWith('/api/v1/vehicles/'));
  });
});
