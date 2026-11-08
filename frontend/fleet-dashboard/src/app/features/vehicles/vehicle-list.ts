import {
  afterRenderEffect,
  Component,
  computed,
  ElementRef,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { BehaviorSubject, catchError, finalize, of, switchMap } from 'rxjs';
import { ApiError } from '../../core/api/api-error';
import { Page, VehicleQuery, VehicleResponse, VehicleStatus } from '../../core/api/api-models';
import { FleetApi } from '../../core/api/fleet-api';
import { ApiErrorNotice } from '../../shared/api-error-notice';

@Component({
  selector: 'app-vehicle-list',
  imports: [ReactiveFormsModule, RouterLink, ApiErrorNotice],
  templateUrl: './vehicle-list.html',
  styleUrl: './vehicle-pages.css',
})
export class VehicleList {
  private readonly api = inject(FleetApi);
  private readonly resultsRegion = viewChild.required<ElementRef<HTMLElement>>('resultsRegion');
  private paginationFocusRequested = false;
  private readonly requests = new BehaviorSubject<VehicleQuery>({
    page: 0,
    size: 20,
    sort: 'createdAt,desc',
  });
  readonly loading = signal(false);
  readonly error = signal<ApiError | null>(null);
  readonly result = signal<Page<VehicleResponse> | null>(null);
  readonly statusMessage = computed(() => {
    const page = this.result();
    return this.loading()
      ? 'Caricamento veicoli…'
      : page
        ? `Veicoli trovati: ${page.totalElements}`
        : '';
  });
  readonly filters = new FormGroup({
    query: new FormControl('', { nonNullable: true }),
    status: new FormControl<VehicleStatus | ''>('', { nonNullable: true }),
    sort: new FormControl<NonNullable<VehicleQuery['sort']>>('createdAt,desc', {
      nonNullable: true,
    }),
  });

  constructor() {
    afterRenderEffect(() => {
      if (this.loading() || !this.paginationFocusRequested) return;
      this.paginationFocusRequested = false;
      const region = this.resultsRegion().nativeElement;
      const active = region.ownerDocument.activeElement;
      // Do not interrupt someone who moved back to the filters while HTTP was pending.
      if (active === region.ownerDocument.body || (active && region.contains(active)))
        region.focus();
    });
    this.requests
      .pipe(
        switchMap((query) => {
          this.loading.set(true);
          this.error.set(null);
          this.result.set(null);
          return this.api.getVehicles(query).pipe(
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

  applyFilters(): void {
    this.paginationFocusRequested = false;
    const values = this.filters.getRawValue();
    this.requests.next({
      query: values.query.trim() || undefined,
      status: values.status || undefined,
      sort: values.sort,
      page: 0,
      size: 20,
    });
  }

  resetFilters(): void {
    this.filters.reset({ query: '', status: '', sort: 'createdAt,desc' });
    this.applyFilters();
  }

  changePage(page: number): void {
    if (this.loading() || page < 0) return;
    this.paginationFocusRequested = true;
    this.requests.next({ ...this.requests.value, page });
  }

  reload(): void {
    if (this.loading()) return;
    this.paginationFocusRequested = true;
    this.requests.next(this.requests.value);
  }
}
