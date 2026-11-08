import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter, Router } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';
import { App } from '../../../app';
import { routes } from '../../../app.routes';
import { ApiError } from '../../../core/api/api-error';
import { Page, TelemetryResponse } from '../../../core/api/api-models';
import { FleetApi } from '../../../core/api/fleet-api';
import { createFleetApiStub, telemetryPage, TEST_TELEMETRY } from '../../../testing/fleet-api.stub';
import { VehicleHistory } from './vehicle-history';

const from = '2026-11-07T20:00:00.123456Z';
const to = '2026-11-08T20:00:00.987654Z';
const url = (extra = '', id = 'id') =>
  `/vehicles/${id}/telemetry?from=${from}&to=${to}&page=0&size=2&sort=observedAt,desc${extra}`;

describe('Pagina storico telemetria', () => {
  let api: ReturnType<typeof createFleetApiStub>;
  beforeEach(() => {
    api = createFleetApiStub();
    TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter(routes), { provide: FleetApi, useValue: api }],
    });
  });
  async function setup(path = url()) {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    const router = TestBed.inject(Router);
    await router.navigateByUrl(path);
    await fixture.whenStable();
    const component = fixture.debugElement.query(By.directive(VehicleHistory))
      .componentInstance as VehicleHistory;
    return { fixture, router, component, element: fixture.nativeElement as HTMLElement };
  }
  it('apre dalla pagina dedicata, fissa le ultime 24 ore nella URL e carica una sola volta', async () => {
    const { router, component, element } = await setup('/vehicles/id/telemetry');
    expect(router.url).toContain('from=');
    expect(router.url).toContain('page=0&size=50&sort=observedAt,desc');
    const query = component.applied()!;
    expect(Date.parse(query.to) - Date.parse(query.from)).toBe(86400000);
    expect(api.getVehicle).toHaveBeenCalledTimes(1);
    expect(api.getTelemetry).toHaveBeenCalledTimes(1);
    expect(api.getTelemetry).toHaveBeenCalledWith('id', query);
    expect(element.querySelector('tbody time')?.textContent).toContain('08/11/2026 20:00:00');
    expect(element.querySelector('th[aria-sort]')?.getAttribute('aria-sort')).toBe('descending');
    expect(api.getVehicleState).not.toHaveBeenCalled();
  });
  it('applica filtri e ordinamento sul server ripartendo dalla prima pagina', async () => {
    const { component, fixture, router } = await setup(url().replace('page=0', 'page=3'));
    component.form.patchValue({
      from: '2026-11-01T10:15',
      to: '2026-11-02T11:30',
      size: 10,
      sort: 'observedAt,asc',
    });
    component.applyFilters();
    await fixture.whenStable();
    expect(api.getTelemetry).toHaveBeenLastCalledWith('id', {
      from: '2026-11-01T10:15:00Z',
      to: '2026-11-02T11:30:00Z',
      page: 0,
      size: 10,
      sort: 'observedAt,asc',
    });
    expect(router.url).toContain('page=0&size=10&sort=observedAt,asc');
    expect(api.getVehicle).toHaveBeenCalledTimes(1);
  });
  it('pagina sul server senza perdere la precisione UTC e ripristina i filtri dalla URL', async () => {
    const { component, fixture, router } = await setup();
    const initial = router.url;
    component.changePage(1);
    await fixture.whenStable();
    expect(api.getTelemetry).toHaveBeenLastCalledWith('id', {
      from,
      to,
      page: 1,
      size: 2,
      sort: 'observedAt,desc',
    });
    expect(api.getVehicle).toHaveBeenCalledTimes(1);
    component.form.patchValue({ from: '2020-01-01T00:00' });
    await router.navigateByUrl(initial);
    await fixture.whenStable();
    expect(component.form.controls.from.value).toBe('2026-11-07T20:00:00');
    expect(component.applied()?.page).toBe(0);
    component.applyFilters();
    await fixture.whenStable();
    expect(api.getTelemetry).toHaveBeenLastCalledWith('id', {
      from,
      to,
      page: 0,
      size: 2,
      sort: 'observedAt,desc',
    });
  });
  it('valida campi obbligatori, range e dimensione senza richieste e porta il focus al primo errore', async () => {
    const { component, fixture, element } = await setup();
    component.form.patchValue({ from: '', size: 101 });
    component.applyFilters();
    await fixture.whenStable();
    expect(api.getTelemetry).toHaveBeenCalledTimes(1);
    expect(component.fieldError('from')).toBe('Campo obbligatorio.');
    expect(component.fieldError('size')).toContain('1 a 100');
    expect(element.ownerDocument.activeElement?.id).toBe('history-from');
    component.form.patchValue({ from: '2026-11-09T00:00', size: 1 });
    component.applyFilters();
    await fixture.whenStable();
    expect(component.fieldError('to')).toContain('precedere');
    expect(api.getTelemetry).toHaveBeenCalledTimes(1);
  });
  it('segnala URL non valida senza HTTP e permette di correggerla', async () => {
    const { component, fixture, element } = await setup(url('&size=101'));
    expect(api.getVehicle).not.toHaveBeenCalled();
    expect(api.getTelemetry).not.toHaveBeenCalled();
    expect(element.querySelector('[role="alert"]')?.textContent).toContain('una sola volta');
    component.form.controls.size.setValue(2);
    component.applyFilters();
    await fixture.whenStable();
    expect(api.getTelemetry).toHaveBeenCalledTimes(1);
    expect(component.queryIssues()).toEqual([]);
  });
  it('distingue intervallo vuoto e pagina oltre i risultati permettendo il ritorno alla prima', async () => {
    api.getTelemetry.mockReturnValue(
      of({
        content: [],
        page: 0,
        size: 2,
        totalElements: 0,
        totalPages: 0,
        first: true,
        last: true,
      }),
    );
    const { component, fixture, element } = await setup();
    expect(element.textContent).toContain('Nessuna misura presente');
    api.getTelemetry.mockReturnValue(
      of({
        content: [],
        page: 10,
        size: 2,
        totalElements: 5,
        totalPages: 3,
        first: false,
        last: true,
      }),
    );
    component.changePage(10);
    await fixture.whenStable();
    expect(element.textContent).toContain('Questa pagina non contiene misure');
    const first = Array.from(element.querySelectorAll('nav button')).find((button) =>
      button.textContent?.includes('Prima pagina'),
    ) as HTMLButtonElement;
    expect(first.getAttribute('aria-disabled')).toBe('false');
    first.click();
    await fixture.whenStable();
    expect(api.getTelemetry).toHaveBeenLastCalledWith('id', expect.objectContaining({ page: 0 }));
  });
  it('mostra errore storico e consente una nuova lettura senza polling o nuova anagrafica', async () => {
    api.getTelemetry.mockReturnValueOnce(
      throwError(
        () =>
          new ApiError(
            'SERVICE_UNAVAILABLE',
            503,
            'Servizio temporaneamente non disponibile.',
            true,
          ),
      ),
    );
    const { component, fixture, element } = await setup();
    expect(element.querySelector('[role="alert"]')?.textContent).toContain('temporaneamente');
    expect(component.result()).toBeNull();
    component.reload();
    await fixture.whenStable();
    expect(component.result()?.content).toEqual([TEST_TELEMETRY]);
    expect(api.getVehicle).toHaveBeenCalledTimes(1);
    expect(api.getTelemetry).toHaveBeenCalledTimes(2);
    expect(element.querySelector('[role="alert"]')).toBeNull();
  });
  it('distingue veicolo inesistente e non richiede lo storico prima di verificarlo', async () => {
    api.getVehicle.mockReturnValueOnce(
      throwError(() => new ApiError('VEHICLE_NOT_FOUND', 404, 'Veicolo non trovato.', false)),
    );
    const { component, fixture, element } = await setup();
    expect(element.querySelector('[role="alert"]')?.textContent).toContain('Veicolo non trovato');
    expect(api.getTelemetry).not.toHaveBeenCalled();
    component.reload();
    await fixture.whenStable();
    expect(api.getVehicle).toHaveBeenCalledTimes(2);
    expect(api.getTelemetry).toHaveBeenCalledTimes(1);
  });
  it('cancella letture superate e ignora risposte del filtro o veicolo precedente', async () => {
    const pending = new Subject<Page<TelemetryResponse>>();
    api.getTelemetry.mockReturnValueOnce(pending);
    const { component, fixture, router } = await setup();
    expect(component.loading()).toBe(true);
    expect(pending.observed).toBe(true);
    component.form.controls.size.setValue(3);
    component.applyFilters();
    await fixture.whenStable();
    expect(pending.observed).toBe(false);
    pending.next({ ...telemetryPage({ from, to }), content: [{ ...TEST_TELEMETRY, id: 99 }] });
    expect(component.result()?.content[0].id).toBe(1);
    await router.navigateByUrl(url('', 'other'));
    await fixture.whenStable();
    expect(component.vehicle()?.id).toBe('other');
    expect(api.getTelemetry).toHaveBeenLastCalledWith('other', expect.any(Object));
    const leaving = new Subject<Page<TelemetryResponse>>();
    api.getTelemetry.mockReturnValueOnce(leaving);
    component.reload();
    expect(leaving.observed).toBe(true);
    await router.navigateByUrl('/vehicles');
    await fixture.whenStable();
    expect(leaving.observed).toBe(false);
  });
  it('la pagina usa i filtri applicati e non invia le modifiche ancora in bozza', async () => {
    const { component, fixture } = await setup();
    component.form.patchValue({ from: '2020-01-01T00:00', sort: 'observedAt,asc' });
    expect(api.getTelemetry).toHaveBeenCalledTimes(1);
    component.changePage(1);
    await fixture.whenStable();
    expect(api.getTelemetry).toHaveBeenLastCalledWith('id', {
      from,
      to,
      page: 1,
      size: 2,
      sort: 'observedAt,desc',
    });
  });
  it('mantiene ordine delle righe e unità senza ordinamenti locali', async () => {
    api.getTelemetry.mockReturnValueOnce(
      of({
        ...telemetryPage({ from, to }),
        content: [
          { ...TEST_TELEMETRY, id: 2, sequenceNumber: 3 },
          { ...TEST_TELEMETRY, id: 1, sequenceNumber: 50 },
        ],
      }),
    );
    const { element } = await setup();
    const rows = Array.from(element.querySelectorAll('tbody tr'));
    expect(rows[0].querySelector('td')?.textContent).toBe('3');
    expect(rows[1].querySelector('td')?.textContent).toBe('50');
    expect(rows[0].textContent).toContain('72,4');
    expect(element.querySelector('thead')?.textContent).toContain('km/h');
    expect(element.querySelector('thead')?.textContent).toContain('°C');
  });
  it('porta il focus ai risultati dopo la paginazione senza sottrarlo a chi modifica i filtri', async () => {
    const { component, fixture, element } = await setup();
    const nextPage = new Subject<Page<TelemetryResponse>>();
    api.getTelemetry.mockReturnValueOnce(nextPage);
    element
      .querySelector<HTMLButtonElement>('nav[aria-label="Paginazione storico"] button')!
      .focus();
    component.changePage(1);
    await fixture.whenStable();
    nextPage.next(telemetryPage({ from, to, page: 1 }));
    nextPage.complete();
    await fixture.whenStable();
    expect(element.ownerDocument.activeElement?.getAttribute('aria-labelledby')).toBe(
      'history-results-title',
    );
    const reload = new Subject<Page<TelemetryResponse>>();
    api.getTelemetry.mockReturnValueOnce(reload);
    component.reload();
    await fixture.whenStable();
    element.querySelector<HTMLInputElement>('#history-size')!.focus();
    reload.next(telemetryPage({ from, to, page: 1 }));
    reload.complete();
    await fixture.whenStable();
    expect(element.ownerDocument.activeElement?.id).toBe('history-size');
  });
});
