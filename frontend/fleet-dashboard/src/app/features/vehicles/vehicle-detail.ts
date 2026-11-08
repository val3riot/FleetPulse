import { DatePipe } from '@angular/common';
import {
  afterRenderEffect,
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  catchError,
  combineLatest,
  finalize,
  of,
  startWith,
  Subject,
  Subscription,
  switchMap,
} from 'rxjs';
import { ApiError } from '../../core/api/api-error';
import { VehicleResponse, VehicleStatus } from '../../core/api/api-models';
import { FleetApi } from '../../core/api/fleet-api';
import { ApiErrorNotice } from '../../shared/api-error-notice';
import { VehicleState } from './vehicle-state';

@Component({
  selector: 'app-vehicle-detail',
  imports: [DatePipe, RouterLink, ApiErrorNotice, VehicleState],
  templateUrl: './vehicle-detail.html',
  styleUrl: './vehicle-pages.css',
})
export class VehicleDetail {
  private readonly api = inject(FleetApi);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly refresh = new Subject<void>();
  private mutation?: Subscription;
  private refreshFocusRequested = false;
  private readonly heading = viewChild.required<ElementRef<HTMLElement>>('detailHeading');
  private readonly statusDialog = viewChild.required<ElementRef<HTMLDialogElement>>('statusDialog');
  private readonly cancelButton = viewChild<ElementRef<HTMLButtonElement>>('cancelButton');
  private readonly statusButton = viewChild<ElementRef<HTMLButtonElement>>('statusChangeButton');
  private readonly reloadButton = viewChild<ElementRef<HTMLButtonElement>>('reloadButton');
  private readonly dialogDescription =
    viewChild.required<ElementRef<HTMLElement>>('dialogDescription');
  readonly vehicle = signal<VehicleResponse | null>(null);
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly error = signal<ApiError | null>(null);
  readonly mutationError = signal<ApiError | null>(null);
  readonly uncertain = signal(false);
  readonly pendingStatus = signal<VehicleStatus | null>(null);
  readonly success = signal('');
  readonly statusMessage = computed(() => {
    if (this.loading()) return 'Caricamento veicolo…';
    if (this.uncertain())
      return "L'esito del cambio stato non è confermato. Aggiorna i dati prima di ripetere l'operazione.";
    return this.success();
  });

  constructor() {
    afterRenderEffect(() => {
      if (this.loading() || !this.refreshFocusRequested) return;
      this.refreshFocusRequested = false;
      const heading = this.heading().nativeElement;
      const active = heading.ownerDocument.activeElement;
      if (active === heading.ownerDocument.body || active === this.reloadButton()?.nativeElement) {
        heading.focus();
      }
    });
    afterRenderEffect(() => {
      const pending = this.pendingStatus() !== null;
      const saving = this.saving();
      const dialog = this.statusDialog().nativeElement;
      if (pending && !dialog.open) {
        dialog.showModal();
        this.cancelButton()?.nativeElement.focus();
      } else if (!pending && dialog.open) {
        dialog.close();
        const trigger = this.statusButton()?.nativeElement;
        if (trigger && !trigger.disabled) trigger.focus();
        else this.reloadButton()?.nativeElement.focus();
      }
      if (pending && saving) this.dialogDescription().nativeElement.focus();
    });

    combineLatest([this.route.paramMap, this.refresh.pipe(startWith(undefined))])
      .pipe(
        switchMap(([params]) => {
          this.mutation?.unsubscribe();
          this.loading.set(true);
          this.vehicle.set(null);
          this.error.set(null);
          this.mutationError.set(null);
          this.pendingStatus.set(null);
          this.success.set('');
          return this.api.getVehicle(params.get('id') ?? '').pipe(
            catchError((error: ApiError) => {
              this.error.set(error);
              return of(null);
            }),
            finalize(() => this.loading.set(false)),
          );
        }),
        takeUntilDestroyed(),
      )
      .subscribe((vehicle) => {
        this.vehicle.set(vehicle);
        if (vehicle) this.uncertain.set(false);
      });
  }

  reload(): void {
    if (this.saving()) return;
    this.refreshFocusRequested = true;
    this.refresh.next();
  }

  requestStatusChange(): void {
    const vehicle = this.vehicle();
    if (!vehicle || this.saving() || this.uncertain()) return;
    this.success.set('');
    this.mutationError.set(null);
    this.pendingStatus.set(vehicle.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE');
  }

  cancelStatusChange(): void {
    if (!this.saving()) this.pendingStatus.set(null);
  }

  onDialogCancel(event: Event): void {
    // Keep the signal authoritative; Escape must not dismiss an in-flight write.
    event.preventDefault();
    this.cancelStatusChange();
  }

  confirmStatusChange(): void {
    const vehicle = this.vehicle();
    const target = this.pendingStatus();
    if (!vehicle || !target || this.saving() || this.uncertain()) return;
    this.saving.set(true);
    this.mutationError.set(null);
    this.mutation = this.api
      .changeVehicleStatus(vehicle.id, target)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.saving.set(false)),
      )
      .subscribe({
        next: (updated) => {
          this.vehicle.set(updated);
          this.pendingStatus.set(null);
          this.success.set('Stato del veicolo aggiornato.');
        },
        error: (error: ApiError) => {
          this.mutationError.set(error);
          this.pendingStatus.set(null);
          this.uncertain.set(error.status === 0 || error.status >= 500);
        },
      });
  }
}
