import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import axe from 'axe-core';
import { of, throwError } from 'rxjs';
import { App } from './app';
import { routes } from './app.routes';
import { ApiError } from './core/api/api-error';
import { FleetApi } from './core/api/fleet-api';
import { createFleetApiStub, TEST_ALERT } from './testing/fleet-api.stub';

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
    ['/dashboard', 'dashboard con alert'],
    ['/dashboard', 'dashboard in errore'],
    ['/dashboard', 'dashboard in pausa'],
    ['/vehicles', 'elenco'],
    ['/vehicles', 'elenco vuoto'],
    ['/vehicles', 'errore API'],
    ['/vehicles/new', 'registrazione'],
    ['/vehicles/new', 'registrazione invalida'],
    ['/vehicles/id', 'dettaglio'],
    ['/vehicles/id', 'telemetria assente'],
    ['/vehicles/id', 'telemetria in errore'],
    ['/vehicles/id', 'modale'],
    ['/vehicles/id/telemetry', 'storico'],
    ['/vehicles/id/telemetry', 'storico vuoto'],
    ['/vehicles/id/telemetry', 'storico in errore'],
    ['/vehicles/id/telemetry?size=101', 'storico URL invalida'],
    ['/vehicles/id/telemetry', 'storico form invalido'],
    ['/alerts', 'elenco alert'],
    ['/alerts', 'alert vuoti'],
    ['/alerts', 'alert errore'],
    ['/alerts', 'filtri alert invalidi'],
    ['/alerts?status=UNKNOWN', 'URL alert invalida'],
    ['/alerts/f2607610-5100-4723-93d0-e6bbdcf00da0', 'alert OPEN'],
    ['/alerts/f2607610-5100-4723-93d0-e6bbdcf00da0', 'alert ACKNOWLEDGED'],
    ['/alerts/f2607610-5100-4723-93d0-e6bbdcf00da0', 'alert CLOSED'],
    ['/alerts/f2607610-5100-4723-93d0-e6bbdcf00da0', 'alert mancante'],
    ['/alerts/f2607610-5100-4723-93d0-e6bbdcf00da0', 'dialog alert'],
    ['/alerts/f2607610-5100-4723-93d0-e6bbdcf00da0', 'conflitto alert'],
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
    if (state === 'dashboard con alert') {
      api.getDashboard.mockReturnValue(
        of({
          totalVehicles: 1,
          vehiclesByStatus: { ACTIVE: 1, DISABLED: 0 },
          recentlyReportingVehicles: 1,
          openAlerts: 1,
          relevantAlerts: [
            {
              id: 'alert',
              vehicleId: 'id',
              type: 'SERVICE_DUE',
              severity: 'HIGH',
              status: 'OPEN',
              description: 'Manutenzione richiesta',
              createdAt: '2026-11-08T10:00:00Z',
            },
          ],
        }),
      );
    }
    if (state === 'dashboard in errore') {
      api.getDashboard.mockReturnValue(
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
    if (state === 'telemetria assente' || state === 'telemetria in errore') {
      api.getVehicleState.mockReturnValue(
        throwError(() =>
          state === 'telemetria assente'
            ? new ApiError(
                'VEHICLE_STATE_NOT_AVAILABLE',
                404,
                'Nessuna telemetria disponibile.',
                false,
              )
            : new ApiError(
                'SERVICE_UNAVAILABLE',
                503,
                'Servizio temporaneamente non disponibile.',
                true,
              ),
        ),
      );
    }
    if (state === 'storico vuoto') {
      api.getTelemetry.mockReturnValue(
        of({
          content: [],
          page: 0,
          size: 50,
          totalElements: 0,
          totalPages: 0,
          first: true,
          last: true,
        }),
      );
    }
    if (state === 'storico in errore') {
      api.getTelemetry.mockReturnValue(
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
    if (state === 'alert vuoti')
      api.getAlerts.mockReturnValue(
        of({
          content: [],
          page: 0,
          size: 50,
          totalElements: 0,
          totalPages: 0,
          first: true,
          last: true,
        }),
      );
    if (state === 'alert errore')
      api.getAlerts.mockReturnValue(
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
    if (state === 'alert mancante')
      api.getAlert.mockReturnValue(
        throwError(() => new ApiError('ALERT_NOT_FOUND', 404, 'Alert non trovato.', false)),
      );
    if (state === 'alert ACKNOWLEDGED')
      api.getAlert.mockReturnValue(
        of({ ...TEST_ALERT, status: 'ACKNOWLEDGED', acknowledgedAt: '2026-11-08T20:01:00Z' }),
      );
    if (state === 'alert CLOSED')
      api.getAlert.mockReturnValue(
        of({ ...TEST_ALERT, status: 'CLOSED', closedAt: '2026-11-08T20:02:00Z' }),
      );
    if (state === 'conflitto alert') {
      api.getAlert
        .mockReturnValueOnce(of(TEST_ALERT))
        .mockReturnValue(of({ ...TEST_ALERT, status: 'CLOSED', closedAt: '2026-11-08T20:02:00Z' }));
      api.changeAlertStatus.mockReturnValue(
        throwError(
          () =>
            new ApiError(
              'ALERT_STATUS_TRANSITION_CONFLICT',
              409,
              'Lo stato dell’alert è cambiato. Aggiorna i dati.',
              false,
            ),
        ),
      );
    }
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    await TestBed.inject(Router).navigateByUrl(url);
    await fixture.whenStable();

    if (state === 'dashboard in pausa') {
      fixture.nativeElement.querySelector('app-polling-controls button:nth-child(2)').click();
      await fixture.whenStable();
    }
    if (state === 'storico form invalido') {
      const input = fixture.nativeElement.querySelector('#history-from') as HTMLInputElement;
      input.value = '';
      input.dispatchEvent(new Event('input'));
      fixture.nativeElement.querySelector('form button[type="submit"]').click();
      await fixture.whenStable();
    }
    if (state === 'registrazione invalida') {
      fixture.nativeElement.querySelector('form button[type="submit"]').click();
      await fixture.whenStable();
    }
    if (state === 'filtri alert invalidi') {
      const input = fixture.nativeElement.querySelector('#alert-vehicleId') as HTMLInputElement;
      input.value = 'invalido';
      input.dispatchEvent(new Event('input'));
      fixture.nativeElement.querySelector('form button[type="submit"]').click();
      await fixture.whenStable();
    }
    if (state === 'modale' || state === 'dialog alert' || state === 'conflitto alert') {
      const dialog: HTMLDialogElement = fixture.nativeElement.querySelector('dialog');
      // jsdom lacks the native dialog API. Keyboard containment is a browser acceptance check.
      Object.defineProperties(dialog, {
        showModal: { value: () => dialog.setAttribute('open', '') },
        close: { value: () => dialog.removeAttribute('open') },
      });
      fixture.nativeElement.querySelector('.panel .actions button').click();
      await fixture.whenStable();
      if (state === 'conflitto alert') {
        fixture.nativeElement.querySelector('dialog button').click();
        await fixture.whenStable();
      }
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
