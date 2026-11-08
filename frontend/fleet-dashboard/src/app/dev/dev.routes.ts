import { Routes } from '@angular/router';

export const DEV_ROUTES: Routes = [
  {
    path: 'dev/api',
    title: 'Verifica API | FleetPulse',
    loadComponent: () => import('./api-check').then((m) => m.ApiCheck),
  },
];
