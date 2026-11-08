import { UtcDateTime } from './utc-date-time';
import { Component, computed, input } from '@angular/core';
import { PollingControlsState } from '../core/polling/polling-resource';

@Component({
  selector: 'app-polling-controls',
  imports: [UtcDateTime],
  template: `
    <div role="group" [attr.aria-label]="'Aggiornamento ' + label()" class="controls">
      <button type="button" [attr.aria-disabled]="busy()" (click)="resource().refresh()">
        Aggiorna {{ label() }}
      </button>
      <button type="button" (click)="resource().togglePause()">
        {{ resource().paused() ? 'Riprendi' : 'Pausa' }} {{ label() }}
      </button>
    </div>
    <p class="live-status" role="status">{{ modeMessage() }}</p>
    @if (resource().loading()) {
      <p>Aggiornamento in corso…</p>
    }
    @if (resource().lastSuccessfulAt(); as time) {
      <p>
        Ultima lettura riuscita:
        <time [attr.datetime]="time.toISOString()">{{ time | utcDateTime }}</time>
      </p>
    }
  `,
  styles: `
    .controls {
      display: flex;
      gap: 0.75rem;
      flex-wrap: wrap;
    }
    button {
      font: inherit;
      min-block-size: 2.75rem;
      padding: 0.5rem 0.75rem;
      border: 1px solid var(--color-control-border);
      border-radius: 0.3rem;
      color: var(--color-link);
      background: var(--color-surface);
      cursor: pointer;
    }
    button[aria-disabled='true'] {
      cursor: default;
    }
  `,
})
export class PollingControls {
  readonly resource = input.required<PollingControlsState>();
  readonly label = input.required<string>();
  readonly busy = computed(() => this.resource().loading() || this.resource().suspended());
  readonly modeMessage = computed(() => {
    const resource = this.resource();
    if (resource.stopped())
      return 'Aggiornamento automatico interrotto. Puoi riprovare manualmente.';
    if (resource.paused()) return 'Aggiornamento automatico in pausa.';
    if (resource.suspended()) return 'Aggiornamento automatico temporaneamente sospeso.';
    if (resource.hidden()) return 'Aggiornamento automatico sospeso nella scheda nascosta.';
    return 'Aggiornamento automatico attivo.';
  });
}
