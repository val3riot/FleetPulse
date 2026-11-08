import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { App } from './app';
import { routes } from './app.routes';
import {
  alertsPage,
  telemetryPage,
  TEST_ALERT,
  TEST_DASHBOARD,
  TEST_STATE,
  TEST_VEHICLE,
} from './testing/fleet-api.stub';

const historyUrl =
  '/vehicles/id/telemetry?from=2026-11-08T00:00:00Z&to=2026-11-09T00:00:00Z&page=0&size=50&sort=observedAt,desc';
const vehiclePage = {
  content: [TEST_VEHICLE],
  page: 0,
  size: 20,
  totalElements: 1,
  totalPages: 1,
  first: true,
  last: true,
};
const cases = [
  {
    name: 'dashboard',
    url: '/dashboard',
    path: '/api/v1/dashboard',
    button: 'Aggiorna dashboard',
    success: TEST_DASHBOARD,
    visible: 'Panoramica flotta',
  },
  {
    name: 'elenco veicoli',
    url: '/vehicles',
    path: '/api/v1/vehicles',
    button: 'Riprova',
    success: vehiclePage,
    visible: TEST_VEHICLE.plate,
  },
  {
    name: 'dettaglio veicolo',
    url: '/vehicles/id',
    path: '/api/v1/vehicles/id',
    button: 'Riprova',
    success: TEST_VEHICLE,
    visible: TEST_VEHICLE.plate,
  },
  {
    name: 'ultima telemetria',
    url: '/vehicles/id',
    path: '/api/v1/vehicles/id/state',
    button: 'Aggiorna telemetria',
    success: TEST_STATE,
    visible: '72,4',
  },
  {
    name: 'storico',
    url: historyUrl,
    path: '/api/v1/vehicles/id/telemetry',
    button: 'Riprova',
    success: telemetryPage({ from: '', to: '' }),
    visible: '72,4',
  },
  {
    name: 'elenco alert',
    url: '/alerts?page=0&size=50&sort=createdAt,desc',
    path: '/api/v1/alerts',
    button: 'Riprova',
    success: alertsPage(),
    visible: TEST_ALERT.description,
  },
  {
    name: 'dettaglio alert',
    url: '/alerts/' + TEST_ALERT.id,
    path: '/api/v1/alerts/' + TEST_ALERT.id,
    button: 'Riprova lettura',
    success: TEST_ALERT,
    visible: TEST_ALERT.description,
  },
];

describe('Stati degradati delle pagine reali', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
    }),
  );
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    vi.useRealTimers();
  });
  it.each(cases)(
    '$name: fallback sicuro senza falso vuoto e recupero con un solo comando',
    async (scenario) => {
      const fixture = TestBed.createComponent(App);
      fixture.detectChanges();
      const http = TestBed.inject(HttpTestingController);
      await TestBed.inject(Router).navigateByUrl(scenario.url);
      fixture.detectChanges();
      if (scenario.name === 'ultima telemetria' || scenario.name === 'storico') {
        http.expectOne('/api/v1/vehicles/id').flush(TEST_VEHICLE);
        fixture.detectChanges();
      }
      const request = http.expectOne((request) => request.url === scenario.path);
      request.flush(
        { code: 'FUTURE_ERROR', message: '<script>PRIVATE DIAGNOSTICS</script>' },
        { status: 503, statusText: 'Unavailable' },
      );
      await fixture.whenStable();
      const element = fixture.nativeElement as HTMLElement;
      expect(element.querySelector('[role="alert"]')?.textContent).toContain(
        'Il servizio ha restituito un errore',
      );
      expect(element.textContent).not.toContain('PRIVATE DIAGNOSTICS');
      expect(element.textContent).not.toContain('Nessun veicolo corrisponde');
      expect(element.textContent).not.toContain('Nessun alert corrisponde');
      http.expectNone((request) => request.url === scenario.path);
      const retry = Array.from(element.querySelectorAll('button')).find(
        (button) => button.textContent?.trim() === scenario.button,
      )!;
      retry.click();
      retry.click();
      http.expectOne((request) => request.url === scenario.path).flush(scenario.success);
      fixture.detectChanges();
      if (scenario.name === 'dettaglio veicolo')
        http.expectOne('/api/v1/vehicles/id/state').flush(TEST_STATE);
      await fixture.whenStable();
      expect(element.querySelector('[role="alert"]')).toBeNull();
      expect(element.textContent).toContain(scenario.visible);
    },
  );
  it.each([
    {
      body: '<html>PRIVATE PROXY TRACE</html>',
      status: 502,
      message: 'Il servizio ha restituito un errore',
    },
    {
      body: { code: 'INTERNAL_ERROR', message: 'PRIVATE TRACE' },
      status: 500,
      message: 'Si è verificato un errore interno',
    },
  ])(
    'mostra fallback della risposta $status senza rendering HTML e senza retry implicito',
    async ({ body, status, message }) => {
      const fixture = TestBed.createComponent(App);
      fixture.detectChanges();
      await TestBed.inject(Router).navigateByUrl('/vehicles');
      const http = TestBed.inject(HttpTestingController);
      http
        .expectOne((request) => request.url === '/api/v1/vehicles')
        .flush(body, { status, statusText: 'Error' });
      await fixture.whenStable();
      expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(message);
      expect(fixture.nativeElement.textContent).not.toContain('PRIVATE');
      http.expectNone((request) => request.url === '/api/v1/vehicles');
    },
  );
  it.each(['network', 'unavailable', 'timeout'])(
    'esaurisce un solo retry GET per %s e permette recupero manuale senza perdere la ricerca',
    async (kind) => {
      vi.useFakeTimers();
      const fixture = TestBed.createComponent(App);
      fixture.detectChanges();
      await TestBed.inject(Router).navigateByUrl('/vehicles');
      fixture.detectChanges();
      const http = TestBed.inject(HttpTestingController);
      for (let attempt = 0; attempt < 2; attempt++) {
        const request = http.expectOne((request) => request.url === '/api/v1/vehicles');
        if (kind === 'timeout') {
          await vi.advanceTimersByTimeAsync(10000);
          expect(request.cancelled).toBe(true);
        } else if (kind === 'network') request.error(new ProgressEvent('error'));
        else
          request.flush(
            { code: 'SERVICE_UNAVAILABLE' },
            { status: 503, statusText: 'Unavailable' },
          );
        if (attempt === 0) {
          fixture.detectChanges();
          expect(fixture.nativeElement.textContent).toContain('Caricamento veicoli');
          await vi.advanceTimersByTimeAsync(499);
          http.expectNone((request) => request.url === '/api/v1/vehicles');
          await vi.advanceTimersByTimeAsync(1);
        }
      }
      fixture.detectChanges();
      const expected =
        kind === 'timeout'
          ? 'Il servizio non ha risposto in tempo'
          : kind === 'network'
            ? 'Impossibile contattare il servizio'
            : 'Servizio temporaneamente non disponibile';
      expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(expected);
      expect(fixture.nativeElement.textContent).not.toContain('Nessun veicolo');
      await vi.advanceTimersByTimeAsync(30000);
      http.expectNone((request) => request.url === '/api/v1/vehicles');
      const button = Array.from(
        (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
      ).find((button) => button.textContent?.trim() === 'Riprova')!;
      button.click();
      http.expectOne((request) => request.url === '/api/v1/vehicles').flush(vehiclePage);
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
      expect(fixture.nativeElement.textContent).toContain(TEST_VEHICLE.plate);
    },
  );
});
