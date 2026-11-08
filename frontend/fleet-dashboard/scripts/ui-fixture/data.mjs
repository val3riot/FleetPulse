// Synthetic, fixed UTC data. Each server instance receives an independent copy.
export const VEHICLE_ID = '97e194a8-64b3-4885-b1e6-25fd482f58c0';
export const ALERT_ID = 'f2607610-5100-4723-93d0-e6bbdcf00da0';
export const INSTANT = '2026-11-08T12:00:00.123456Z';
export function fixtureData() {
  const vehicles = [0, 1, 2].map((index) => ({
    id: VEHICLE_ID.slice(0, -1) + index,
    externalCode: `DEMO-00${index + 1}`,
    plate: `DEMO00${index + 1}`,
    status: index === 2 ? 'DISABLED' : 'ACTIVE',
    serviceIntervalKm: 15000,
    nextServiceAtKm: 100000,
    createdAt: `2026-11-08T10:00:0${index}Z`,
  }));
  const alerts = ['OPEN', 'ACKNOWLEDGED', 'CLOSED'].map((status, index) => ({
    id: ALERT_ID.slice(0, -1) + index,
    vehicleId: VEHICLE_ID,
    sourceMessageId: `d2607610-5100-4723-93d0-e6bbdcf00da${index}`,
    type: ['ENGINE_TEMPERATURE_HIGH', 'BATTERY_VOLTAGE_LOW', 'SERVICE_DUE'][index],
    severity: index === 2 ? 'MEDIUM' : 'HIGH',
    description: [
      'Temperatura motore oltre soglia',
      'Tensione batteria sotto soglia',
      'Manutenzione programmata raggiunta',
    ][index],
    status,
    createdAt: `2026-11-08T11:00:0${index}Z`,
    acknowledgedAt: index > 0 ? '2026-11-08T11:01:00Z' : null,
    closedAt: index === 2 ? '2026-11-08T11:02:00Z' : null,
  }));
  const samples = [0, 1, 2, 3].map((index) => ({
    id: index + 1,
    vehicleId: VEHICLE_ID,
    messageId: `b2607610-5100-4723-93d0-e6bbdcf00da${index}`,
    sequenceNumber: index,
    observedAt: `2026-11-08T12:00:0${index}.123456Z`,
    receivedAt: INSTANT,
    processedAt: INSTANT,
    speedKmh: 50 + index,
    engineTemperatureC: 90 + index,
    batteryVoltage: 12.6,
    odometerKm: 85000 + index,
    latitude: 41.9028,
    longitude: 12.4964,
  }));
  return { vehicles, alerts, samples };
}
