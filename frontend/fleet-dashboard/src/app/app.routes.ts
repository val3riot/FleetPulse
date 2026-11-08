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
    path: 'vehicles/:id',
    title: 'Dettaglio veicolo | FleetPulse',
    loadComponent: () => import('./features/vehicles/vehicle-detail').then((m) => m.VehicleDetail),
  },
  {
    path: 'alerts',
    title: 'Alert | FleetPulse',
    loadComponent: () => import('./features/feature-preview').then((m) => m.FeaturePreview),
    data: { heading: 'Alert', description: 'La gestione degli alert sarà disponibile con FP-065.' },
  },
  ...DEV_ROUTES,
  {
    path: '**',
    title: 'Pagina non trovata | FleetPulse',
    loadComponent: () => import('./shared/not-found').then((m) => m.NotFound),
  },
];
