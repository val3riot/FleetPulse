import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter, Router } from '@angular/router';
import { App } from '../../app';
import { routes } from '../../app.routes';
import { API_CONFIG, DEFAULT_API_CONFIG } from '../../core/api/api-config';
import { AlertStatus, AlertTargetStatus } from '../../core/api/api-models';
import { TEST_ALERT } from '../../testing/fleet-api.stub';
import { AlertDetail } from './alert-detail';

const path = `/api/v1/alerts/${TEST_ALERT.id}`;
describe('Dettaglio e transizioni alert HTTP', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter(routes),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: API_CONFIG,
          useValue: { ...DEFAULT_API_CONFIG, readRetryCount: 0, timeoutMs: 1000 },
        },
      ],
    }),
  );
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    vi.useRealTimers();
  });
  async function setup(state: AlertStatus = 'OPEN', errorStatus?: number) {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    const router = TestBed.inject(Router);
    await router.navigateByUrl(
      `/alerts/${TEST_ALERT.id}?status=OPEN&page=2&size=2&sort=createdAt,desc`,
    );
    const http = TestBed.inject(HttpTestingController);
    const read = http.expectOne(path);
    if (errorStatus)
      read.flush(
        { code: errorStatus === 404 ? 'ALERT_NOT_FOUND' : 'SERVICE_UNAVAILABLE' },
        { status: errorStatus, statusText: 'Error' },
      );
    else
      read.flush({
        ...TEST_ALERT,
        status: state,
        acknowledgedAt: state === 'ACKNOWLEDGED' ? '2026-11-08T20:01:00Z' : null,
        closedAt: state === 'CLOSED' ? '2026-11-08T20:02:00Z' : null,
      });
    await fixture.whenStable();
    const component = fixture.debugElement.query(By.directive(AlertDetail))
      .componentInstance as AlertDetail;
    const element = fixture.nativeElement as HTMLElement;
    const dialog = element.querySelector('dialog')!;
    Object.defineProperties(dialog, {
      showModal: { value: () => dialog.setAttribute('open', '') },
      close: { value: () => dialog.removeAttribute('open') },
    });
    return { fixture, component, http, element, dialog, router };
  }
  it.each([
    ['OPEN', 2],
    ['ACKNOWLEDGED', 1],
    ['CLOSED', 0],
  ] as const)('per %s mostra solo %i azioni', async (state, count) => {
    const { component, element } = await setup(state);
    expect(element.querySelectorAll('.panel .actions button')).toHaveLength(count);
    expect(element.querySelector('time')?.textContent).toBe('08/11/2026 20:00:00');
    if (state === 'OPEN') expect(element.textContent).toContain('Non ancora preso in carico');
    if (state === 'CLOSED') {
      component.requestChange('CLOSED');
      expect(component.pending()).toBeNull();
    }
  });
  it('conferma in una modale nativa e annulla senza inviare PATCH, ripristinando il focus', async () => {
    const { fixture, element, dialog, http } = await setup();
    const trigger = element.querySelector<HTMLButtonElement>('.panel .actions button')!;
    trigger.focus();
    trigger.click();
    await fixture.whenStable();
    expect(dialog.open).toBe(true);
    expect(document.activeElement?.textContent).toBe('Annulla');
    dialog.dispatchEvent(new Event('cancel', { cancelable: true }));
    await fixture.whenStable();
    expect(dialog.open).toBe(false);
    expect(document.activeElement).toBe(trigger);
    http.expectNone((request) => request.method === 'PATCH');
  });
  it.each([
    ['OPEN', 'ACKNOWLEDGED'],
    ['OPEN', 'CLOSED'],
    ['ACKNOWLEDGED', 'CLOSED'],
  ] as [AlertStatus, AlertTargetStatus][])(
    'esegue %s -> %s una sola volta e usa il DTO risposta',
    async (state, target) => {
      const { fixture, component, element, dialog, http } = await setup(state);
      const trigger = Array.from(
        element.querySelectorAll<HTMLButtonElement>('.panel .actions button'),
      ).find((button) => button.textContent?.includes(target === 'CLOSED' ? 'Chiudi' : 'Prendi'))!;
      trigger.click();
      await fixture.whenStable();
      component.confirm();
      component.confirm();
      fixture.detectChanges();
      const patch = http.expectOne((request) => request.url === path && request.method === 'PATCH');
      expect(patch.request.body).toEqual({ status: target });
      expect(component.alert()?.status).toBe(state);
      dialog.dispatchEvent(new Event('cancel', { cancelable: true }));
      fixture.detectChanges();
      expect(dialog.open).toBe(true);
      patch.flush({
        ...TEST_ALERT,
        status: target,
        acknowledgedAt:
          target === 'ACKNOWLEDGED' || state === 'ACKNOWLEDGED' ? '2026-11-08T20:01:02Z' : null,
        closedAt: target === 'CLOSED' ? '2026-11-08T20:03:04Z' : null,
      });
      await fixture.whenStable();
      expect(component.alert()?.status).toBe(target);
      expect(dialog.open).toBe(false);
      expect(component.success()).toContain(target === 'CLOSED' ? 'chiuso' : 'preso in carico');
      expect(document.activeElement?.textContent?.trim()).toBe('Aggiorna stato');
      expect(element.textContent).toContain(
        target === 'CLOSED' ? '08/11/2026 20:03:04' : '08/11/2026 20:01:02',
      );
      http.expectNone(path);
    },
  );
  it('su 409 rilegge senza ripetere PATCH e aggiorna azioni e stato', async () => {
    const { fixture, component, http, element } = await setup();
    component.requestChange('ACKNOWLEDGED');
    fixture.detectChanges();
    component.confirm();
    http
      .expectOne((request) => request.method === 'PATCH')
      .flush({ code: 'ALERT_STATUS_TRANSITION_CONFLICT' }, { status: 409, statusText: 'Conflict' });
    expect(component.needsVerification()).toBe(true);
    expect(component.actionsBlocked()).toBe(true);
    http
      .expectOne(path)
      .flush({ ...TEST_ALERT, status: 'CLOSED', closedAt: '2026-11-08T20:03:04Z' });
    await fixture.whenStable();
    expect(component.alert()?.status).toBe('CLOSED');
    expect(component.needsVerification()).toBe(false);
    expect(component.mutationError()?.code).toBe('ALERT_STATUS_TRANSITION_CONFLICT');
    expect(element.querySelectorAll('.panel .actions button')).toHaveLength(0);
    expect(component.success()).toContain('riletto');
    http.expectNone((request) => request.method === 'PATCH');
  });
  it('mantiene azioni bloccate se la rilettura dopo conflitto fallisce e recupera manualmente', async () => {
    const { fixture, component, http } = await setup();
    component.requestChange('ACKNOWLEDGED');
    fixture.detectChanges();
    component.confirm();
    http
      .expectOne((request) => request.method === 'PATCH')
      .flush({ code: 'ALERT_STATUS_TRANSITION_CONFLICT' }, { status: 409, statusText: 'Conflict' });
    http
      .expectOne(path)
      .flush({ code: 'SERVICE_UNAVAILABLE' }, { status: 503, statusText: 'Unavailable' });
    await fixture.whenStable();
    component.requestChange('CLOSED');
    expect(component.pending()).toBeNull();
    expect(component.needsVerification()).toBe(true);
    component.reload();
    http
      .expectOne(path)
      .flush({ ...TEST_ALERT, status: 'ACKNOWLEDGED', acknowledgedAt: '2026-11-08T20:01:00Z' });
    await fixture.whenStable();
    expect(component.actionsBlocked()).toBe(false);
    component.requestChange('CLOSED');
    expect(component.pending()).toBe('CLOSED');
  });
  it.each([0, 503, 500])(
    'non reinvia dopo errore scrittura %i: richiede verifica e poi permette un nuovo comando',
    async (status) => {
      const { fixture, component, http } = await setup();
      component.requestChange('CLOSED');
      fixture.detectChanges();
      component.confirm();
      const patch = http.expectOne((request) => request.method === 'PATCH');
      if (status === 0) patch.error(new ProgressEvent('error'));
      else
        patch.flush(
          { code: status === 503 ? 'SERVICE_UNAVAILABLE' : 'INTERNAL_ERROR' },
          { status, statusText: 'Error' },
        );
      await fixture.whenStable();
      expect(component.needsVerification()).toBe(true);
      expect(component.alert()?.status).toBe('OPEN');
      component.requestChange('CLOSED');
      expect(component.pending()).toBeNull();
      http.expectNone(path);
      component.reload();
      http
        .expectOne(path)
        .flush({ ...TEST_ALERT, status: 'CLOSED', closedAt: '2026-11-08T20:03:04Z' });
      await fixture.whenStable();
      expect(component.needsVerification()).toBe(false);
      expect(component.alert()?.status).toBe('CLOSED');
    },
  );
  it('tratta il timeout PATCH come esito incerto e non lo ripete', async () => {
    const { fixture, component, http } = await setup();
    vi.useFakeTimers();
    component.requestChange('CLOSED');
    fixture.detectChanges();
    component.confirm();
    const patch = http.expectOne((request) => request.method === 'PATCH');
    await vi.advanceTimersByTimeAsync(1000);
    fixture.detectChanges();
    expect(patch.cancelled).toBe(true);
    expect(component.mutationError()?.code).toBe('REQUEST_TIMEOUT');
    expect(component.needsVerification()).toBe(true);
    http.expectNone(path);
  });
  it.each([404, 503])('distingue errore lettura %i e consente recupero', async (status) => {
    const { fixture, component, http, element } = await setup('OPEN', status);
    expect(component.alert()).toBeNull();
    expect(component.error()?.status).toBe(status);
    expect(element.querySelectorAll('.panel .actions button')).toHaveLength(0);
    component.reload();
    http.expectOne(path).flush(TEST_ALERT);
    await fixture.whenStable();
    expect(component.error()).toBeNull();
  });
  it('gestisce 404 durante scrittura senza lasciare azioni attive', async () => {
    const { fixture, component, http } = await setup();
    component.requestChange('CLOSED');
    fixture.detectChanges();
    component.confirm();
    http
      .expectOne((request) => request.method === 'PATCH')
      .flush({ code: 'ALERT_NOT_FOUND' }, { status: 404, statusText: 'Not Found' });
    await fixture.whenStable();
    expect(component.alert()).toBeNull();
    expect(component.error()?.code).toBe('ALERT_NOT_FOUND');
  });
  it('valida UUID del dettaglio senza HTTP', async () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    await TestBed.inject(Router).navigateByUrl('/alerts/invalid');
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Identificativo alert non valido');
    TestBed.inject(HttpTestingController).expectNone((request) => request.url.includes('/alerts/'));
  });
  it('annulla letture e scritture all’uscita e non mescola due identità', async () => {
    const { fixture, component, router, http } = await setup();
    component.requestChange('CLOSED');
    fixture.detectChanges();
    component.confirm();
    const pending = http.expectOne((request) => request.method === 'PATCH');
    const other = 'f2607610-5100-4723-93d0-e6bbdcf00da1';
    await router.navigateByUrl('/alerts/' + other);
    expect(pending.cancelled).toBe(true);
    expect(component.alert()).toBeNull();
    const next = http.expectOne('/api/v1/alerts/' + other);
    await router.navigateByUrl('/non-esiste');
    await fixture.whenStable();
    expect(next.cancelled).toBe(true);
  });
  it('non esegue polling del dettaglio e una rilettura manuale preserva il focus spostato', async () => {
    const { fixture, component, element, http } = await setup();
    vi.useFakeTimers();
    await vi.advanceTimersByTimeAsync(15000);
    http.expectNone(path);
    vi.useRealTimers();
    component.reload();
    const back = element.querySelector<HTMLAnchorElement>('app-alert-detail > a')!;
    back.focus();
    http.expectOne(path).flush(TEST_ALERT);
    await fixture.whenStable();
    expect(document.activeElement).toBe(back);
  });
});
