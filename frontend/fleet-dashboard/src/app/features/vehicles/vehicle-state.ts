import { UtcDateTime } from '../../shared/utc-date-time';
import { Component, computed, inject, input } from '@angular/core';
import { FleetApi } from '../../core/api/fleet-api';
import { createPollingResource } from '../../core/polling/polling-resource';
import { ApiErrorNotice } from '../../shared/api-error-notice';
import { PollingControls } from '../../shared/polling-controls';
import { formatMetric, formatCoordinate } from '../../shared/telemetry-format';

@Component({
  selector: 'app-vehicle-state',
  imports: [UtcDateTime, ApiErrorNotice, PollingControls],
  templateUrl: './vehicle-state.html',
  styleUrl: './vehicle-state.css',
})
export class VehicleState {
  readonly vehicleId = input.required<string>();
  readonly suspended = input(false);
  private readonly api = inject(FleetApi);
  readonly resource = createPollingResource(() => this.api.getVehicleState(this.vehicleId()), {
    pauseScope: 'telemetry',
    key: this.vehicleId,
    suspended: this.suspended,
    clearDataOnError: (error) =>
      ['VEHICLE_STATE_NOT_AVAILABLE', 'VEHICLE_NOT_FOUND'].includes(error.code),
    continueOnError: (error) => error.code === 'VEHICLE_STATE_NOT_AVAILABLE' || error.retryable,
  });
  readonly noTelemetry = computed(
    () => this.resource.error()?.code === 'VEHICLE_STATE_NOT_AVAILABLE',
  );
  readonly failure = computed(() => (this.noTelemetry() ? null : this.resource.error()));
  readonly statusMessage = computed(() => {
    if (this.resource.initialLoading()) return 'Caricamento telemetria…';
    if (this.noTelemetry()) return 'Nessuna telemetria disponibile per questo veicolo.';
    if (this.failure()) return '';
    return this.resource.data() ? 'Ultima misura disponibile.' : '';
  });
  readonly readings = computed(() => {
    const data = this.resource.data();
    if (!data) return [];
    return [
      { label: 'Velocità', value: formatMetric(data.speedKmh), unit: 'km/h' },
      { label: 'Temperatura motore', value: formatMetric(data.engineTemperatureC), unit: '°C' },
      { label: 'Tensione batteria', value: formatMetric(data.batteryVoltage), unit: 'V' },
      { label: 'Odometro', value: formatMetric(data.odometerKm), unit: 'km' },
      { label: 'Latitudine', value: formatCoordinate(data.latitude), unit: '°' },
      { label: 'Longitudine', value: formatCoordinate(data.longitude), unit: '°' },
    ];
  });
}
