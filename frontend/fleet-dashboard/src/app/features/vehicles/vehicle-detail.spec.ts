import { Component, input } from '@angular/core';
import { VehicleState } from './vehicle-state';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { API_CONFIG, DEFAULT_API_CONFIG } from '../../core/api/api-config';
import { VehicleDetail } from './vehicle-detail';

@Component({ selector: 'app-vehicle-state', template: '' })
class VehicleStateStub {
  readonly vehicleId = input.required<string>();
  readonly suspended = input(false);
}

const vehicle = {
  id: 'id',
  externalCode: 'VAN-001',
  plate: 'FP001AA',
  status: 'ACTIVE',
  serviceIntervalKm: 15000,
  nextServiceAtKm: 90000,
  createdAt: '2026-08-01T10:00:00Z',
};

describe('Dettaglio e stato veicolo', () => {
  let params: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
  beforeEach(() => {
    params = new BehaviorSubject(convertToParamMap({ id: 'id' }));
    TestBed.overrideComponent(VehicleDetail, {
      remove: { imports: [VehicleState] },
      add: { imports: [VehicleStateStub] },
    });
    TestBed.configureTestingModule({
      imports: [VehicleDetail],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { paramMap: params } },
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: API_CONFIG, useValue: { ...DEFAULT_API_CONFIG, readRetryCount: 0 } },
      ],
    });
  });
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  function setup() {
    const fixture = TestBed.createComponent(VehicleDetail);
    fixture.detectChanges();
    const dialog: HTMLDialogElement = fixture.nativeElement.querySelector('dialog');
    // jsdom does not implement the native dialog API; keep spies local to this fixture.
    Object.defineProperties(dialog, {
      showModal: { value: vi.fn(() => dialog.setAttribute('open', '')), configurable: true },
      close: { value: vi.fn(() => dialog.removeAttribute('open')), configurable: true },
    });
    return {
      fixture,
      component: fixture.componentInstance,
      http: TestBed.inject(HttpTestingController),
    };
  }

  it('carica anagrafica e stato e mostra le unità', () => {
    const { fixture, http } = setup();
    expect(fixture.nativeElement.textContent).toContain('Caricamento veicolo');
    http.expectOne('/api/v1/vehicles/id').flush(vehicle);
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('VAN-001');
    expect(text).toContain('FP001AA');
    expect(text).toContain('15000 km');
    expect(text).toContain('90000 km');
    expect(text).toContain('01/08/2026');
    expect(text).toContain('Attivo');
  });

  it.each([
    [404, 'VEHICLE_NOT_FOUND'],
    [503, 'SERVICE_UNAVAILABLE'],
  ])('gestisce HTTP %s senza mostrare dati inesistenti e recupera al retry', (status, code) => {
    const { fixture, component, http } = setup();
    http
      .expectOne('/api/v1/vehicles/id')
      .flush({ code }, { status: status as number, statusText: 'Error' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('dl')).toBeNull();
    component.reload();
    http.expectOne('/api/v1/vehicles/id').flush(vehicle);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('dl')).not.toBeNull();
  });

  it('richiede conferma, consente annullamento e non cambia stato prima della risposta', () => {
    const { fixture, component, http } = setup();
    http.expectOne('/api/v1/vehicles/id').flush(vehicle);
    component.requestStatusChange();
    expect(component.pendingStatus()).toBe('DISABLED');
    component.cancelStatusChange();
    http.expectNone((r) => r.method === 'PATCH');
    component.requestStatusChange();
    component.confirmStatusChange();
    component.confirmStatusChange();
    const request = http.expectOne('/api/v1/vehicles/id/status');
    expect(request.request.body).toEqual({ status: 'DISABLED' });
    expect(component.vehicle()!.status).toBe('ACTIVE');
    expect(component.saving()).toBe(true);
    request.flush({ ...vehicle, status: 'DISABLED' });
    fixture.detectChanges();
    expect(component.vehicle()!.status).toBe('DISABLED');
    expect(fixture.nativeElement.textContent).toContain('Stato del veicolo aggiornato.');
    expect(fixture.nativeElement.textContent).toContain('Attiva veicolo');
    component.requestStatusChange();
    expect(component.pendingStatus()).toBe('ACTIVE');
  });

  it('apre una vera dialog modale e annulla con Esc senza scrivere, ripristinando il focus', () => {
    const { fixture, component, http } = setup();
    http.expectOne('/api/v1/vehicles/id').flush(vehicle);
    fixture.detectChanges();
    const dialog: HTMLDialogElement = fixture.nativeElement.querySelector('dialog');
    const trigger: HTMLButtonElement =
      fixture.nativeElement.querySelector('.panel .actions button');
    expect(dialog.open).toBe(false);
    trigger.focus();
    trigger.click();
    fixture.detectChanges();
    expect(dialog.showModal).toHaveBeenCalledOnce();
    expect(dialog.open).toBe(true);
    expect(dialog.getAttribute('aria-labelledby')).toBe('status-dialog-title');
    expect(dialog.getAttribute('aria-describedby')).toBe('status-dialog-description');
    expect(document.activeElement?.textContent?.trim()).toBe('Annulla');
    const escape = new Event('cancel', { cancelable: true });
    dialog.dispatchEvent(escape);
    fixture.detectChanges();
    expect(escape.defaultPrevented).toBe(true);
    expect(component.pendingStatus()).toBeNull();
    expect(dialog.close).toHaveBeenCalledOnce();
    expect(dialog.open).toBe(false);
    expect(document.activeElement).toBe(trigger);
    http.expectNone((r) => r.method === 'PATCH');
  });

  it('mantiene la modale aperta e blocca Esc e doppi invii durante il salvataggio', () => {
    const { fixture, component, http } = setup();
    http.expectOne('/api/v1/vehicles/id').flush(vehicle);
    fixture.detectChanges();
    const dialog: HTMLDialogElement = fixture.nativeElement.querySelector('dialog');
    component.requestStatusChange();
    fixture.detectChanges();
    const buttons = dialog.querySelectorAll('button');
    buttons[0].click();
    fixture.detectChanges();
    expect(dialog.open).toBe(true);
    expect(buttons[0].disabled).toBe(true);
    expect(buttons[1].disabled).toBe(true);
    expect(dialog.getAttribute('aria-busy')).toBe('true');
    expect(document.activeElement?.id).toBe('status-dialog-description');
    dialog.dispatchEvent(new Event('cancel', { cancelable: true }));
    component.confirmStatusChange();
    fixture.detectChanges();
    expect(dialog.open).toBe(true);
    expect(component.pendingStatus()).toBe('DISABLED');
    http.expectOne('/api/v1/vehicles/id/status').flush({ ...vehicle, status: 'DISABLED' });
    fixture.detectChanges();
    expect(dialog.open).toBe(false);
    expect(component.vehicle()!.status).toBe('DISABLED');
    expect(document.activeElement?.textContent?.trim()).toBe('Attiva veicolo');
  });

  it('chiude la modale dopo errore e porta il focus su Aggiorna per verificare lo stato', () => {
    const { fixture, component, http } = setup();
    http.expectOne('/api/v1/vehicles/id').flush(vehicle);
    fixture.detectChanges();
    component.requestStatusChange();
    fixture.detectChanges();
    component.confirmStatusChange();
    http
      .expectOne('/api/v1/vehicles/id/status')
      .flush({ code: 'SERVICE_UNAVAILABLE' }, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('dialog').open).toBe(false);
    expect(document.activeElement?.textContent).toBe('Aggiorna');
    expect(fixture.nativeElement.querySelector('[role="alert"]')).not.toBeNull();
  });

  it('attiva un veicolo disabilitato con una scrittura confermata dal server', () => {
    const { fixture, component, http } = setup();
    http.expectOne('/api/v1/vehicles/id').flush({ ...vehicle, status: 'DISABLED' });
    component.requestStatusChange();
    component.confirmStatusChange();
    const request = http.expectOne('/api/v1/vehicles/id/status');
    expect(request.request.body).toEqual({ status: 'ACTIVE' });
    expect(component.vehicle()!.status).toBe('DISABLED');
    request.flush(vehicle);
    fixture.detectChanges();
    expect(component.vehicle()!.status).toBe('ACTIVE');
    expect(fixture.nativeElement.textContent).toContain('Disabilita veicolo');
  });

  it('mantiene lo stato confermato dopo errore di scrittura e rilegge prima di permettere altre modifiche', () => {
    const { fixture, component, http } = setup();
    http.expectOne('/api/v1/vehicles/id').flush(vehicle);
    component.requestStatusChange();
    component.confirmStatusChange();
    http
      .expectOne('/api/v1/vehicles/id/status')
      .flush({ code: 'SERVICE_UNAVAILABLE' }, { status: 503, statusText: 'Unavailable' });
    expect(component.vehicle()!.status).toBe('ACTIVE');
    expect(component.uncertain()).toBe(true);
    component.requestStatusChange();
    component.confirmStatusChange();
    http.expectNone((r) => r.method === 'PATCH');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Aggiorna i dati');
    component.reload();
    http.expectOne('/api/v1/vehicles/id').flush({ ...vehicle, status: 'DISABLED' });
    expect(component.uncertain()).toBe(false);
    component.requestStatusChange();
    expect(component.pendingStatus()).toBe('ACTIVE');
  });

  it('ripristina un punto di lettura utile dopo aggiornamento asincrono del dettaglio', async () => {
    const { fixture, http } = setup();
    http.expectOne('/api/v1/vehicles/id').flush(vehicle);
    await fixture.whenStable();
    const refresh: HTMLButtonElement =
      fixture.nativeElement.querySelectorAll('.panel .actions button')[1];
    refresh.focus();
    refresh.click();
    http.expectOne('/api/v1/vehicles/id').flush(vehicle);
    await fixture.whenStable();
    expect(document.activeElement).toBe(fixture.nativeElement.querySelector('h1'));
  });

  it('non sottrae il focus se l’utente si è spostato durante l’aggiornamento', async () => {
    const { fixture, component, http } = setup();
    http.expectOne('/api/v1/vehicles/id').flush(vehicle);
    await fixture.whenStable();
    component.reload();
    const link: HTMLAnchorElement = fixture.nativeElement.querySelector('a');
    link.focus();
    http.expectOne('/api/v1/vehicles/id').flush(vehicle);
    await fixture.whenStable();
    expect(document.activeElement).toBe(link);
  });

  it('annulla una lettura superata navigando tra dettagli', () => {
    const { fixture, http } = setup();
    const first = http.expectOne('/api/v1/vehicles/id');
    params.next(convertToParamMap({ id: 'next' }));
    expect(first.cancelled).toBe(true);
    http
      .expectOne('/api/v1/vehicles/next')
      .flush({ ...vehicle, id: 'next', externalCode: 'VAN-002' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('VAN-002');
    expect(fixture.nativeElement.textContent).not.toContain('VAN-001');
  });

  it('annulla una scrittura lasciando il dettaglio per non sovrascrivere un altro veicolo', () => {
    const { component, http } = setup();
    http.expectOne('/api/v1/vehicles/id').flush(vehicle);
    component.requestStatusChange();
    component.confirmStatusChange();
    const patch = http.expectOne('/api/v1/vehicles/id/status');
    params.next(convertToParamMap({ id: 'next' }));
    expect(patch.cancelled).toBe(true);
    http.expectOne('/api/v1/vehicles/next').flush({ ...vehicle, id: 'next' });
    expect(component.vehicle()!.id).toBe('next');
    expect(component.saving()).toBe(false);
  });
  it('non avvia più riletture equivalenti mentre la prima è pendente', () => {
    const { component, http } = setup();
    http.expectOne('/api/v1/vehicles/id').flush(vehicle);
    component.reload();
    component.reload();
    http.expectOne('/api/v1/vehicles/id').flush(vehicle);
  });
});
