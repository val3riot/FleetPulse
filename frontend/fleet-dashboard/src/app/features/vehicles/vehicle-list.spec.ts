import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { API_CONFIG, DEFAULT_API_CONFIG } from '../../core/api/api-config';
import { VehicleList } from './vehicle-list';

const vehicle = {
  id: 'id',
  externalCode: 'VAN-001',
  plate: 'FP001AA',
  status: 'ACTIVE',
  serviceIntervalKm: 15000,
  nextServiceAtKm: 90000,
  createdAt: '2026-08-01T10:00:00Z',
};
function page(content: object[] = [vehicle], index = 0, total = 1) {
  return {
    content,
    page: index,
    size: 20,
    totalElements: total,
    totalPages: Math.ceil(total / 20),
    first: index === 0,
    last: index + 1 >= Math.ceil(total / 20),
  };
}

describe('Elenco veicoli', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [VehicleList],
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
    const fixture = TestBed.createComponent(VehicleList);
    fixture.detectChanges();
    return {
      fixture,
      http: TestBed.inject(HttpTestingController),
      component: fixture.componentInstance,
    };
  }

  it('carica la prima pagina e presenta i dati con collegamento al dettaglio', () => {
    const { fixture, http } = setup();
    expect(fixture.nativeElement.textContent).toContain('Caricamento veicoli');
    const request = http.expectOne((r) => r.url === '/api/v1/vehicles');
    expect(request.request.params.get('page')).toBe('0');
    expect(request.request.params.get('size')).toBe('20');
    request.flush(page());
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('tbody').textContent).toContain('FP001AA');
    expect(fixture.nativeElement.querySelector('a[href="/vehicles/id"]').textContent).toContain(
      'VAN-001',
    );
    expect(fixture.nativeElement.textContent).toContain('Attivo');
    expect(fixture.nativeElement.textContent).not.toContain('Caricamento veicoli');
  });

  it('aggiorna la tabella dai campi HTML senza forzare il change detection', async () => {
    const { fixture, http } = setup();
    http.expectOne((r) => r.url === '/api/v1/vehicles').flush(page([vehicle], 0, 2));
    await fixture.whenStable();
    const input: HTMLInputElement = fixture.nativeElement.querySelector('#vehicle-query');
    input.value = ' VAN-001 ';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    const status: HTMLSelectElement = fixture.nativeElement.querySelector('#vehicle-status');
    status.value = 'ACTIVE';
    status.dispatchEvent(new Event('change', { bubbles: true }));
    fixture.nativeElement
      .querySelector('form')
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    const request = http.expectOne((r) => r.params.get('query') === 'VAN-001');
    expect(request.request.params.get('status')).toBe('ACTIVE');
    request.flush(page([{ ...vehicle, externalCode: 'RISULTATO', plate: 'NUOVA' }]));
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('tbody').textContent).toContain('RISULTATO');
    expect(fixture.nativeElement.querySelector('tbody').textContent).toContain('NUOVA');
    expect(fixture.nativeElement.textContent).not.toContain('Caricamento veicoli');
  });

  it('applica ricerca, stato e sort e torna alla prima pagina cambiando i filtri', () => {
    const { fixture, component, http } = setup();
    http.expectOne((r) => r.url === '/api/v1/vehicles').flush(page([vehicle], 0, 41));
    component.changePage(1);
    http.expectOne((r) => r.params.get('page') === '1').flush(page([vehicle], 1, 41));
    component.filters.setValue({ query: ' VAN-001 ', status: 'DISABLED', sort: 'plate,asc' });
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));
    const request = http.expectOne((r) => r.params.get('query') === 'VAN-001');
    expect(request.request.params.get('page')).toBe('0');
    expect(request.request.params.get('status')).toBe('DISABLED');
    expect(request.request.params.get('sort')).toBe('plate,asc');
    request.flush(page([], 0, 0));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Nessun veicolo corrisponde ai filtri.');
    component.resetFilters();
    const reset = http.expectOne((r) => r.url === '/api/v1/vehicles');
    expect(reset.request.params.has('query')).toBe(false);
    expect(reset.request.params.has('status')).toBe(false);
    reset.flush(page());
  });

  it('pagina i risultati con i filtri applicati e rispetta i limiti dei pulsanti', () => {
    const { fixture, component, http } = setup();
    http.expectOne((r) => r.url === '/api/v1/vehicles').flush(page([vehicle], 0, 21));
    fixture.detectChanges();
    const previous: HTMLButtonElement = fixture.nativeElement.querySelector('nav button');
    expect(previous.disabled).toBe(true);
    component.changePage(1);
    http.expectOne((r) => r.params.get('page') === '1').flush(page([vehicle], 1, 21));
    fixture.detectChanges();
    const buttons: NodeListOf<HTMLButtonElement> =
      fixture.nativeElement.querySelectorAll('nav button');
    expect(buttons[0].disabled).toBe(false);
    expect(buttons[1].disabled).toBe(true);
    expect(fixture.nativeElement.querySelector('nav').textContent).toContain('Pagina 2 di 2');
  });

  it('mostra errore e recupera usando Riprova senza convertirlo in elenco vuoto', () => {
    const { fixture, http } = setup();
    http
      .expectOne((r) => r.url === '/api/v1/vehicles')
      .flush({ code: 'SERVICE_UNAVAILABLE' }, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'temporaneamente',
    );
    expect(fixture.nativeElement.textContent).not.toContain('Nessun veicolo');
    const retry = [
      ...(fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>),
    ].find((b) => b.textContent === 'Riprova');
    retry!.click();
    http.expectOne((r) => r.url === '/api/v1/vehicles').flush(page());
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('table')).not.toBeNull();
  });

  it('porta il focus ai risultati dopo paginazione, ma non dopo modifica dei filtri', async () => {
    const { fixture, component, http } = setup();
    http.expectOne((r) => r.url === '/api/v1/vehicles').flush(page([vehicle], 0, 21));
    await fixture.whenStable();
    const next: HTMLButtonElement = fixture.nativeElement.querySelectorAll('nav button')[1];
    next.focus();
    next.click();
    http.expectOne((r) => r.params.get('page') === '1').flush(page([vehicle], 1, 21));
    await fixture.whenStable();
    expect(document.activeElement?.getAttribute('aria-label')).toBe('Risultati ricerca veicoli');
    const input: HTMLInputElement = fixture.nativeElement.querySelector('#vehicle-query');
    input.focus();
    component.applyFilters();
    http.expectOne((r) => r.params.get('page') === '0').flush(page());
    await fixture.whenStable();
    expect(document.activeElement).toBe(input);
  });

  it('non sottrae il focus a chi torna ai filtri mentre cambia pagina', async () => {
    const { fixture, component, http } = setup();
    http.expectOne((r) => r.url === '/api/v1/vehicles').flush(page([vehicle], 0, 21));
    await fixture.whenStable();
    component.changePage(1);
    const request = http.expectOne((r) => r.params.get('page') === '1');
    const input: HTMLInputElement = fixture.nativeElement.querySelector('#vehicle-query');
    input.focus();
    request.flush(page([vehicle], 1, 21));
    await fixture.whenStable();
    expect(document.activeElement).toBe(input);
  });

  it('mantiene la stessa regione live tra risultati, caricamento ed elenco vuoto', () => {
    const { fixture, component, http } = setup();
    const status: HTMLElement = fixture.nativeElement.querySelector('[role="status"]');
    expect(status.textContent).toContain('Caricamento veicoli');
    http.expectOne((r) => r.url === '/api/v1/vehicles').flush(page());
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="status"]')).toBe(status);
    expect(status.textContent).toContain('Veicoli trovati: 1');
    component.applyFilters();
    fixture.detectChanges();
    expect(status.textContent).toContain('Caricamento veicoli');
    http.expectOne((r) => r.url === '/api/v1/vehicles').flush(page([], 0, 0));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="status"]')).toBe(status);
    expect(status.textContent).toContain('Veicoli trovati: 0');
  });

  it('annulla la ricerca precedente e le richieste alla distruzione', () => {
    const { fixture, component, http } = setup();
    const first = http.expectOne((r) => r.url === '/api/v1/vehicles');
    component.filters.controls.query.setValue('nuovo');
    component.applyFilters();
    expect(first.cancelled).toBe(true);
    const next = http.expectOne((r) => r.params.get('query') === 'nuovo');
    fixture.destroy();
    expect(next.cancelled).toBe(true);
  });

  it('permette di tornare alla prima pagina se il totale è cambiato', () => {
    const { fixture, component, http } = setup();
    http.expectOne((r) => r.url === '/api/v1/vehicles').flush(page([vehicle], 0, 21));
    component.changePage(1);
    http.expectOne((r) => r.params.get('page') === '1').flush(page([], 1, 1));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Nessun veicolo in questa pagina.');
    const button = [
      ...(fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>),
    ].find((b) => b.textContent === 'Torna alla prima pagina');
    button!.click();
    http.expectOne((r) => r.params.get('page') === '0').flush(page());
  });
  it('Riprova non duplica la lettura e riporta il focus ai risultati preservando i filtri applicati', async () => {
    const { fixture, component, http } = setup();
    http.expectOne((request) => request.url === '/api/v1/vehicles').flush(page());
    component.filters.controls.query.setValue('VAN-001');
    component.applyFilters();
    http
      .expectOne((request) => request.params.get('query') === 'VAN-001')
      .flush({ code: 'SERVICE_UNAVAILABLE' }, { status: 503, statusText: 'Unavailable' });
    await fixture.whenStable();
    const retry = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('button'),
    ).find((button) => button.textContent?.trim() === 'Riprova')!;
    retry.focus();
    retry.click();
    component.reload();
    const read = http.expectOne((request) => request.params.get('query') === 'VAN-001');
    await fixture.whenStable();
    read.flush(page());
    await fixture.whenStable();
    expect(document.activeElement?.getAttribute('aria-label')).toBe('Risultati ricerca veicoli');
  });
  it('il recupero non sottrae il focus spostato sui filtri durante la lettura', async () => {
    const { fixture, component, http } = setup();
    http
      .expectOne((request) => request.url === '/api/v1/vehicles')
      .flush({ code: 'SERVICE_UNAVAILABLE' }, { status: 503, statusText: 'Unavailable' });
    component.reload();
    const read = http.expectOne((request) => request.url === '/api/v1/vehicles');
    const field = fixture.nativeElement.querySelector('#vehicle-query') as HTMLInputElement;
    field.focus();
    read.flush(page());
    await fixture.whenStable();
    expect(document.activeElement).toBe(field);
  });
});
