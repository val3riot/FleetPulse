import { TEST_DASHBOARD } from '../../testing/fleet-api.stub';
import { DashboardResponse, RelevantAlertResponse } from '../../core/api/api-models';
import { sameDashboard } from './dashboard-equality';
const alert: RelevantAlertResponse = {
  id: 'first',
  vehicleId: 'vehicle',
  type: 'SERVICE_DUE',
  severity: 'HIGH',
  status: 'OPEN',
  description: 'Service',
  createdAt: '2026-11-08T20:00:00Z',
};
const snapshot: DashboardResponse = {
  ...TEST_DASHBOARD,
  relevantAlerts: [alert, { ...alert, id: 'second' }],
};
describe('Uguaglianza dashboard', () => {
  it('ignora nuove istanze e ordine delle proprietà JSON', () => {
    expect(sameDashboard(snapshot, JSON.parse(JSON.stringify(snapshot)))).toBe(true);
    expect(
      sameDashboard(snapshot, { ...snapshot, vehiclesByStatus: { DISABLED: 0, ACTIVE: 1 } }),
    ).toBe(true);
  });
  it.each(['totalVehicles', 'recentlyReportingVehicles', 'openAlerts'] as const)(
    'rileva variazioni del KPI %s',
    (key) => {
      expect(sameDashboard(snapshot, { ...snapshot, [key]: 99 })).toBe(false);
    },
  );
  it.each(['ACTIVE', 'DISABLED'] as const)('rileva variazione conteggio %s', (key) => {
    expect(
      sameDashboard(snapshot, {
        ...snapshot,
        vehiclesByStatus: { ...snapshot.vehiclesByStatus, [key]: 99 },
      }),
    ).toBe(false);
  });
  it.each([
    { id: 'other' },
    { vehicleId: 'other' },
    { type: 'BATTERY_VOLTAGE_LOW' },
    { severity: 'LOW' },
    { status: 'ACKNOWLEDGED' },
    { description: 'changed' },
    { createdAt: '2026-11-08T21:00:00Z' },
  ] as Partial<RelevantAlertResponse>[])('rileva variazione alert %j', (change) => {
    expect(
      sameDashboard(snapshot, {
        ...snapshot,
        relevantAlerts: [{ ...alert, ...change }, snapshot.relevantAlerts[1]],
      }),
    ).toBe(false);
  });
  it('rileva aggiunta, rimozione e riordinamento degli alert', () => {
    expect(
      sameDashboard(snapshot, {
        ...snapshot,
        relevantAlerts: [...snapshot.relevantAlerts, { ...alert, id: 'third' }],
      }),
    ).toBe(false);
    expect(sameDashboard(snapshot, { ...snapshot, relevantAlerts: [alert] })).toBe(false);
    expect(
      sameDashboard(snapshot, {
        ...snapshot,
        relevantAlerts: [...snapshot.relevantAlerts].reverse(),
      }),
    ).toBe(false);
  });
});
