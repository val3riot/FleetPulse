import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { API_CONFIG, DEFAULT_API_CONFIG } from '../../core/api/api-config';
import { TEST_STATE, TEST_VEHICLE } from '../../testing/fleet-api.stub';
import { VehicleDetail } from './vehicle-detail';
import { VehicleState } from './vehicle-state';

describe('Integrazione anagrafica e telemetria', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [VehicleDetail],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ id: 'id' })) } },
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: API_CONFIG, useValue: { ...DEFAULT_API_CONFIG, readRetryCount: 0 } },
      ],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  function setup() {
    const fixture = TestBed.createComponent(VehicleDetail);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('/api/v1/vehicles/id').flush(TEST_VEHICLE);
    fixture.detectChanges();
    const dialog: HTMLDialogElement = fixture.nativeElement.querySelector('dialog');
    Object.defineProperties(dialog, {
      showModal: { value: () => dialog.setAttribute('open', '') },
      close: { value: () => dialog.removeAttribute('open') },
    });
    return { fixture, http, component: fixture.componentInstance };
  }

  it('un errore telemetrico non blocca anagrafica e azione di cambio stato', () => {
    const { fixture, http } = setup();
    http
      .expectOne('/api/v1/vehicles/id/state')
      .flush({ code: 'SERVICE_UNAVAILABLE' }, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.panel h2').textContent).toContain('VAN-001');
    expect(fixture.nativeElement.querySelector('.panel .actions button').disabled).toBe(false);
    expect(fixture.nativeElement.querySelector('app-vehicle-state [role="alert"]')).not.toBeNull();
  });

  it('la modale sospende la telemetria e il successo della PATCH mantiene la stessa sezione', () => {
    const { fixture, component, http } = setup();
    http.expectOne('/api/v1/vehicles/id/state').flush(TEST_STATE);
    fixture.detectChanges();
    const child = fixture.debugElement.query(By.directive(VehicleState))
      .componentInstance as VehicleState;
    component.requestStatusChange();
    fixture.detectChanges();
    expect(child.resource.suspended()).toBe(true);
    child.resource.refresh();
    http.expectNone('/api/v1/vehicles/id/state');
    component.confirmStatusChange();
    http.expectOne('/api/v1/vehicles/id/status').flush({ ...TEST_VEHICLE, status: 'DISABLED' });
    fixture.detectChanges();
    expect(fixture.debugElement.query(By.directive(VehicleState)).componentInstance).toBe(child);
    expect(child.resource.suspended()).toBe(false);
    http.expectOne('/api/v1/vehicles/id/state').flush(TEST_STATE);
    expect(component.vehicle()?.status).toBe('DISABLED');
  });
});
