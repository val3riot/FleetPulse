/** UTC only; compare nanoseconds without truncating PostgreSQL/API sub-millisecond precision. */
export function utcInstant(value: string): bigint | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?Z$/.exec(value);
  if (!match) return null;
  const [year, month, day, hour, minute, second] = match.slice(1, 7).map(Number);
  if (year < 1 || hour > 23 || minute > 59 || second > 59) return null;
  const date = new Date(0);
  date.setUTCFullYear(year, month - 1, day);
  date.setUTCHours(hour, minute, second, 0);
  if (
    date.getUTCFullYear() !== year ||
    date.getUTCMonth() !== month - 1 ||
    date.getUTCDate() !== day
  )
    return null;
  return BigInt(date.getTime()) * 1000000n + BigInt((match[7] ?? '').padEnd(9, '0'));
}

export function utcInputToInstant(value: string): string | null {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,3})?)?$/.test(value)) return null;
  const withSeconds = value.length === 16 ? value + ':00' : value;
  const instant = withSeconds + 'Z';
  return utcInstant(instant) === null ? null : instant;
}

export function utcInstantToInput(value: string): string {
  if (utcInstant(value) === null) return '';
  // Display seconds; the applied query keeps the full original instant.
  return value.slice(0, 19);
}
