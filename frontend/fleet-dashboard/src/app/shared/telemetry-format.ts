const metrics = new Intl.NumberFormat('it-IT', { maximumFractionDigits: 2 });
const coordinates = new Intl.NumberFormat('it-IT', { maximumFractionDigits: 6 });

export function formatMetric(value: number): string {
  return metrics.format(value);
}
export function formatCoordinate(value: number): string {
  return coordinates.format(value);
}
