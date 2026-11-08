import { vi } from 'vitest';
import { of } from 'rxjs';
import {
  DashboardResponse,
  AlertQuery,
  AlertTargetStatus,
  MaintenanceAlertResponse,
  TelemetryResponse,
  TelemetryQuery,
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

export const TEST_TELEMETRY: TelemetryResponse = {
  id: 1,
  messageId: 'message-1',
  vehicleId: 'id',
  sequenceNumber: 42,
  observedAt: '2026-11-08T20:00:00.123456Z',
  receivedAt: '2026-11-08T20:00:01Z',
  processedAt: '2026-11-08T20:00:02Z',
  speedKmh: 72.4,
  engineTemperatureC: 91.8,
  batteryVoltage: 12.6,
  odometerKm: 85312,
  latitude: 41.9028,
  longitude: 12.4964,
};
export function telemetryPage(query: TelemetryQuery): Page<TelemetryResponse> {
  return {
    content: [TEST_TELEMETRY],
    page: query.page ?? 0,
    size: query.size ?? 50,
    totalElements: 1,
    totalPages: 1,
    first: true,
    last: true,
  };
}

export const TEST_ALERT: MaintenanceAlertResponse = {
  id: 'f2607610-5100-4723-93d0-e6bbdcf00da0',
  vehicleId: '97e194a8-64b3-4885-b1e6-25fd482f58c0',
  sourceMessageId: 'message',
  type: 'ENGINE_TEMPERATURE_HIGH',
  severity: 'HIGH',
  description: 'Temperatura motore oltre soglia',
  status: 'OPEN',
  createdAt: '2026-11-08T20:00:00.123456Z',
  acknowledgedAt: null,
  closedAt: null,
};
export function alertsPage(query: AlertQuery = {}): Page<MaintenanceAlertResponse> {
  return {
    content: [TEST_ALERT],
    page: query.page ?? 0,
    size: query.size ?? 50,
    totalElements: 1,
    totalPages: 1,
    first: true,
    last: true,
  };
}

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
    getAlerts: vi.fn((query: AlertQuery = {}) => of(alertsPage(query))),
    getAlert: vi.fn((id: string) => of({ ...TEST_ALERT, id })),
    changeAlertStatus: vi.fn((id: string, status: AlertTargetStatus) =>
      of({
        ...TEST_ALERT,
        id,
        status,
        acknowledgedAt: status === 'ACKNOWLEDGED' ? '2026-11-08T20:01:00Z' : null,
        closedAt: status === 'CLOSED' ? '2026-11-08T20:02:00Z' : null,
      }),
    ),
    getTelemetry: vi.fn((_id: string, query: TelemetryQuery) => of(telemetryPage(query))),
    getDashboard: vi.fn(() => of(TEST_DASHBOARD)),
    getVehicleState: vi.fn((vehicleId: string) => of({ ...TEST_STATE, vehicleId })),
    getVehicles: vi.fn(() => of(page)),
    getVehicle: vi.fn((id: string) => of({ ...TEST_VEHICLE, id })),
  };
}
