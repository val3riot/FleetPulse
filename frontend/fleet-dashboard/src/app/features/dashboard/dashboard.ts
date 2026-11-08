import { alertLabels } from '../../shared/alert-labels';
import { UtcDateTime } from '../../shared/utc-date-time';
import { Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { FleetApi } from '../../core/api/fleet-api';
import { createPollingResource } from '../../core/polling/polling-resource';
import { ApiErrorNotice } from '../../shared/api-error-notice';
import { PollingControls } from '../../shared/polling-controls';

import { sameDashboard } from './dashboard-equality';

const counts = new Intl.NumberFormat('it-IT', { maximumFractionDigits: 0 });
@Component({
  selector: 'app-dashboard',
  imports: [UtcDateTime, RouterLink, ApiErrorNotice, PollingControls],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.css',
})
export class Dashboard {
  private readonly api = inject(FleetApi);
  readonly resource = createPollingResource(() => this.api.getDashboard(), {
    pauseScope: 'dashboard',
    equal: sameDashboard,
  });
  readonly statusMessage = computed(() => {
    if (this.resource.initialLoading()) return 'Caricamento dashboard…';
    if (this.resource.error()) return '';
    return this.resource.data() ? 'Panoramica della flotta disponibile.' : '';
  });
  readonly kpis = computed(() => {
    const data = this.resource.data();
    if (!data) return [];
    return [
      { label: 'Veicoli totali', value: data.totalVehicles },
      { label: 'Veicoli attivi', value: data.vehiclesByStatus.ACTIVE },
      { label: 'Veicoli disabilitati', value: data.vehiclesByStatus.DISABLED },
      { label: 'Veicoli con rilevazioni recenti', value: data.recentlyReportingVehicles },
      { label: 'Alert aperti', value: data.openAlerts },
    ].map((item) => ({ ...item, value: counts.format(item.value) }));
  });
  readonly alerts = computed(() => (this.resource.data()?.relevantAlerts ?? []).map(alertLabels));
}
