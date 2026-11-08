import axe from 'axe-core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { API_CONFIG, DEFAULT_API_CONFIG } from '../../core/api/api-config';
import { VehicleCreate } from './vehicle-create';

const values = {
  externalCode: 'VAN-001',
  plate: 'FP001AA',
  serviceIntervalKm: 15000,
  nextServiceAtKm: 0,
};

describe('Registrazione veicolo', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [VehicleCreate],
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
    const fixture = TestBed.createComponent(VehicleCreate);
    fixture.detectChanges();
    return {
      fixture,
      component: fixture.componentInstance,
      http: TestBed.inject(HttpTestingController),
    };
  }

  it('identifica i quattro campi obbligatori e rende visibili i limiti di lunghezza', () => {
    const { fixture } = setup();
    const element: HTMLElement = fixture.nativeElement;
    expect(element.querySelectorAll('label span[aria-hidden="true"]')).toHaveLength(4);
    expect(element.querySelectorAll('input[required]')).toHaveLength(4);
    expect(element.querySelector('#external-code')?.getAttribute('maxlength')).toBe('64');
    expect(element.querySelector('#plate')?.getAttribute('maxlength')).toBe('16');
    expect(element.querySelector('#external-code-hint')?.textContent).toContain('64');
    expect(element.querySelector('#plate-hint')?.textContent).toContain('16');
  });

  it('accetta esattamente i limiti massimi, incluso zero come prossima manutenzione', () => {
    const { component, http } = setup();
    component.form.setValue({
      externalCode: 'x'.repeat(64),
      plate: 'p'.repeat(16),
      serviceIntervalKm: 2147483647,
      nextServiceAtKm: Number.MAX_SAFE_INTEGER,
    });
    expect(component.form.valid).toBe(true);
    component.form.controls.nextServiceAtKm.setValue(0);
    component.submit();
    const request = http.expectOne('/api/v1/vehicles');
    expect(request.request.body.externalCode).toHaveLength(64);
    expect(request.request.body.plate).toHaveLength(16);
    expect(request.request.body.nextServiceAtKm).toBe(0);
    request.flush({ code: 'REQUEST_INVALID' }, { status: 400, statusText: 'Bad request' });
  });

  it('valida i campi obbligatori prima di inviare', () => {
    const { fixture, http } = setup();
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Campo obbligatorio.');
    expect(fixture.nativeElement.querySelectorAll('[aria-invalid="true"]')).toHaveLength(4);
    expect(fixture.nativeElement.querySelector('[formControlName="status"]')).toBeNull();
    http.expectNone((r) => r.method === 'POST');
  });

  it('porta il focus al primo errore locale con istruzioni associate', async () => {
    const { fixture, http } = setup();
    const button: HTMLButtonElement = fixture.nativeElement.querySelector('button[type="submit"]');
    button.focus();
    button.click();
    await fixture.whenStable();
    const field: HTMLInputElement = fixture.nativeElement.querySelector('#external-code');
    expect(document.activeElement).toBe(field);
    expect(field.getAttribute('aria-invalid')).toBe('true');
    expect(field.getAttribute('aria-describedby')).toContain('external-code-error');
    expect(fixture.nativeElement.querySelector('#external-code-error').textContent).toContain(
      'Campo obbligatorio',
    );
    http.expectNone((r) => r.method === 'POST');
  });

  it('annuncia il salvataggio e porta il focus sul campo in conflitto', async () => {
    const { fixture, component, http } = setup();
    const status = fixture.nativeElement.querySelector('.live-status');
    component.form.setValue(values);
    component.submit();
    fixture.detectChanges();
    expect(status.textContent).toContain('Registrazione in corso');
    http
      .expectOne('/api/v1/vehicles')
      .flush({ code: 'VEHICLE_PLATE_CONFLICT' }, { status: 409, statusText: 'Conflict' });
    await fixture.whenStable();
    const field: HTMLInputElement = fixture.nativeElement.querySelector('#plate');
    expect(document.activeElement).toBe(field);
    expect(field.disabled).toBe(false);
    expect(field.getAttribute('aria-invalid')).toBe('true');
    expect(fixture.nativeElement.querySelector('.live-status')).toBe(status);
    expect(status.textContent).toBe('');
  });

  it.each([
    ['externalCode', '   '],
    ['externalCode', 'x'.repeat(65)],
    ['plate', 'x'.repeat(17)],
    ['serviceIntervalKm', 0],
    ['serviceIntervalKm', 1.5],
    ['serviceIntervalKm', 2147483648],
    ['nextServiceAtKm', -1],
    ['nextServiceAtKm', 0.5],
    ['nextServiceAtKm', Number.MAX_SAFE_INTEGER + 1],
  ])('rifiuta %s non valido (%s)', (field, value) => {
    const { component, http } = setup();
    component.form.setValue(values);
    component.form.get(field as string)!.setValue(value);
    component.submit();
    expect(component.form.invalid).toBe(true);
    http.expectNone((r) => r.method === 'POST');
  });

  it('invia i quattro campi senza status, impedisce doppi invii e apre il dettaglio', () => {
    const { fixture, component, http } = setup();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    component.form.setValue({ ...values, externalCode: ' VAN-001 ', plate: ' FP001AA ' });
    component.submit();
    component.submit();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('button').disabled).toBe(true);
    const request = http.expectOne('/api/v1/vehicles');
    expect(request.request.body).toEqual(values);
    expect(component.form.disabled).toBe(true);
    request.flush(
      { ...values, id: 'created', status: 'ACTIVE', createdAt: '2026-08-01T10:00:00Z' },
      { status: 201, statusText: 'Created' },
    );
    expect(navigate).toHaveBeenCalledWith(['/vehicles', 'created']);
    expect(component.saving()).toBe(false);
  });

  it.each([
    ['VEHICLE_PLATE_CONFLICT', 'plate'],
    ['VEHICLE_EXTERNAL_CODE_CONFLICT', 'externalCode'],
    ['REQUEST_INVALID', 'serviceIntervalKm'],
  ])('associa %s al campo %s e consente correzione e nuovo invio', (code, field) => {
    const { fixture, component, http } = setup();
    component.form.setValue(values);
    component.submit();
    http
      .expectOne('/api/v1/vehicles')
      .flush(
        { code, details: [{ field, message: 'Valore non valido' }] },
        { status: code === 'REQUEST_INVALID' ? 400 : 409, statusText: 'Error' },
      );
    fixture.detectChanges();
    expect(component.form.get(field)!.hasError('api')).toBe(true);
    expect(fixture.nativeElement.querySelectorAll('[aria-invalid="true"]')).toHaveLength(1);
    expect(component.saving()).toBe(false);
    expect(component.form.enabled).toBe(true);
    const control = component.form.get(field)!;
    control.setValue(field === 'serviceIntervalKm' ? 20000 : 'CORRETTO');
    expect(control.hasError('api')).toBe(false);
    component.submit();
    http
      .expectOne('/api/v1/vehicles')
      .flush({ code: 'REQUEST_MALFORMED_JSON' }, { status: 400, statusText: 'Bad request' });
  });

  it('conserva i valori dopo errore generale e presenta dettagli di campi sconosciuti', () => {
    const { fixture, component, http } = setup();
    component.form.setValue(values);
    component.submit();
    http.expectOne('/api/v1/vehicles').flush(
      {
        code: 'REQUEST_INVALID',
        details: [{ field: 'unknown', message: 'Verifica la richiesta' }],
      },
      { status: 400, statusText: 'Bad request' },
    );
    fixture.detectChanges();
    expect(component.form.getRawValue()).toEqual(values);
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'Verifica la richiesta',
    );
  });

  it('dopo errore di rete non ripete una creazione ambigua e invita a verificare l’elenco', () => {
    const { fixture, component, http } = setup();
    component.form.setValue(values);
    component.submit();
    http.expectOne('/api/v1/vehicles').error(new ProgressEvent('error'));
    component.submit();
    http.expectNone('/api/v1/vehicles');
    fixture.detectChanges();
    expect(component.uncertain()).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Cerca il codice o la targa');
    expect(fixture.nativeElement.querySelector('button').disabled).toBe(true);
  });

  it('annulla una richiesta in corso quando si lascia la pagina', () => {
    const { fixture, component, http } = setup();
    component.form.setValue(values);
    component.submit();
    const request = http.expectOne('/api/v1/vehicles');
    fixture.destroy();
    expect(request.cancelled).toBe(true);
  });
  it('porta il focus al riepilogo generale senza perdere il form e non espone diagnostica backend', async () => {
    const { fixture, component, http } = setup();
    component.form.setValue(values);
    const button = fixture.nativeElement.querySelector(
      'button[type="submit"]',
    ) as HTMLButtonElement;
    button.focus();
    button.click();
    http
      .expectOne('/api/v1/vehicles')
      .flush(
        { code: 'FUTURE_ERROR', message: '<script>diagnostica privata</script>' },
        { status: 503, statusText: 'Unavailable' },
      );
    await fixture.whenStable();
    expect(document.activeElement?.getAttribute('aria-label')).toBe('Registrazione non completata');
    expect(fixture.nativeElement.textContent).not.toContain('diagnostica privata');
    expect(component.form.getRawValue()).toEqual(values);
    expect(component.uncertain()).toBe(true);
    const report = await axe.run(fixture.nativeElement, {
      runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] },
      rules: { 'color-contrast': { enabled: false } },
    });
    expect(report.violations.map((violation) => violation.id)).toEqual([]);
    button.click();
    http.expectNone((request) => request.method === 'POST');
  });
  it('non sottrae il focus a chi ha lasciato il form prima dell’errore generale', async () => {
    const { fixture, component, http } = setup();
    component.form.setValue(values);
    component.submit();
    const back = fixture.nativeElement.querySelector('a') as HTMLAnchorElement;
    back.focus();
    http
      .expectOne('/api/v1/vehicles')
      .flush({ code: 'REQUEST_INVALID' }, { status: 400, statusText: 'Bad Request' });
    await fixture.whenStable();
    expect(document.activeElement).toBe(back);
    expect(component.form.getRawValue()).toEqual(values);
  });
});
