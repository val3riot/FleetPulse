import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter, Router } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';
import { App } from '../../app';
import { routes } from '../../app.routes';
import { ApiError } from '../../core/api/api-error';
import { MaintenanceAlertResponse, Page } from '../../core/api/api-models';
import { FleetApi } from '../../core/api/fleet-api';
import { alertsPage, createFleetApiStub, TEST_ALERT } from '../../testing/fleet-api.stub';
import { AlertList } from './alert-list';

const from = '2026-11-08T20:00:00.123456Z';
const to = '2026-11-08T21:00:00.987654Z';
describe('Elenco alert', () => {
  let api: ReturnType<typeof createFleetApiStub>;
  beforeEach(() => {
    api = createFleetApiStub();
    TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter(routes), { provide: FleetApi, useValue: api }],
    });
  });
  async function setup(url = '/alerts') {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    const router = TestBed.inject(Router);
    await router.navigateByUrl(url);
    await fixture.whenStable();
    const component = fixture.debugElement.query(By.directive(AlertList))
      .componentInstance as AlertList;
    return { fixture, component, router, element: fixture.nativeElement as HTMLElement };
  }
  it('normalizza la URL e carica una volta, mostra righe e non carica anagrafiche', async () => {
    const { router, element } = await setup();
    expect(router.url).toBe('/alerts?page=0&size=50&sort=createdAt,desc');
    expect(api.getAlerts).toHaveBeenCalledTimes(1);
    expect(api.getAlerts).toHaveBeenCalledWith(
      expect.objectContaining({ page: 0, size: 50, sort: 'createdAt,desc' }),
    );
    expect(element.querySelector('tbody')?.textContent).toContain('Temperatura motore alta');
    expect(element.querySelector('tbody time')?.textContent).toBe('08/11/2026 20:00:00');
    expect(api.getVehicle).not.toHaveBeenCalled();
    expect(api.getVehicles).not.toHaveBeenCalled();
  });
  it('applica i filtri combinati, resetta pagina e preserva gli istanti precisi', async () => {
    const { component, fixture } = await setup(
      `/alerts?from=${from}&to=${to}&page=3&size=2&sort=createdAt,desc`,
    );
    component.filters.patchValue({
      vehicleId: TEST_ALERT.vehicleId,
      status: 'OPEN',
      type: 'SERVICE_DUE',
      severity: 'CRITICAL',
      size: 10,
      sort: 'createdAt,asc',
    });
    expect(api.getAlerts).toHaveBeenCalledTimes(1);
    component.applyFilters();
    await fixture.whenStable();
    expect(api.getAlerts).toHaveBeenLastCalledWith({
      vehicleId: TEST_ALERT.vehicleId,
      status: 'OPEN',
      type: 'SERVICE_DUE',
      severity: 'CRITICAL',
      from,
      to,
      page: 0,
      size: 10,
      sort: 'createdAt,asc',
    });
    component.filters.controls.from.setValue('2020-01-01T00:00');
    component.changePage(1);
    await fixture.whenStable();
    expect(api.getAlerts).toHaveBeenLastCalledWith(expect.objectContaining({ from, to, page: 1 }));
  });
  it('ammette date opzionali e azzera tutti i filtri', async () => {
    const { component, fixture, router } = await setup(`/alerts?vehicleId=${TEST_ALERT.vehicleId}`);
    component.filters.controls.from.setValue('2026-11-08T00:00');
    component.applyFilters();
    await fixture.whenStable();
    expect(api.getAlerts).toHaveBeenLastCalledWith(
      expect.objectContaining({ from: '2026-11-08T00:00:00Z', to: undefined }),
    );
    component.resetFilters();
    await fixture.whenStable();
    expect(router.url).toBe('/alerts?page=0&size=50&sort=createdAt,desc');
    expect(component.filters.controls.vehicleId.value).toBe('');
  });
  it('non invia filtri invalidi e porta il focus al primo errore', async () => {
    const { component, fixture, element } = await setup();
    component.filters.patchValue({ vehicleId: 'invalid', size: 101 });
    component.applyFilters();
    await fixture.whenStable();
    expect(api.getAlerts).toHaveBeenCalledTimes(1);
    expect(element.ownerDocument.activeElement?.id).toBe('alert-vehicleId');
    expect(component.fieldError('vehicleId')).toContain('UUID');
    component.filters.patchValue({
      vehicleId: '',
      size: 1,
      from: '2026-11-09T00:00',
      to: '2026-11-08T00:00',
    });
    component.applyFilters();
    await fixture.whenStable();
    expect(component.fieldError('from')).toContain('precedere');
    expect(api.getAlerts).toHaveBeenCalledTimes(1);
  });
  it('segnala parametri URL duplicati e permette correzione senza richieste premature', async () => {
    const { component, fixture, element } = await setup('/alerts?status=OPEN&status=CLOSED');
    expect(api.getAlerts).not.toHaveBeenCalled();
    expect(element.querySelector('[role="alert"]')?.textContent).toContain('una sola volta');
    component.applyFilters();
    await fixture.whenStable();
    expect(api.getAlerts).toHaveBeenCalledTimes(1);
  });
  it('distingue vuoto e pagina oltre ultima e consente prima pagina', async () => {
    api.getAlerts.mockReturnValueOnce(
      of({ ...alertsPage(), content: [], totalElements: 0, totalPages: 0 }),
    );
    const { component, fixture, element } = await setup();
    expect(element.textContent).toContain('Nessun alert corrisponde');
    api.getAlerts.mockReturnValueOnce(
      of({ ...alertsPage(), content: [], page: 99, totalElements: 4, totalPages: 2, first: false }),
    );
    component.changePage(99);
    await fixture.whenStable();
    expect(element.textContent).toContain('Questa pagina non contiene alert');
    element.querySelector<HTMLButtonElement>('nav[aria-label="Paginazione alert"] button')!.click();
    await fixture.whenStable();
    expect(api.getAlerts).toHaveBeenLastCalledWith(expect.objectContaining({ page: 0 }));
  });
  it('gestisce errore senza falso zero e riprova gli stessi filtri', async () => {
    api.getAlerts.mockReturnValueOnce(
      throwError(() => new ApiError('SERVICE_UNAVAILABLE', 503, 'Non disponibile', true)),
    );
    const { component, fixture } = await setup();
    expect(component.result()).toBeNull();
    expect(component.error()?.status).toBe(503);
    component.reload();
    await fixture.whenStable();
    expect(component.error()).toBeNull();
    expect(api.getAlerts).toHaveBeenCalledTimes(2);
  });
  it('annulla letture superate e su uscita, senza applicare risposte vecchie', async () => {
    const old = new Subject<Page<MaintenanceAlertResponse>>();
    api.getAlerts.mockReturnValueOnce(old);
    const { component, fixture, router } = await setup();
    component.filters.controls.status.setValue('CLOSED');
    component.applyFilters();
    await fixture.whenStable();
    expect(old.observed).toBe(false);
    old.next({ ...alertsPage(), content: [] });
    expect(component.result()?.content.length).toBe(1);
    const leaving = new Subject<Page<MaintenanceAlertResponse>>();
    api.getAlerts.mockReturnValueOnce(leaving);
    component.reload();
    await router.navigateByUrl('/vehicles');
    await fixture.whenStable();
    expect(leaving.observed).toBe(false);
  });
  it('mantiene filtri e pagina andando al dettaglio e tornando', async () => {
    const { fixture, router, element } = await setup(
      '/alerts?status=OPEN&page=2&size=2&sort=createdAt,asc',
    );
    element.querySelector<HTMLAnchorElement>('tbody th a')!.click();
    await fixture.whenStable();
    expect(router.url).toContain(`/alerts/${TEST_ALERT.id}?`);
    expect(api.getAlert).toHaveBeenCalledWith(TEST_ALERT.id);
    element.querySelector<HTMLAnchorElement>('main > app-alert-detail > a')!.click();
    await fixture.whenStable();
    expect(router.url).toContain('status=OPEN');
    expect(router.url).toContain('page=2');
    expect(router.url).toContain('size=2');
  });
  it('ripristina i filtri dalla cronologia e non li cambia sul refresh', async () => {
    const { component, fixture, router } = await setup(
      '/alerts?status=OPEN&page=0&size=2&sort=createdAt,desc',
    );
    const initial = router.url;
    component.filters.controls.status.setValue('CLOSED');
    component.applyFilters();
    await fixture.whenStable();
    await router.navigateByUrl(initial);
    await fixture.whenStable();
    expect(component.filters.controls.status.value).toBe('OPEN');
    component.reload();
    await fixture.whenStable();
    expect(api.getAlerts).toHaveBeenLastCalledWith(
      expect.objectContaining({ status: 'OPEN', size: 2 }),
    );
  });
  it('porta il focus ai risultati dopo paginazione e rispetta chi si sposta sui filtri', async () => {
    const { component, fixture, element } = await setup();
    const page = new Subject<Page<MaintenanceAlertResponse>>();
    api.getAlerts.mockReturnValueOnce(page);
    element.querySelector<HTMLButtonElement>('nav[aria-label="Paginazione alert"] button')!.focus();
    component.changePage(1);
    await fixture.whenStable();
    page.next(alertsPage({ page: 1 }));
    page.complete();
    await fixture.whenStable();
    expect(document.activeElement?.getAttribute('aria-labelledby')).toBe('alert-results-title');
    const read = new Subject<Page<MaintenanceAlertResponse>>();
    api.getAlerts.mockReturnValueOnce(read);
    component.reload();
    await fixture.whenStable();
    element.querySelector<HTMLInputElement>('#alert-from')!.focus();
    read.next(alertsPage());
    read.complete();
    await fixture.whenStable();
    expect(document.activeElement?.id).toBe('alert-from');
  });
});
