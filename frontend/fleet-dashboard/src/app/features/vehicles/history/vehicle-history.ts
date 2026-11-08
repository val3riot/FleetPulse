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
  tap,
} from 'rxjs';
import { ApiError } from '../../../core/api/api-error';
import { Page, TelemetryResponse, VehicleResponse } from '../../../core/api/api-models';
import { FleetApi } from '../../../core/api/fleet-api';
import { ApiErrorNotice } from '../../../shared/api-error-notice';
import { formatMetric, formatCoordinate } from '../../../shared/telemetry-format';
import {
  defaultHistoryQuery,
  formatUtcInstant,
  HISTORY_PAGE_LIMIT,
  HistoryQuery,
  HistoryQueryIssue,
  historyQueryParams,
  historyQuerySignature,
  parseHistoryQuery,
  utcInputToInstant,
  utcInstant,
  utcInstantToInput,
} from './history-query';

const dateTime = (control: AbstractControl) =>
  !control.value || utcInputToInstant(control.value) !== null ? null : { utc: true };
const integer = (control: AbstractControl) =>
  control.value === null || Number.isInteger(control.value) ? null : { integer: true };

@Component({
  selector: 'app-vehicle-history',
  imports: [ReactiveFormsModule, RouterLink, ApiErrorNotice],
  templateUrl: './vehicle-history.html',
  styleUrl: './vehicle-history.css',
})
export class VehicleHistory {
  private readonly api = inject(FleetApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly injector = inject(Injector);
  private readonly destroyRef = inject(DestroyRef);
  private readonly formElement = viewChild.required<ElementRef<HTMLFormElement>>('historyForm');
  private readonly resultsRegion = viewChild.required<ElementRef<HTMLElement>>('resultsRegion');
  private readonly refresh = new Subject<void>();
  private readonly defaults = defaultHistoryQuery(new Date());
  private cachedVehicleId?: string;
  private focusResultsRequested = false;
  readonly vehicleId = signal('');
  readonly vehicle = signal<VehicleResponse | null>(null);
  readonly loading = signal(false);
  readonly error = signal<ApiError | null>(null);
  readonly queryIssues = signal<HistoryQueryIssue[]>([]);
  readonly applied = signal<HistoryQuery | null>(null);
  readonly result = signal<Page<TelemetryResponse> | null>(null);
  readonly pageLimit = HISTORY_PAGE_LIMIT;
  readonly formatTime = formatUtcInstant;
  readonly form = new FormGroup(
    {
      from: new FormControl('', { nonNullable: true, validators: [Validators.required, dateTime] }),
      to: new FormControl('', { nonNullable: true, validators: [Validators.required, dateTime] }),
      size: new FormControl<number | null>(50, [
        Validators.required,
        Validators.min(1),
        Validators.max(HISTORY_PAGE_LIMIT),
        integer,
      ]),
      sort: new FormControl<HistoryQuery['sort']>('observedAt,desc', {
        nonNullable: true,
        validators: [Validators.required, Validators.pattern(/^observedAt,(asc|desc)$/)],
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
  readonly statusMessage = computed(() =>
    this.loading()
      ? 'Caricamento storico…'
      : this.result()
        ? `Misure trovate: ${this.result()!.totalElements}`
        : '',
  );
  readonly rows = computed(() =>
    (this.result()?.content ?? []).map((row) => ({
      ...row,
      time: formatUtcInstant(row.observedAt),
      speed: formatMetric(row.speedKmh),
      temperature: formatMetric(row.engineTemperatureC),
      battery: formatMetric(row.batteryVoltage),
      odometer: formatMetric(row.odometerKm),
      latitudeText: formatCoordinate(row.latitude),
      longitudeText: formatCoordinate(row.longitude),
    })),
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
        map((force) => {
          const { paramMap, queryParamMap } = this.route.snapshot;
          const id = paramMap.get('id') ?? '';
          return {
            id,
            params: queryParamMap,
            force,
            signature: historyQuerySignature(id, queryParamMap),
          };
        }),
        distinctUntilChanged(
          (previous, current) => !current.force && previous.signature === current.signature,
        ),
        switchMap(({ id, params }) => {
          this.vehicleId.set(id);
          if (id !== this.cachedVehicleId) {
            this.cachedVehicleId = id;
            this.vehicle.set(null);
          }
          this.result.set(null);
          this.error.set(null);
          this.queryIssues.set([]);
          this.loading.set(false);
          const parsed = parseHistoryQuery(params, this.defaults);
          this.applied.set(parsed.query);
          if (!parsed.query) {
            this.focusResultsRequested = false;
            this.queryIssues.set(parsed.issues);
            this.restoreForm(
              params.get('from') ?? '',
              params.get('to') ?? '',
              Number(params.get('size') ?? 50),
              params.get('sort') ?? 'observedAt,desc',
            );
            this.form.markAllAsTouched();
            return EMPTY;
          }
          const query = parsed.query;
          this.restoreForm(query.from, query.to, query.size, query.sort);
          if (parsed.canonicalize) {
            void this.router.navigate([], {
              relativeTo: this.route,
              queryParams: historyQueryParams(query),
              replaceUrl: true,
            });
            return EMPTY;
          }
          this.loading.set(true);
          const vehicleRead = this.vehicle()
            ? of(this.vehicle()!)
            : this.api.getVehicle(id).pipe(tap((vehicle) => this.vehicle.set(vehicle)));
          return vehicleRead.pipe(
            switchMap(() => this.api.getTelemetry(id, query)),
            catchError((error: ApiError) => {
              this.error.set(error);
              return of(null);
            }),
            finalize(() => this.loading.set(false)),
          );
        }),
        takeUntilDestroyed(),
      )
      .subscribe((page) => this.result.set(page));
  }

  private restoreForm(from: string, to: string, size: number, sort: string): void {
    this.form.reset(
      {
        from: utcInstantToInput(from),
        to: utcInstantToInput(to),
        size,
        sort: sort as HistoryQuery['sort'],
      },
      { emitEvent: false },
    );
  }

  fieldError(field: 'from' | 'to' | 'size' | 'sort'): string | null {
    const control = this.form.controls[field];
    if (!control.touched) return null;
    if (control.hasError('required')) return 'Campo obbligatorio.';
    if (control.hasError('utc')) return 'Inserisci una data e ora valida.';
    if ((field === 'from' || field === 'to') && this.form.hasError('range'))
      return "L'inizio deve precedere o coincidere con la fine.";
    if (field === 'size' && control.invalid) return 'Inserisci un intero da 1 a 100.';
    if (field === 'sort' && control.invalid) return 'Seleziona un ordinamento supportato.';
    return null;
  }

  applyFilters(): void {
    this.focusResultsRequested = false;
    this.form.markAllAsTouched();
    if (this.form.invalid) {
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
    const value = this.form.getRawValue();
    const previous = this.applied();
    const from =
      previous && value.from === utcInstantToInput(previous.from)
        ? previous.from
        : utcInputToInstant(value.from)!;
    const to =
      previous && value.to === utcInstantToInput(previous.to)
        ? previous.to
        : utcInputToInstant(value.to)!;
    const query: HistoryQuery = { from, to, size: value.size!, sort: value.sort, page: 0 };
    void this.router
      .navigate([], { relativeTo: this.route, queryParams: historyQueryParams(query) })
      .then((changed) => {
        if (!changed) this.refresh.next();
      });
  }

  changePage(page: number): void {
    const query = this.applied();
    if (this.loading() || !query || !Number.isSafeInteger(page) || page < 0 || page > 2147483647)
      return;
    this.focusResultsRequested = true;
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: historyQueryParams({ ...query, page }),
    });
  }

  reload(): void {
    if (this.loading() || !this.applied()) return;
    this.focusResultsRequested = true;
    this.refresh.next();
  }
}
