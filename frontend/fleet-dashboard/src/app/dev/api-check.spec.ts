import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { ApiCheck } from './api-check';
import { API_CONFIG, DEFAULT_API_CONFIG } from '../core/api/api-config';

describe('Verifica manuale API', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [ApiCheck],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: API_CONFIG, useValue: { ...DEFAULT_API_CONFIG, readRetryCount: 0 } },
      ],
    }),
  );
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('mostra caricamento, impedisce invii concorrenti e visualizza la risposta', async () => {
    const fixture = TestBed.createComponent(ApiCheck);
    fixture.detectChanges();
    const button: HTMLButtonElement = fixture.nativeElement.querySelector('button');
    button.click();
    fixture.detectChanges();
    expect(button.disabled).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Caricamento');
    fixture.componentInstance.loadDashboard();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/v1/dashboard')
      .flush({ totalVehicles: 3 });
    await fixture.whenStable();
    expect(button.disabled).toBe(false);
    expect(fixture.nativeElement.querySelector('pre').textContent).toContain('3');
  });

  it('mostra errori localizzati e dettagli del campo, poi recupera al nuovo invio', async () => {
    const fixture = TestBed.createComponent(ApiCheck);
    fixture.detectChanges();
    await fixture.whenStable();
    const input: HTMLInputElement = fixture.nativeElement.querySelector('input');
    input.value = 'invalid';
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    fixture.detectChanges();
    const form: HTMLFormElement = fixture.nativeElement.querySelector('form');
    form.dispatchEvent(new Event('submit', { cancelable: true }));
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/vehicles/invalid').flush(
      {
        code: 'REQUEST_INVALID',
        message: 'Diagnostica backend',
        details: [{ field: 'plate', message: 'obbligatoria' }],
      },
      { status: 400, statusText: 'Bad request' },
    );
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'Controlla i dati inseriti.',
    );
    expect(fixture.nativeElement.querySelector('li').textContent).toContain('plate: obbligatoria');
    expect(fixture.nativeElement.textContent).not.toContain('Diagnostica backend');
    fixture.componentInstance.loadDashboard();
    http.expectOne('/api/v1/dashboard').flush({ totalVehicles: 0 });
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
  });

  it('non invia un identificativo vuoto e annulla le letture alla distruzione', () => {
    const fixture = TestBed.createComponent(ApiCheck);
    fixture.detectChanges();
    fixture.componentInstance.loadVehicle();
    TestBed.inject(HttpTestingController).expectNone((r) => r.url.includes('/vehicles'));
    fixture.componentInstance.loadDashboard();
    const request = TestBed.inject(HttpTestingController).expectOne('/api/v1/dashboard');
    fixture.destroy();
    expect(request.cancelled).toBe(true);
  });
});
