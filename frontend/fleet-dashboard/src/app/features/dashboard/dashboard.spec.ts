import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { API_CONFIG, DEFAULT_API_CONFIG } from '../../core/api/api-config';
import { DashboardResponse } from '../../core/api/api-models';
import { Dashboard } from './dashboard';

const dashboard: DashboardResponse = {
  totalVehicles: 120,
  vehiclesByStatus: { ACTIVE: 104, DISABLED: 16 },
  recentlyReportingVehicles: 98,
  openAlerts: 1,
  relevantAlerts: [
    {
      id: 'critical',
      vehicleId: 'first-vehicle',
      type: 'ENGINE_TEMPERATURE_HIGH',
      severity: 'CRITICAL',
      status: 'OPEN',
      description: 'Temperatura molto alta',
      createdAt: '2026-08-01T10:00:00Z',
    },
    {
      id: 'acknowledged',
      vehicleId: 'second-vehicle',
      type: 'SERVICE_DUE',
      severity: 'HIGH',
      status: 'ACKNOWLEDGED',
      description: 'Manutenzione da verificare',
      createdAt: '2026-08-01T10:01:00Z',
    },
  ],
};

describe('Dashboard operativa', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [Dashboard],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: API_CONFIG, useValue: { ...DEFAULT_API_CONFIG, readRetryCount: 0 } },
      ],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  function setup() {
    const fixture = TestBed.createComponent(Dashboard);
    fixture.detectChanges();
    return {
      fixture,
      component: fixture.componentInstance,
      http: TestBed.inject(HttpTestingController),
    };
  }

  it('usa tutti i KPI e gli alert nell’ordine del backend, senza query per veicolo', () => {
    const { fixture, http } = setup();
    expect(fixture.nativeElement.textContent).toContain('Caricamento dashboard');
    http.expectOne('/api/v1/dashboard').flush(dashboard);
    fixture.detectChanges();
    const kpis: HTMLElement[] = [...fixture.nativeElement.querySelectorAll('.kpi')];
    expect(kpis.map((kpi) => kpi.querySelector('dd')!.textContent)).toEqual([
      '120',
      '104',
      '16',
      '98',
      '1',
    ]);
    const rows: HTMLElement[] = [...fixture.nativeElement.querySelectorAll('tbody tr')];
    expect(rows[0].textContent).toContain('Critica');
    expect(rows[1].textContent).toContain('Preso in carico');
    expect(rows[0].querySelector('a')!.getAttribute('href')).toBe('/vehicles/first-vehicle');
    expect(rows[1].querySelector('a')!.getAttribute('href')).toBe('/vehicles/second-vehicle');
    expect(fixture.nativeElement.textContent).toContain('possono includere veicoli disabilitati');
    http.expectNone((r) => r.url.includes('/vehicles/'));
  });

  it('distingue una flotta vuota da un errore senza inventare numeri', () => {
    const { fixture, http } = setup();
    http.expectOne('/api/v1/dashboard').flush({
      totalVehicles: 0,
      vehiclesByStatus: { ACTIVE: 0, DISABLED: 0 },
      recentlyReportingVehicles: 0,
      openAlerts: 0,
      relevantAlerts: [],
    });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.kpi dd')).toHaveLength(5);
    expect(fixture.nativeElement.textContent).toContain('Nessun veicolo registrato');
    expect(fixture.nativeElement.textContent).toContain('Nessun alert rilevante');
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
  });

  it('presenta un errore iniziale senza conteggi zero sostitutivi', () => {
    const { fixture, http } = setup();
    http
      .expectOne('/api/v1/dashboard')
      .flush({ code: 'SERVICE_UNAVAILABLE' }, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('dl')).toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('Nessun veicolo registrato');
  });

  it('conserva i dati e i controlli durante refresh, errore e recupero senza cambiare focus', async () => {
    const { fixture, component, http } = setup();
    http.expectOne('/api/v1/dashboard').flush(dashboard);
    await fixture.whenStable();
    const refresh: HTMLButtonElement = fixture.nativeElement.querySelector('button');
    const table = fixture.nativeElement.querySelector('table');
    const firstTime = component.resource.lastSuccessfulAt();
    refresh.focus();
    refresh.click();
    refresh.click();
    fixture.detectChanges();
    expect(refresh.disabled).toBe(false);
    expect(refresh.getAttribute('aria-disabled')).toBe('true');
    expect(fixture.nativeElement.querySelector('table')).toBe(table);
    http
      .expectOne('/api/v1/dashboard')
      .flush({ code: 'SERVICE_UNAVAILABLE' }, { status: 503, statusText: 'Unavailable' });
    await fixture.whenStable();
    expect(component.resource.data()?.totalVehicles).toBe(120);
    expect(component.resource.lastSuccessfulAt()).toBe(firstTime);
    expect(fixture.nativeElement.textContent).toContain('ultima lettura riuscita');
    expect(document.activeElement).toBe(refresh);
    refresh.click();
    http
      .expectOne('/api/v1/dashboard')
      .flush({ ...dashboard, totalVehicles: 121, vehiclesByStatus: { ACTIVE: 105, DISABLED: 16 } });
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
    expect(component.resource.data()?.totalVehicles).toBe(121);
    expect(document.activeElement).toBe(refresh);
    expect(fixture.nativeElement.querySelector('table')).toBe(table);
  });

  it('pausa e riprende con pulsanti dal nome esplicito', async () => {
    const { fixture, component, http } = setup();
    http.expectOne('/api/v1/dashboard').flush(dashboard);
    await fixture.whenStable();
    const pause: HTMLButtonElement = fixture.nativeElement.querySelectorAll('button')[1];
    pause.click();
    await fixture.whenStable();
    expect(component.resource.paused()).toBe(true);
    expect(pause.textContent).toContain('Riprendi dashboard');
    expect(fixture.nativeElement.textContent).toContain('automatico in pausa');
    pause.click();
    fixture.detectChanges();
    http.expectOne('/api/v1/dashboard').flush(dashboard);
    await fixture.whenStable();
    expect(component.resource.paused()).toBe(false);
  });

  it('un errore interno ferma i cicli automatici e offre riprova manuale', () => {
    const { fixture, component, http } = setup();
    http
      .expectOne('/api/v1/dashboard')
      .flush({ code: 'INTERNAL_ERROR' }, { status: 500, statusText: 'Error' });
    fixture.detectChanges();
    expect(component.resource.stopped()).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Puoi riprovare manualmente');
  });
  it('mostra date degli alert e ultima lettura in UTC fino ai secondi', () => {
    const { fixture, http } = setup();
    const instant = '2026-11-08T20:00:01.123456Z';
    http
      .expectOne('/api/v1/dashboard')
      .flush(
        { ...dashboard, relevantAlerts: [{ ...dashboard.relevantAlerts[0], createdAt: instant }] },
        { headers: { Date: 'Sun, 08 Nov 2026 20:00:02 GMT' } },
      );
    fixture.detectChanges();
    const alertTime = fixture.nativeElement.querySelector('tbody time') as HTMLTimeElement;
    expect(alertTime.textContent).toBe('08/11/2026 20:00:01');
    expect(alertTime.dateTime).toBe(instant);
    const readTime = fixture.nativeElement.querySelector(
      'app-polling-controls time',
    ) as HTMLTimeElement;
    expect(readTime.textContent).toMatch(/^\d{2}\/\d{2}\/\d{4} \d{2}:\d{2}:\d{2}$/);
  });
});
