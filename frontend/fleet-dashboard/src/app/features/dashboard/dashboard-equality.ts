import { DashboardResponse } from '../../core/api/api-models';

/** Compare domain values, preserving the server's relevant-alert order. */
export function sameDashboard(previous: DashboardResponse, current: DashboardResponse): boolean {
  return (
    previous.totalVehicles === current.totalVehicles &&
    previous.vehiclesByStatus.ACTIVE === current.vehiclesByStatus.ACTIVE &&
    previous.vehiclesByStatus.DISABLED === current.vehiclesByStatus.DISABLED &&
    previous.recentlyReportingVehicles === current.recentlyReportingVehicles &&
    previous.openAlerts === current.openAlerts &&
    previous.relevantAlerts.length === current.relevantAlerts.length &&
    previous.relevantAlerts.every((alert, index) => {
      const next = current.relevantAlerts[index];
      return (
        alert.id === next.id &&
        alert.vehicleId === next.vehicleId &&
        alert.type === next.type &&
        alert.severity === next.severity &&
        alert.status === next.status &&
        alert.description === next.description &&
        alert.createdAt === next.createdAt
      );
    })
  );
}
