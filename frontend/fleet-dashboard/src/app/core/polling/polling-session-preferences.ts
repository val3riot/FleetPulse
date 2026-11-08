import { Injectable, Signal, signal } from '@angular/core';

export type PollingPauseScope = 'dashboard' | 'telemetry';

/** Session preferences only. HTTP data and subscriptions belong to each page. */
@Injectable({ providedIn: 'root' })
export class PollingSessionPreferences {
  private readonly pausedByScope = {
    dashboard: signal(false),
    telemetry: signal(false),
  };

  paused(scope: PollingPauseScope): Signal<boolean> {
    return this.pausedByScope[scope].asReadonly();
  }

  toggle(scope: PollingPauseScope): void {
    this.pausedByScope[scope].update((value) => !value);
  }
}
