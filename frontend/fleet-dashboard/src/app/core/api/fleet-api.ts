import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { catchError, Observable, retry, throwError, timeout, timer } from 'rxjs';
import { API_CONFIG } from './api-config';
import { isTemporaryReadFailure, mapApiError } from './api-error';
import {
  AlertQuery,
  AlertTargetStatus,
  CreateVehicleRequest,
  DashboardResponse,
  MaintenanceAlertResponse,
  Page,
  TelemetryQuery,
  TelemetryResponse,
  VehicleQuery,
  VehicleResponse,
  VehicleStateResponse,
  VehicleStatus,
} from './api-models';

@Injectable({ providedIn: 'root' })
export class FleetApi {
  private readonly http = inject(HttpClient);
  private readonly config = inject(API_CONFIG);
  private readonly baseUrl = this.config.baseUrl.replace(/\/+$/, '');

  getDashboard(): Observable<DashboardResponse> {
    return this.read('/dashboard');
  }

  getVehicles(query: VehicleQuery = {}): Observable<Page<VehicleResponse>> {
    return this.read('/vehicles', query);
  }

  getVehicle(id: string): Observable<VehicleResponse> {
    return this.read(`/vehicles/${encodeURIComponent(id)}`);
  }

  createVehicle(request: CreateVehicleRequest): Observable<VehicleResponse> {
    return this.write('POST', '/vehicles', request);
  }

  changeVehicleStatus(id: string, status: VehicleStatus): Observable<VehicleResponse> {
    return this.write('PATCH', `/vehicles/${encodeURIComponent(id)}/status`, { status });
  }

  getVehicleState(id: string): Observable<VehicleStateResponse> {
    return this.read(`/vehicles/${encodeURIComponent(id)}/state`);
  }

  getTelemetry(id: string, query: TelemetryQuery): Observable<Page<TelemetryResponse>> {
    return this.read(`/vehicles/${encodeURIComponent(id)}/telemetry`, query);
  }

  getAlerts(query: AlertQuery = {}): Observable<Page<MaintenanceAlertResponse>> {
    return this.read('/alerts', query);
  }

  getVehicleAlerts(
    id: string,
    query: Omit<AlertQuery, 'vehicleId'> = {},
  ): Observable<Page<MaintenanceAlertResponse>> {
    return this.read(`/vehicles/${encodeURIComponent(id)}/alerts`, query);
  }

  getAlert(id: string): Observable<MaintenanceAlertResponse> {
    return this.read(`/alerts/${encodeURIComponent(id)}`);
  }

  changeAlertStatus(id: string, status: AlertTargetStatus): Observable<MaintenanceAlertResponse> {
    return this.write('PATCH', `/alerts/${encodeURIComponent(id)}`, { status });
  }

  private read<T>(path: string, query: object = {}): Observable<T> {
    let params = new HttpParams();
    for (const [key, value] of Object.entries(query)) {
      if (
        value === undefined ||
        value === null ||
        (typeof value === 'string' && value.trim() === '')
      )
        continue;
      params = params.set(key, String(value));
    }
    return this.http.get<T>(`${this.baseUrl}${path}`, { params }).pipe(
      timeout(this.config.timeoutMs),
      retry({
        count: this.config.readRetryCount,
        delay: (error: unknown) =>
          isTemporaryReadFailure(error) ? timer(this.config.retryDelayMs) : throwError(() => error),
      }),
      catchError((error: unknown) => throwError(() => mapApiError(error))),
    );
  }

  private write<T>(method: 'POST' | 'PATCH', path: string, body: object): Observable<T> {
    return this.http.request<T>(method, `${this.baseUrl}${path}`, { body }).pipe(
      timeout(this.config.timeoutMs),
      catchError((error: unknown) => throwError(() => mapApiError(error))),
    );
  }
}
