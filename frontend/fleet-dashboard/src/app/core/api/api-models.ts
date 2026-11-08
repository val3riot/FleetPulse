export type VehicleStatus = 'ACTIVE' | 'DISABLED';
export type AlertStatus = 'OPEN' | 'ACKNOWLEDGED' | 'CLOSED';
export type AlertTargetStatus = Exclude<AlertStatus, 'OPEN'>;
export type AlertType = 'ENGINE_TEMPERATURE_HIGH' | 'BATTERY_VOLTAGE_LOW' | 'SERVICE_DUE';
export type AlertSeverity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type TimeSort = 'createdAt,asc' | 'createdAt,desc';

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface CreateVehicleRequest {
  externalCode: string;
  plate: string;
  serviceIntervalKm: number;
  nextServiceAtKm: number;
}

export interface VehicleResponse extends CreateVehicleRequest {
  id: string;
  status: VehicleStatus;
  createdAt: string;
}

export interface TelemetryValues {
  speedKmh: number;
  engineTemperatureC: number;
  batteryVoltage: number;
  odometerKm: number;
  latitude: number;
  longitude: number;
}

export interface VehicleStateResponse extends TelemetryValues {
  vehicleId: string;
  lastSequenceNumber: number;
  lastSeenAt: string;
  stale: boolean;
}

export interface TelemetryResponse extends TelemetryValues {
  id: number;
  messageId: string;
  vehicleId: string;
  sequenceNumber: number;
  observedAt: string;
  receivedAt: string;
  processedAt: string;
}

export interface RelevantAlertResponse {
  id: string;
  vehicleId: string;
  type: AlertType;
  severity: AlertSeverity;
  status: AlertStatus;
  description: string;
  createdAt: string;
}

export interface MaintenanceAlertResponse extends RelevantAlertResponse {
  sourceMessageId: string;
  acknowledgedAt: string | null;
  closedAt: string | null;
}

export interface DashboardResponse {
  totalVehicles: number;
  vehiclesByStatus: Record<VehicleStatus, number>;
  recentlyReportingVehicles: number;
  openAlerts: number;
  relevantAlerts: RelevantAlertResponse[];
}

export interface PaginationQuery {
  page?: number;
  size?: number;
}

export interface VehicleQuery extends PaginationQuery {
  query?: string;
  status?: VehicleStatus;
  sort?: `${'createdAt' | 'externalCode' | 'plate' | 'status'},${'asc' | 'desc'}`;
}

export interface TelemetryQuery extends PaginationQuery {
  from: string;
  to: string;
  sort?: 'observedAt,asc' | 'observedAt,desc';
}

export interface AlertQuery extends PaginationQuery {
  vehicleId?: string;
  status?: AlertStatus;
  type?: AlertType;
  severity?: AlertSeverity;
  from?: string;
  to?: string;
  sort?: TimeSort;
}

export interface ValidationErrorDetail {
  field: string;
  message: string;
}

export interface ApiErrorResponse {
  timestamp: string;
  status: number;
  code: string;
  message: string;
  path: string;
  details: ValidationErrorDetail[];
}
