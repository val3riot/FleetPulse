import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { Title } from '@angular/platform-browser';
import { App } from './app';
import { routes } from './app.routes';

describe('Navigazione FleetPulse', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({ imports: [App], providers: [provideRouter(routes)] }),
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
    ['/dashboard', 'Dashboard', 'FP-063'],
    ['/vehicles', 'Veicoli', 'FP-062'],
    ['/alerts', 'Alert', 'FP-065'],
  ])('apre %s e indica la funzionalità in preparazione', async (url, heading, ticket) => {
    const fixture = await open(url);
    const element: HTMLElement = fixture.nativeElement;
    expect(element.querySelector('h1')?.textContent).toBe(heading);
    expect(element.textContent).toContain(ticket);
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

    for (const url of ['/vehicles', '/alerts', '/non-esiste', '/dashboard']) {
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

  it('offre un collegamento per saltare la navigazione', async () => {
    const fixture = await open('/dashboard');
    expect(fixture.nativeElement.querySelector('.skip-link').getAttribute('href')).toBe(
      '#main-content',
    );
    expect(fixture.nativeElement.querySelector('main').id).toBe('main-content');
  });
});
