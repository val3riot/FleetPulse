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
import { AlertTargetStatus, MaintenanceAlertResponse } from '../../core/api/api-models';
import { FleetApi } from '../../core/api/fleet-api';
import { ApiErrorNotice } from '../../shared/api-error-notice';
import { alertLabels } from '../../shared/alert-labels';
import { UtcDateTime } from '../../shared/utc-date-time';
import {
  alertQueryParams,
  DEFAULT_ALERT_QUERY,
  parseAlertQuery,
  UUID_PATTERN,
} from './alert-query';

@Component({
  selector: 'app-alert-detail',
  imports: [RouterLink, ApiErrorNotice, UtcDateTime],
  templateUrl: './alert-detail.html',
  styleUrl: './alert-pages.css',
})
export class AlertDetail {
  private readonly api = inject(FleetApi);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly refresh = new Subject<void>();
  private mutation?: Subscription;
  private currentId?: string;
  private trigger?: HTMLButtonElement;
  private focusReadRequested = false;
  private readonly heading = viewChild.required<ElementRef<HTMLElement>>('detailHeading');
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('confirmationDialog');
  private readonly cancelButton = viewChild<ElementRef<HTMLButtonElement>>('cancelButton');
  private readonly dialogDescription =
    viewChild.required<ElementRef<HTMLElement>>('dialogDescription');
  private readonly reloadButton = viewChild.required<ElementRef<HTMLButtonElement>>('reloadButton');
  readonly alert = signal<MaintenanceAlertResponse | null>(null);
  readonly item = computed(() => (this.alert() ? alertLabels(this.alert()!) : null));
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly error = signal<ApiError | null>(null);
  readonly mutationError = signal<ApiError | null>(null);
  readonly needsVerification = signal(false);
  readonly pending = signal<AlertTargetStatus | null>(null);
  readonly success = signal('');
  readonly returnParams = signal(alertQueryParams(DEFAULT_ALERT_QUERY));
  readonly actionsBlocked = computed(
    () =>
      this.loading() ||
      this.saving() ||
      this.needsVerification() ||
      !!this.error() ||
      !!this.pending(),
  );
  readonly statusMessage = computed(() => {
    if (this.loading())
      return this.needsVerification() ? 'Verifica dello stato sul server…' : 'Caricamento alert…';
    if (this.needsVerification())
      return "L'esito dell'operazione deve essere verificato. Aggiorna lo stato prima di ripetere un comando.";
    return this.success();
  });
  constructor() {
    this.route.queryParamMap
      .pipe(takeUntilDestroyed())
      .subscribe((params) =>
        this.returnParams.set(
          alertQueryParams(parseAlertQuery(params).query ?? DEFAULT_ALERT_QUERY),
        ),
      );
    afterRenderEffect(() => {
      if (this.loading() || !this.focusReadRequested) return;
      this.focusReadRequested = false;
      const heading = this.heading().nativeElement;
      const active = heading.ownerDocument.activeElement;
      if (active === heading.ownerDocument.body || active === this.reloadButton().nativeElement)
        heading.focus();
    });
    afterRenderEffect(() => {
      const pending = this.pending();
      const saving = this.saving();
      const dialog = this.dialog().nativeElement;
      if (pending && !dialog.open) {
        dialog.showModal();
        this.cancelButton()?.nativeElement.focus();
      } else if (!pending && dialog.open) {
        dialog.close();
        if (this.trigger?.isConnected && this.trigger.getAttribute('aria-disabled') !== 'true')
          this.trigger.focus();
        else this.reloadButton().nativeElement.focus();
      }
      if (pending && saving) this.dialogDescription().nativeElement.focus();
    });
    combineLatest([this.route.paramMap, this.refresh.pipe(startWith(undefined))])
      .pipe(
        switchMap(([params]) => {
          const id = params.get('id') ?? '';
          if (id !== this.currentId) {
            this.mutation?.unsubscribe();
            this.currentId = id;
            this.alert.set(null);
            this.pending.set(null);
            this.mutationError.set(null);
            this.needsVerification.set(false);
            this.success.set('');
          }
          this.error.set(null);
          if (!UUID_PATTERN.test(id)) {
            this.error.set(
              new ApiError('REQUEST_INVALID', 400, 'Identificativo alert non valido.', false),
            );
            return of(null);
          }
          this.loading.set(true);
          return this.api.getAlert(id).pipe(
            catchError((failure: ApiError) => {
              this.error.set(failure);
              if (failure.code === 'ALERT_NOT_FOUND') this.alert.set(null);
              return of(null);
            }),
            finalize(() => this.loading.set(false)),
          );
        }),
        takeUntilDestroyed(),
      )
      .subscribe((alert) => {
        if (!alert) return;
        this.alert.set(alert);
        if (this.needsVerification())
          this.success.set('Stato dell’alert riletto dal server. Verifica le azioni disponibili.');
        this.needsVerification.set(false);
      });
  }
  reload(): void {
    if (this.loading() || this.saving() || this.pending()) return;
    this.focusReadRequested = true;
    this.refresh.next();
  }
  requestChange(target: AlertTargetStatus, trigger?: HTMLButtonElement): void {
    const state = this.alert()?.status;
    if (
      this.actionsBlocked() ||
      !state ||
      state === 'CLOSED' ||
      (state === 'ACKNOWLEDGED' && target !== 'CLOSED')
    )
      return;
    this.trigger = trigger;
    this.success.set('');
    this.mutationError.set(null);
    this.pending.set(target);
  }
  cancel(): void {
    if (!this.saving()) this.pending.set(null);
  }
  onCancel(event: Event): void {
    event.preventDefault();
    this.cancel();
  }
  confirm(): void {
    const alert = this.alert();
    const target = this.pending();
    if (
      !alert ||
      !target ||
      this.saving() ||
      this.loading() ||
      this.needsVerification() ||
      this.error()
    )
      return;
    this.saving.set(true);
    this.mutation = this.api
      .changeAlertStatus(alert.id, target)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.saving.set(false)),
      )
      .subscribe({
        next: (updated) => {
          this.alert.set(updated);
          this.pending.set(null);
          this.success.set(
            updated.status === 'CLOSED' ? 'Alert chiuso.' : 'Alert preso in carico.',
          );
        },
        error: (failure: ApiError) => {
          this.mutationError.set(failure);
          this.pending.set(null);
          this.saving.set(false);
          const conflict = failure.code === 'ALERT_STATUS_TRANSITION_CONFLICT';
          this.needsVerification.set(conflict || failure.status === 0 || failure.status >= 500);
          if (failure.code === 'ALERT_NOT_FOUND') {
            this.alert.set(null);
            this.error.set(failure);
            this.mutationError.set(null);
          }
          if (conflict) {
            this.focusReadRequested = true;
            this.refresh.next();
          }
        },
      });
  }
}
