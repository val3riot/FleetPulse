import { Routes } from '@angular/router';
import { DEV_ROUTES } from './dev/dev.routes';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  {
    path: 'dashboard',
    title: 'Dashboard | FleetPulse',
    loadComponent: () => import('./features/dashboard/dashboard').then((m) => m.Dashboard),
  },
  {
    path: 'vehicles',
    title: 'Veicoli | FleetPulse',
    loadComponent: () => import('./features/vehicles/vehicle-list').then((m) => m.VehicleList),
  },
  {
    path: 'vehicles/new',
    title: 'Registra veicolo | FleetPulse',
    loadComponent: () => import('./features/vehicles/vehicle-create').then((m) => m.VehicleCreate),
  },
  {
    path: 'vehicles/:id/telemetry',
    title: 'Storico telemetria | FleetPulse',
    loadComponent: () =>
      import('./features/vehicles/history/vehicle-history').then((m) => m.VehicleHistory),
  },
  {
    path: 'vehicles/:id',
    title: 'Dettaglio veicolo | FleetPulse',
    loadComponent: () => import('./features/vehicles/vehicle-detail').then((m) => m.VehicleDetail),
  },
  {
    path: 'alerts',
    title: 'Alert | FleetPulse',
    loadComponent: () => import('./features/alerts/alert-list').then((m) => m.AlertList),
  },
  {
    path: 'alerts/:id',
    title: 'Dettaglio alert | FleetPulse',
    loadComponent: () => import('./features/alerts/alert-detail').then((m) => m.AlertDetail),
  },
  ...DEV_ROUTES,
  {
    path: '**',
    title: 'Pagina non trovata | FleetPulse',
    loadComponent: () => import('./shared/not-found').then((m) => m.NotFound),
  },
];
