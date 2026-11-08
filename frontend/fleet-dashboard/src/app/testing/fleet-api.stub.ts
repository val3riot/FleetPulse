import { vi } from 'vitest';
import { of } from 'rxjs';
import {
  DashboardResponse,
  Page,
  VehicleResponse,
  VehicleStateResponse,
} from '../core/api/api-models';

export const TEST_VEHICLE: VehicleResponse = {
  id: 'id',
  externalCode: 'VAN-001',
  plate: 'FP001AA',
  status: 'ACTIVE',
  serviceIntervalKm: 15000,
  nextServiceAtKm: 90000,
  createdAt: '2026-08-01T10:00:00Z',
};

export const TEST_DASHBOARD: DashboardResponse = {
  totalVehicles: 1,
  vehiclesByStatus: { ACTIVE: 1, DISABLED: 0 },
  recentlyReportingVehicles: 0,
  openAlerts: 0,
  relevantAlerts: [],
};
export const TEST_STATE: VehicleStateResponse = {
  vehicleId: 'id',
  lastSequenceNumber: 42,
  lastSeenAt: '2026-08-01T10:00:00Z',
  stale: true,
  speedKmh: 72.4,
  engineTemperatureC: 91.8,
  batteryVoltage: 12.6,
  odometerKm: 85312,
  latitude: 41.9028,
  longitude: 12.4964,
};

export function createFleetApiStub() {
  const page: Page<VehicleResponse> = {
    content: [TEST_VEHICLE],
    page: 0,
    size: 20,
    totalElements: 1,
    totalPages: 1,
    first: true,
    last: true,
  };
  return {
    getDashboard: vi.fn(() => of(TEST_DASHBOARD)),
    getVehicleState: vi.fn((vehicleId: string) => of({ ...TEST_STATE, vehicleId })),
    getVehicles: vi.fn(() => of(page)),
    getVehicle: vi.fn((id: string) => of({ ...TEST_VEHICLE, id })),
  };
}
