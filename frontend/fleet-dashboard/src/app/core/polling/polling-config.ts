import { InjectionToken } from '@angular/core';

export interface PollingConfig {
  readonly intervalMs: number;
}

export const DEFAULT_POLLING_CONFIG: PollingConfig = { intervalMs: 5000 };
export const POLLING_CONFIG = new InjectionToken<PollingConfig>('Polling configuration', {
  providedIn: 'root',
  factory: () => DEFAULT_POLLING_CONFIG,
});
