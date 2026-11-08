import { JsonPipe } from '@angular/common';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { finalize, Observable } from 'rxjs';
import { ApiError } from '../core/api/api-error';
import { FleetApi } from '../core/api/fleet-api';
import { ApiErrorNotice } from '../shared/api-error-notice';

@Component({
  selector: 'app-api-check',
  imports: [FormsModule, JsonPipe, ApiErrorNotice],
  template: `
    <h1>Verifica Fleet API</h1>
    <p>Strumento di sviluppo FP-061. Esegue soltanto letture.</p>
    <button type="button" [disabled]="loading()" (click)="loadDashboard()">Leggi dashboard</button>
    <form (ngSubmit)="loadVehicle()">
      <label for="vehicle-id">Identificativo veicolo</label>
      <input id="vehicle-id" name="vehicleId" [(ngModel)]="vehicleId" required />
      <button type="submit" [disabled]="loading() || !vehicleId.trim()">Leggi veicolo</button>
    </form>
    @if (loading()) {
      <p role="status">Caricamento…</p>
    }
    @if (error(); as failure) {
      <app-api-error-notice [error]="failure" />
    }
    @if (result(); as data) {
      <pre>{{ data | json }}</pre>
    }
  `,
})
export class ApiCheck {
  private readonly api = inject(FleetApi);
  private readonly destroyRef = inject(DestroyRef);
  readonly loading = signal(false);
  readonly error = signal<ApiError | null>(null);
  readonly result = signal<unknown>(null);
  vehicleId = '';

  loadDashboard(): void {
    this.load(this.api.getDashboard());
  }

  loadVehicle(): void {
    if (!this.vehicleId.trim()) return;
    this.load(this.api.getVehicle(this.vehicleId.trim()));
  }

  private load(request: Observable<unknown>): void {
    if (this.loading()) return;
    this.loading.set(true);
    this.error.set(null);
    this.result.set(null);
    request
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.loading.set(false)),
      )
      .subscribe({
        next: (data) => this.result.set(data),
        error: (error: ApiError) => this.error.set(error),
      });
  }
}
