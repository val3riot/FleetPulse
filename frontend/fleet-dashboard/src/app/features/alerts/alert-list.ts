import {
  afterNextRender,
  afterRenderEffect,
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  Injector,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { ActivatedRoute, NavigationEnd, Router, RouterLink } from '@angular/router';
import {
  catchError,
  distinctUntilChanged,
  EMPTY,
  filter,
  finalize,
  map,
  merge,
  of,
  startWith,
  Subject,
  switchMap,
} from 'rxjs';
import { ApiError } from '../../core/api/api-error';
import { AlertQuery, MaintenanceAlertResponse, Page } from '../../core/api/api-models';
import { FleetApi } from '../../core/api/fleet-api';
import { ApiErrorNotice } from '../../shared/api-error-notice';
import {
  alertLabels,
  ALERT_TYPES,
  ALERT_SEVERITIES,
  ALERT_STATUSES,
} from '../../shared/alert-labels';
import { UtcDateTime } from '../../shared/utc-date-time';
import { utcInputToInstant, utcInstant, utcInstantToInput } from '../../shared/utc-date-input';
import {
  alertQueryParams,
  alertQuerySignature,
  AppliedAlertQuery,
  DEFAULT_ALERT_QUERY,
  parseAlertQuery,
  UUID_PATTERN,
} from './alert-query';

const optionalDate = (control: AbstractControl) =>
  !control.value || utcInputToInstant(control.value) ? null : { date: true };
const enumeration = (values: Record<string, string>) => (control: AbstractControl) =>
  !control.value || Object.hasOwn(values, control.value) ? null : { enum: true };

@Component({
  selector: 'app-alert-list',
  imports: [ReactiveFormsModule, RouterLink, ApiErrorNotice, UtcDateTime],
  templateUrl: './alert-list.html',
  styleUrl: './alert-pages.css',
})
export class AlertList {
  private readonly api = inject(FleetApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly injector = inject(Injector);
  private readonly destroyRef = inject(DestroyRef);
  private readonly formElement = viewChild.required<ElementRef<HTMLFormElement>>('filterForm');
  private readonly resultsRegion = viewChild.required<ElementRef<HTMLElement>>('resultsRegion');
  private readonly refresh = new Subject<void>();
  private focusResultsRequested = false;
  readonly types = Object.entries(ALERT_TYPES);
  readonly severities = Object.entries(ALERT_SEVERITIES);
  readonly statuses = Object.entries(ALERT_STATUSES);
  readonly loading = signal(false);
  readonly error = signal<ApiError | null>(null);
  readonly issues = signal<string[]>([]);
  readonly applied = signal<AppliedAlertQuery | null>(null);
  readonly result = signal<Page<MaintenanceAlertResponse> | null>(null);
  readonly appliedParams = computed(() =>
    this.applied() ? alertQueryParams(this.applied()!) : {},
  );
  readonly rows = computed(() => (this.result()?.content ?? []).map(alertLabels));
  readonly statusMessage = computed(() =>
    this.loading()
      ? 'Caricamento alert…'
      : this.result()
        ? `Alert trovati: ${this.result()!.totalElements}`
        : '',
  );
  readonly filters = new FormGroup(
    {
      vehicleId: new FormControl('', {
        nonNullable: true,
        validators: [Validators.pattern(UUID_PATTERN)],
      }),
      type: new FormControl('', { nonNullable: true, validators: [enumeration(ALERT_TYPES)] }),
      severity: new FormControl('', {
        nonNullable: true,
        validators: [enumeration(ALERT_SEVERITIES)],
      }),
      status: new FormControl('', { nonNullable: true, validators: [enumeration(ALERT_STATUSES)] }),
      from: new FormControl('', { nonNullable: true, validators: [optionalDate] }),
      to: new FormControl('', { nonNullable: true, validators: [optionalDate] }),
      size: new FormControl<number | null>(50, [
        Validators.required,
        Validators.min(1),
        Validators.max(100),
        (control) =>
          control.value === null || Number.isInteger(control.value) ? null : { integer: true },
      ]),
      sort: new FormControl<AppliedAlertQuery['sort']>('createdAt,desc', {
        nonNullable: true,
        validators: [Validators.required, Validators.pattern(/^createdAt,(asc|desc)$/)],
      }),
    },
    {
      validators: (group) => {
        const from = utcInputToInstant(group.get('from')?.value ?? '');
        const to = utcInputToInstant(group.get('to')?.value ?? '');
        return from && to && utcInstant(from)! > utcInstant(to)! ? { range: true } : null;
      },
    },
  );
  constructor() {
    afterRenderEffect(() => {
      if (this.loading() || !this.focusResultsRequested) return;
      this.focusResultsRequested = false;
      const region = this.resultsRegion().nativeElement;
      const active = region.ownerDocument.activeElement;
      if (active === region.ownerDocument.body || (active && region.contains(active)))
        region.focus();
    });
    merge(
      this.router.events.pipe(
        filter((event) => event instanceof NavigationEnd),
        map(() => false),
      ),
      this.refresh.pipe(map(() => true)),
    )
      .pipe(
        startWith(false),
        map((force) => ({
          force,
          params: this.route.snapshot.queryParamMap,
          signature: alertQuerySignature(this.route.snapshot.queryParamMap),
        })),
        distinctUntilChanged(
          (previous, current) => !current.force && previous.signature === current.signature,
        ),
        switchMap(({ params }) => {
          this.result.set(null);
          this.error.set(null);
          this.loading.set(false);
          const parsed = parseAlertQuery(params);
          this.applied.set(parsed.query);
          this.issues.set(parsed.issues);
          this.filters.reset(
            {
              vehicleId: params.get('vehicleId') ?? '',
              type: params.get('type') ?? '',
              severity: params.get('severity') ?? '',
              status: params.get('status') ?? '',
              from: utcInstantToInput(params.get('from') ?? ''),
              to: utcInstantToInput(params.get('to') ?? ''),
              size: Number(params.get('size') ?? 50),
              sort: (params.get('sort') ?? 'createdAt,desc') as AppliedAlertQuery['sort'],
            },
            { emitEvent: false },
          );
          if (!parsed.query) {
            this.focusResultsRequested = false;
            this.filters.markAllAsTouched();
            return EMPTY;
          }
          if (parsed.canonicalize) {
            void this.navigate(parsed.query, true);
            return EMPTY;
          }
          this.loading.set(true);
          return this.api.getAlerts(parsed.query).pipe(
            catchError((failure: ApiError) => {
              this.error.set(failure);
              return of(null);
            }),
            finalize(() => this.loading.set(false)),
          );
        }),
        takeUntilDestroyed(),
      )
      .subscribe((page) => this.result.set(page));
  }
  private navigate(query: AppliedAlertQuery, replaceUrl = false): Promise<boolean> {
    return this.router.navigate([], {
      relativeTo: this.route,
      queryParams: alertQueryParams(query),
      replaceUrl,
    });
  }
  fieldError(field: keyof AlertList['filters']['controls']): string | null {
    const control = this.filters.controls[field];
    if (!control.touched) return null;
    if (control.hasError('required')) return 'Campo obbligatorio.';
    if (field === 'vehicleId' && control.invalid) return 'Inserisci un UUID veicolo valido.';
    if ((field === 'from' || field === 'to') && this.filters.hasError('range'))
      return "L'inizio deve precedere o coincidere con la fine.";
    if (control.hasError('date')) return 'Inserisci una data e ora valida.';
    if (field === 'size' && control.invalid) return 'Inserisci un intero da 1 a 100.';
    return control.invalid ? 'Seleziona un valore supportato.' : null;
  }
  applyFilters(): void {
    this.focusResultsRequested = false;
    this.filters.markAllAsTouched();
    if (this.filters.invalid) {
      afterNextRender(
        () => {
          if (!this.destroyRef.destroyed)
            this.formElement()
              .nativeElement.querySelector<HTMLElement>('[aria-invalid="true"]')
              ?.focus();
        },
        { injector: this.injector },
      );
      return;
    }
    const values = this.filters.getRawValue();
    const previous = this.applied();
    const endpoint = (field: 'from' | 'to') =>
      !values[field]
        ? undefined
        : previous?.[field] && values[field] === utcInstantToInput(previous[field]!)
          ? previous[field]
          : utcInputToInstant(values[field])!;
    const query: AppliedAlertQuery = {
      vehicleId: values.vehicleId || undefined,
      type: (values.type || undefined) as AlertQuery['type'],
      status: (values.status || undefined) as AlertQuery['status'],
      severity: (values.severity || undefined) as AlertQuery['severity'],
      from: endpoint('from'),
      to: endpoint('to'),
      page: 0,
      size: values.size!,
      sort: values.sort,
    };
    void this.navigate(query).then((changed) => {
      if (!changed) this.refresh.next();
    });
  }
  resetFilters(): void {
    this.focusResultsRequested = false;
    void this.navigate(DEFAULT_ALERT_QUERY).then((changed) => {
      if (!changed) this.refresh.next();
    });
  }
  changePage(page: number): void {
    if (
      this.loading() ||
      !this.applied() ||
      !Number.isSafeInteger(page) ||
      page < 0 ||
      page > 2147483647
    )
      return;
    this.focusResultsRequested = true;
    void this.navigate({ ...this.applied()!, page });
  }
  reload(): void {
    if (!this.loading() && this.applied()) {
      this.focusResultsRequested = true;
      this.refresh.next();
    }
  }
}
