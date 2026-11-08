import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { ApiError } from '../api/api-error';
import { POLLING_CONFIG } from './polling-config';
import { createPollingResource } from './polling-resource';

const sources: Subject<number>[] = [];
const request = vi.fn(() => {
  const source = new Subject<number>();
  sources.push(source);
  return source.asObservable();
});

@Component({ template: '' })
class Host {
  readonly key = signal('first');
  readonly suspended = signal(false);
  readonly resource = createPollingResource(request, { key: this.key, suspended: this.suspended });
}

describe('Polling dopo conclusione della lettura', () => {
  let fixture: ComponentFixture<Host> | undefined;
  let hidden = false;
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-11-08T10:00:00Z'));
    sources.length = 0;
    request.mockClear();
    hidden = false;
    vi.spyOn(document, 'hidden', 'get').mockImplementation(() => hidden);
    TestBed.configureTestingModule({ imports: [Host] });
  });
  afterEach(() => {
    fixture?.destroy();
    fixture = undefined;
    vi.restoreAllMocks();
    vi.useRealTimers();
  });
  function setup() {
    fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    return fixture.componentInstance;
  }
  function visibility(value: boolean) {
    hidden = value;
    document.dispatchEvent(new Event('visibilitychange'));
    fixture!.detectChanges();
  }

  it('legge subito e attende 5 secondi dalla risposta, senza sovrapporre letture lente', async () => {
    const host = setup();
    expect(request).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(12000);
    expect(request).toHaveBeenCalledTimes(1);
    host.resource.refresh();
    expect(request).toHaveBeenCalledTimes(1);
    sources[0].next(1);
    expect(host.resource.data()).toBe(1);
    await vi.advanceTimersByTimeAsync(4999);
    expect(request).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(request).toHaveBeenCalledTimes(2);
  });

  it('supporta un intervallo configurato', async () => {
    TestBed.overrideProvider(POLLING_CONFIG, { useValue: { intervalMs: 3000 } });
    setup();
    sources[0].next(1);
    await vi.advanceTimersByTimeAsync(2999);
    expect(request).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(request).toHaveBeenCalledTimes(2);
  });

  it.each([
    0,
    -1,
    1.5,
    NaN,
    Infinity,
    2147483648,
    Number.MAX_SAFE_INTEGER,
    Number.MAX_SAFE_INTEGER + 1,
  ])('rifiuta intervallo %s', (intervalMs) => {
    TestBed.overrideProvider(POLLING_CONFIG, { useValue: { intervalMs } });
    expect(() => setup()).toThrow('Polling intervalMs');
    expect(request).not.toHaveBeenCalled();
  });

  it('pausa senza annullare la lettura corrente e riprende subito', async () => {
    const host = setup();
    host.resource.togglePause();
    fixture!.detectChanges();
    expect(sources[0].observers).toHaveLength(1);
    sources[0].next(1);
    await vi.advanceTimersByTimeAsync(30000);
    expect(request).toHaveBeenCalledTimes(1);
    host.resource.togglePause();
    fixture!.detectChanges();
    expect(request).toHaveBeenCalledTimes(2);
  });

  it('aggiorna manualmente anche in pausa senza accodare o sovrapporre richieste', async () => {
    const host = setup();
    sources[0].next(1);
    host.resource.togglePause();
    fixture!.detectChanges();
    host.resource.refresh();
    host.resource.refresh();
    expect(request).toHaveBeenCalledTimes(2);
    sources[1].next(2);
    await vi.advanceTimersByTimeAsync(30000);
    expect(request).toHaveBeenCalledTimes(2);
  });

  it('un refresh manuale riparte da una nuova attesa completa dopo la risposta', async () => {
    const host = setup();
    sources[0].next(1);
    await vi.advanceTimersByTimeAsync(4000);
    host.resource.refresh();
    await vi.advanceTimersByTimeAsync(4000);
    expect(request).toHaveBeenCalledTimes(2);
    sources[1].next(2);
    await vi.advanceTimersByTimeAsync(4999);
    expect(request).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(request).toHaveBeenCalledTimes(3);
  });

  it('sospende nelle schede nascoste e rilegge al ritorno senza ignorare la pausa', async () => {
    const host = setup();
    sources[0].next(1);
    visibility(true);
    await vi.advanceTimersByTimeAsync(30000);
    expect(request).toHaveBeenCalledTimes(1);
    visibility(false);
    expect(request).toHaveBeenCalledTimes(2);
    sources[1].next(2);
    host.resource.togglePause();
    fixture!.detectChanges();
    visibility(true);
    visibility(false);
    expect(request).toHaveBeenCalledTimes(2);
  });

  it('non avvia letture se la pagina nasce nascosta, fino al ritorno visibile', () => {
    hidden = true;
    setup();
    expect(request).not.toHaveBeenCalled();
    visibility(false);
    expect(request).toHaveBeenCalledOnce();
  });

  it('non sovrappone una lettura al ritorno visibile se la precedente è ancora in corso', () => {
    setup();
    visibility(true);
    visibility(false);
    expect(request).toHaveBeenCalledTimes(1);
  });

  it('sospende anche il refresh manuale quando la sezione è bloccata da una modale', async () => {
    const host = setup();
    sources[0].next(1);
    host.suspended.set(true);
    fixture!.detectChanges();
    host.resource.refresh();
    await vi.advanceTimersByTimeAsync(30000);
    expect(request).toHaveBeenCalledTimes(1);
    host.suspended.set(false);
    fixture!.detectChanges();
    expect(request).toHaveBeenCalledTimes(2);
  });

  it('conserva snapshot e data dell’ultimo successo su errore temporaneo e recupera', async () => {
    const host = setup();
    sources[0].next(1);
    const time = host.resource.lastSuccessfulAt()!.getTime();
    await vi.advanceTimersByTimeAsync(5000);
    sources[1].error(new ApiError('SERVICE_UNAVAILABLE', 503, 'Non disponibile', true));
    expect(host.resource.data()).toBe(1);
    expect(host.resource.lastSuccessfulAt()!.getTime()).toBe(time);
    expect(host.resource.error()?.code).toBe('SERVICE_UNAVAILABLE');
    await vi.advanceTimersByTimeAsync(5000);
    sources[2].next(3);
    expect(host.resource.data()).toBe(3);
    expect(host.resource.error()).toBeNull();
    expect(host.resource.lastSuccessfulAt()!.getTime()).toBeGreaterThan(time);
  });

  it('ferma gli errori non temporanei senza loop, ma consente una rilettura esplicita', async () => {
    const host = setup();
    sources[0].error(new ApiError('INTERNAL_ERROR', 500, 'Errore interno', false));
    await vi.advanceTimersByTimeAsync(30000);
    expect(request).toHaveBeenCalledTimes(1);
    expect(host.resource.stopped()).toBe(true);
    host.resource.refresh();
    expect(request).toHaveBeenCalledTimes(2);
    sources[1].next(2);
    expect(host.resource.stopped()).toBe(false);
  });

  it('cambia risorsa senza conservare dati, timer o risposte del veicolo precedente', async () => {
    const host = setup();
    sources[0].next(1);
    await vi.advanceTimersByTimeAsync(5000);
    const previous = sources[1];
    host.key.set('second');
    fixture!.detectChanges();
    expect(previous.observers).toHaveLength(0);
    expect(host.resource.data()).toBeNull();
    expect(host.resource.lastSuccessfulAt()).toBeNull();
    previous.next(999);
    expect(host.resource.data()).toBeNull();
    sources[2].next(2);
    expect(host.resource.data()).toBe(2);
  });

  it('alla distruzione annulla letture e listener, senza nuovi cicli', async () => {
    const host = setup();
    fixture!.destroy();
    expect(sources[0].observers).toHaveLength(0);
    visibility(true);
    host.resource.refresh();
    await vi.advanceTimersByTimeAsync(30000);
    expect(request).toHaveBeenCalledTimes(1);
  });

  it('alla distruzione annulla anche il timer già pianificato', async () => {
    setup();
    sources[0].next(1);
    fixture!.destroy();
    await vi.advanceTimersByTimeAsync(30000);
    expect(request).toHaveBeenCalledTimes(1);
  });
});
