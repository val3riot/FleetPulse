import { of } from 'rxjs';
import { createFleetApiStub, TEST_ALERT, TEST_DASHBOARD } from './testing/fleet-api.stub';
import { FleetApi } from './core/api/fleet-api';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { Title } from '@angular/platform-browser';
import { App } from './app';
import { routes } from './app.routes';
import { DEV_ROUTES as productionDevRoutes } from './dev/dev.routes.production';

describe('Navigazione FleetPulse', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter(routes),
        {
          provide: FleetApi,
          useValue: createFleetApiStub(),
        },
      ],
    }),
  );

  async function open(url: string) {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    await TestBed.inject(Router).navigateByUrl(url);
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  it('apre la dashboard dalla radice', async () => {
    const fixture = await open('/');
    expect(TestBed.inject(Router).url).toBe('/dashboard');
    expect(fixture.nativeElement.querySelector('h1').textContent).toBe('Dashboard');
  });

  it.each([
    ['/dashboard', 'Dashboard', ''],
    ['/vehicles', 'Veicoli', ''],
    ['/alerts', 'Alert', ''],
  ])('apre %s e aggiorna titolo e navigazione', async (url, heading, ticket) => {
    const fixture = await open(url);
    const element: HTMLElement = fixture.nativeElement;
    expect(element.querySelector('h1')?.textContent).toBe(heading);
    if (ticket) expect(element.textContent).toContain(ticket);
    expect(TestBed.inject(Title).getTitle()).toBe(`${heading} | FleetPulse`);
    expect(element.querySelector('[aria-current="page"]')?.textContent).toBe(heading);
  });

  it('naviga usando i link e aggiorna il contenuto', async () => {
    const fixture = await open('/dashboard');
    const link: HTMLAnchorElement = fixture.nativeElement.querySelector('a[href="/vehicles"]');
    link.click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(TestBed.inject(Router).url).toBe('/vehicles');
    expect(fixture.nativeElement.querySelector('h1').textContent).toBe('Veicoli');
  });

  it('mostra la pagina non trovata e permette il ritorno alla dashboard', async () => {
    const fixture = await open('/non-esiste');
    expect(fixture.nativeElement.querySelector('h1').textContent).toBe('Pagina non trovata');
    const link: HTMLAnchorElement = fixture.nativeElement.querySelector('main a');
    link.click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(TestBed.inject(Router).url).toBe('/dashboard');
  });

  it('mantiene lo stesso layout e header durante la navigazione di tutte le pagine', async () => {
    const fixture = await open('/dashboard');
    const element: HTMLElement = fixture.nativeElement;
    const header = element.querySelector('app-header');
    const main = element.querySelector('main');

    for (const url of [
      '/vehicles',
      '/vehicles/new',
      '/vehicles/id',
      '/alerts',
      '/non-esiste',
      '/dashboard',
    ]) {
      await TestBed.inject(Router).navigateByUrl(url);
      await fixture.whenStable();
      fixture.detectChanges();
      expect(element.querySelector('app-header')).toBe(header);
      expect(element.querySelector('main')).toBe(main);
      expect(element.querySelectorAll('header')).toHaveLength(1);
      expect(element.querySelectorAll('main')).toHaveLength(1);
      expect(main?.querySelector('h1')).not.toBeNull();
      expect(element.querySelectorAll('[aria-current="page"]')).toHaveLength(
        url === '/non-esiste' ? 0 : 1,
      );
    }
  });

  it('porta il focus al contenuto dopo una navigazione di pagina', async () => {
    const fixture = await open('/dashboard');
    const link: HTMLAnchorElement = fixture.nativeElement.querySelector('a[href="/vehicles"]');
    link.focus();
    link.click();
    await fixture.whenStable();
    expect(document.activeElement).toBe(fixture.nativeElement.querySelector('main'));
    expect(document.activeElement?.textContent).toContain('Veicoli');
  });

  it('mantiene il focus quando cambiano solo i parametri query', async () => {
    const fixture = await open('/vehicles');
    const input: HTMLInputElement = fixture.nativeElement.querySelector('#vehicle-query');
    input.focus();
    await TestBed.inject(Router).navigateByUrl('/vehicles?page=1');
    await fixture.whenStable();
    expect(document.activeElement).toBe(input);
  });

  it('rispetta un frammento esplicito senza forzare il focus sul main', async () => {
    const fixture = await open('/dashboard');
    const link: HTMLAnchorElement = fixture.nativeElement.querySelector('a[href="/vehicles"]');
    link.focus();
    await TestBed.inject(Router).navigateByUrl('/vehicles#main-content');
    await fixture.whenStable();
    expect(document.activeElement).toBe(link);
  });

  it('esclude le rotte diagnostiche dalla configurazione di produzione', () => {
    expect(productionDevRoutes).toEqual([]);
  });

  it('offre un collegamento per saltare la navigazione', async () => {
    const fixture = await open('/dashboard');
    expect(fixture.nativeElement.querySelector('.skip-link').getAttribute('href')).toBe(
      '#main-content',
    );
    expect(fixture.nativeElement.querySelector('main').id).toBe('main-content');
  });
  it('il link dashboard apre il dettaglio dell’alert con un UUID valido', async () => {
    TestBed.overrideProvider(FleetApi, {
      useValue: {
        ...createFleetApiStub(),
        getDashboard: () => of({ ...TEST_DASHBOARD, relevantAlerts: [TEST_ALERT] }),
      },
    });
    const fixture = await open('/dashboard');
    fixture.nativeElement.querySelector(`a[href="/alerts/${TEST_ALERT.id}"]`).click();
    await fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/alerts/' + TEST_ALERT.id);
    expect(fixture.nativeElement.querySelector('h1').textContent).toBe('Dettaglio alert');
    expect(fixture.nativeElement.textContent).toContain(TEST_ALERT.description);
  });
  it('il link dal veicolo apre la lista alert con il filtro del veicolo applicato', async () => {
    const fixture = await open('/vehicles/' + TEST_ALERT.vehicleId);
    fixture.nativeElement.querySelector('a[href^="/alerts?vehicleId="]').click();
    await fixture.whenStable();
    const params = TestBed.inject(Router).parseUrl(TestBed.inject(Router).url).queryParams;
    expect(params['vehicleId']).toBe(TEST_ALERT.vehicleId);
    expect(params['page']).toBe('0');
    const input = fixture.nativeElement.querySelector('#alert-vehicleId') as HTMLInputElement;
    expect(input.value).toBe(TEST_ALERT.vehicleId);
  });
});
