import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import axe from 'axe-core';
import { of, throwError } from 'rxjs';
import { App } from './app';
import { routes } from './app.routes';
import { ApiError } from './core/api/api-error';
import { FleetApi } from './core/api/fleet-api';
import { createFleetApiStub } from './testing/fleet-api.stub';

describe('Accessibilità semantica delle pagine', () => {
  let api: ReturnType<typeof createFleetApiStub>;

  beforeEach(() => {
    document.documentElement.lang = 'it';
    api = createFleetApiStub();
    TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter(routes), { provide: FleetApi, useValue: api }],
    });
  });

  it.each([
    ['/dashboard', 'dashboard'],
    ['/vehicles', 'elenco'],
    ['/vehicles', 'elenco vuoto'],
    ['/vehicles', 'errore API'],
    ['/vehicles/new', 'registrazione'],
    ['/vehicles/new', 'registrazione invalida'],
    ['/vehicles/id', 'dettaglio'],
    ['/vehicles/id', 'modale'],
    ['/non-esiste', 'pagina non trovata'],
  ])('verifica %s nello stato %s', async (url, state) => {
    if (state === 'elenco vuoto') {
      api.getVehicles.mockReturnValue(
        of({
          content: [],
          page: 0,
          size: 20,
          totalElements: 0,
          totalPages: 0,
          first: true,
          last: true,
        }),
      );
    }
    if (state === 'errore API') {
      api.getVehicles.mockReturnValue(
        throwError(
          () =>
            new ApiError(
              'SERVICE_UNAVAILABLE',
              503,
              'Servizio temporaneamente non disponibile.',
              true,
            ),
        ),
      );
    }
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    await TestBed.inject(Router).navigateByUrl(url);
    await fixture.whenStable();

    if (state === 'registrazione invalida') {
      fixture.nativeElement.querySelector('form button[type="submit"]').click();
      await fixture.whenStable();
    }
    if (state === 'modale') {
      const dialog: HTMLDialogElement = fixture.nativeElement.querySelector('dialog');
      // jsdom lacks the native dialog API. Keyboard containment is a browser acceptance check.
      Object.defineProperties(dialog, {
        showModal: { value: () => dialog.setAttribute('open', '') },
        close: { value: () => dialog.removeAttribute('open') },
      });
      fixture.nativeElement.querySelector('.panel .actions button').click();
      await fixture.whenStable();
    }

    const report = await axe.run(document.documentElement, {
      runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] },
      // Layout/color rendering is unavailable in jsdom. Palette contrast has a separate gate.
      rules: { 'color-contrast': { enabled: false } },
    });
    expect(
      report.violations.map(({ id, nodes }) => ({ id, targets: nodes.map((node) => node.target) })),
    ).toEqual([]);
    fixture.destroy();
  });
});
