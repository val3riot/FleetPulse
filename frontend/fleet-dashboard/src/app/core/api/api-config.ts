import { InjectionToken } from '@angular/core';

export interface ApiConfig {
  readonly baseUrl: string;
  readonly readRetryCount: number;
  readonly retryDelayMs: number;
  readonly timeoutMs: number;
}

export const DEFAULT_API_CONFIG: ApiConfig = {
  baseUrl: '/api/v1',
  readRetryCount: 1,
  retryDelayMs: 500,
  timeoutMs: 10000,
};

export const API_CONFIG = new InjectionToken<ApiConfig>('Fleet API configuration', {
  providedIn: 'root',
  factory: () => DEFAULT_API_CONFIG,
});
