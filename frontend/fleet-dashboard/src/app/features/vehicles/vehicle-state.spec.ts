import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { API_CONFIG, DEFAULT_API_CONFIG } from '../../core/api/api-config';
import { TEST_STATE } from '../../testing/fleet-api.stub';
import { VehicleState } from './vehicle-state';

describe('Ultima telemetria del veicolo', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [VehicleState],
      providers: [
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
  function setup() {
    const fixture = TestBed.createComponent(VehicleState);
    fixture.componentRef.setInput('vehicleId', 'first');
    fixture.detectChanges();
    return {
      fixture,
      component: fixture.componentInstance,
      http: TestBed.inject(HttpTestingController),
    };
  }

  it.each([false, true])('mostra tutte le misure/unità e il flag stale=%s del backend', (stale) => {
    const { fixture, http } = setup();
    http
      .expectOne('/api/v1/vehicles/first/state')
      .flush({ ...TEST_STATE, stale, lastSeenAt: '2026-08-01T10:00:00Z' });
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent;
    for (const value of ['72,4 km/h', '91,8 °C', '12,6 V', '85.312 km', '41,9028 °', '12,4964 °'])
      expect(text).toContain(value);
    expect(text).toContain(stale ? 'Misura non recente' : 'Misura recente all’ultima verifica');
    expect(text).toContain('Istante della misura');
    expect(text).toContain('Ultima lettura riuscita');
    expect(text).toContain('01/08/2026');
  });

  it('tratta assenza telemetria come stato atteso e mantiene il polling abilitato', () => {
    const { fixture, component, http } = setup();
    http
      .expectOne('/api/v1/vehicles/first/state')
      .flush({ code: 'VEHICLE_STATE_NOT_AVAILABLE' }, { status: 404, statusText: 'Not found' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Nessuna telemetria disponibile');
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('dl')).toBeNull();
    expect(component.resource.stopped()).toBe(false);
    component.resource.refresh();
    http.expectOne('/api/v1/vehicles/first/state').flush(TEST_STATE);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('dl')).not.toBeNull();
  });

  it('rileva automaticamente la prima misura dopo una risposta senza telemetria', () => {
    vi.useFakeTimers();
    const { fixture, http } = setup();
    http
      .expectOne('/api/v1/vehicles/first/state')
      .flush({ code: 'VEHICLE_STATE_NOT_AVAILABLE' }, { status: 404, statusText: 'Not found' });
    fixture.detectChanges();
    vi.advanceTimersByTime(5000);
    http.expectOne('/api/v1/vehicles/first/state').flush({ ...TEST_STATE, stale: false });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('Nessuna telemetria disponibile');
    expect(fixture.nativeElement.textContent).toContain('Misura recente all’ultima verifica');
  });

  it('distingue veicolo assente, cancella i dati e ferma il polling', () => {
    const { fixture, component, http } = setup();
    http.expectOne('/api/v1/vehicles/first/state').flush(TEST_STATE);
    component.resource.refresh();
    http
      .expectOne('/api/v1/vehicles/first/state')
      .flush({ code: 'VEHICLE_NOT_FOUND' }, { status: 404, statusText: 'Not found' });
    fixture.detectChanges();
    expect(component.resource.stopped()).toBe(true);
    expect(component.resource.data()).toBeNull();
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'Veicolo non trovato',
    );
    expect(fixture.nativeElement.querySelector('dl')).toBeNull();
  });

  it('non conserva la misura precedente dopo risposta esplicita di assenza telemetria', () => {
    const { component, http } = setup();
    http.expectOne('/api/v1/vehicles/first/state').flush(TEST_STATE);
    component.resource.refresh();
    http
      .expectOne('/api/v1/vehicles/first/state')
      .flush({ code: 'VEHICLE_STATE_NOT_AVAILABLE' }, { status: 404, statusText: 'Not found' });
    expect(component.resource.data()).toBeNull();
    expect(component.noTelemetry()).toBe(true);
  });

  it('mantiene l’ultima misura su errore temporaneo e recupera senza smontare il contenuto', async () => {
    const { fixture, component, http } = setup();
    http.expectOne('/api/v1/vehicles/first/state').flush(TEST_STATE);
    await fixture.whenStable();
    const readings = fixture.nativeElement.querySelector('dl');
    const time = component.resource.lastSuccessfulAt();
    component.resource.refresh();
    http
      .expectOne('/api/v1/vehicles/first/state')
      .flush({ code: 'SERVICE_UNAVAILABLE' }, { status: 503, statusText: 'Unavailable' });
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('dl')).toBe(readings);
    expect(component.resource.lastSuccessfulAt()).toBe(time);
    expect(fixture.nativeElement.textContent).toContain('I valori visualizzati appartengono');
    component.resource.refresh();
    http.expectOne('/api/v1/vehicles/first/state').flush({ ...TEST_STATE, speedKmh: 10 });
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('10 km/h');
  });

  it('cambia id annullando la vecchia richiesta e non mostra dati di un altro veicolo', () => {
    const { fixture, component, http } = setup();
    const first = http.expectOne('/api/v1/vehicles/first/state');
    fixture.componentRef.setInput('vehicleId', 'second');
    fixture.detectChanges();
    expect(first.cancelled).toBe(true);
    expect(component.resource.data()).toBeNull();
    http.expectOne('/api/v1/vehicles/second/state').flush({ ...TEST_STATE, vehicleId: 'second' });
    expect(component.resource.data()?.vehicleId).toBe('second');
  });

  it('sospende letture durante la conferma del cambio stato e le riprende alla chiusura', () => {
    const { fixture, component, http } = setup();
    http.expectOne('/api/v1/vehicles/first/state').flush(TEST_STATE);
    fixture.componentRef.setInput('suspended', true);
    fixture.detectChanges();
    component.resource.refresh();
    http.expectNone('/api/v1/vehicles/first/state');
    fixture.componentRef.setInput('suspended', false);
    fixture.detectChanges();
    http.expectOne('/api/v1/vehicles/first/state').flush(TEST_STATE);
  });

  it('annulla la richiesta lasciando la sezione', () => {
    const { fixture, http } = setup();
    const request = http.expectOne('/api/v1/vehicles/first/state');
    fixture.destroy();
    expect(request.cancelled).toBe(true);
  });
});
