export const ALERT_TYPES: Record<string, string> = {
  ENGINE_TEMPERATURE_HIGH: 'Temperatura motore alta',
  BATTERY_VOLTAGE_LOW: 'Tensione batteria bassa',
  SERVICE_DUE: 'Manutenzione richiesta',
};
export const ALERT_SEVERITIES: Record<string, string> = {
  LOW: 'Bassa',
  MEDIUM: 'Media',
  HIGH: 'Alta',
  CRITICAL: 'Critica',
};
export const ALERT_STATUSES: Record<string, string> = {
  OPEN: 'Aperto',
  ACKNOWLEDGED: 'Preso in carico',
  CLOSED: 'Chiuso',
};

export function alertLabels<T extends { type: string; severity: string; status: string }>(
  alert: T,
) {
  return {
    ...alert,
    typeLabel: Object.hasOwn(ALERT_TYPES, alert.type)
      ? ALERT_TYPES[alert.type]
      : 'Tipo non riconosciuto',
    severityLabel: Object.hasOwn(ALERT_SEVERITIES, alert.severity)
      ? ALERT_SEVERITIES[alert.severity]
      : 'Severità non riconosciuta',
    statusLabel: Object.hasOwn(ALERT_STATUSES, alert.status)
      ? ALERT_STATUSES[alert.status]
      : 'Stato non riconosciuto',
  };
}
