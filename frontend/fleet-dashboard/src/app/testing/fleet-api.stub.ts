import { vi } from 'vitest';
import { of } from 'rxjs';
import { Page, VehicleResponse } from '../core/api/api-models';

export const TEST_VEHICLE: VehicleResponse = {
  id: 'id',
  externalCode: 'VAN-001',
  plate: 'FP001AA',
  status: 'ACTIVE',
  serviceIntervalKm: 15000,
  nextServiceAtKm: 90000,
  createdAt: '2026-08-01T10:00:00Z',
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
    getVehicles: vi.fn(() => of(page)),
    getVehicle: vi.fn(() => of(TEST_VEHICLE)),
  };
}
