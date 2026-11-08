import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  {
    path: 'dashboard',
    title: 'Dashboard | FleetPulse',
    loadComponent: () => import('./features/feature-preview').then((m) => m.FeaturePreview),
    data: {
      heading: 'Dashboard',
      description: 'La panoramica della flotta sarà disponibile con FP-063.',
    },
  },
  {
    path: 'vehicles',
    title: 'Veicoli | FleetPulse',
    loadComponent: () => import('./features/feature-preview').then((m) => m.FeaturePreview),
    data: {
      heading: 'Veicoli',
      description: 'La gestione dei veicoli sarà disponibile con FP-062.',
    },
  },
  {
    path: 'alerts',
    title: 'Alert | FleetPulse',
    loadComponent: () => import('./features/feature-preview').then((m) => m.FeaturePreview),
    data: { heading: 'Alert', description: 'La gestione degli alert sarà disponibile con FP-065.' },
  },
  {
    path: '**',
    title: 'Pagina non trovata | FleetPulse',
    loadComponent: () => import('./shared/not-found').then((m) => m.NotFound),
  },
];
