import { utcInstant } from '../../../shared/utc-date-input';
export { utcInstant, utcInputToInstant, utcInstantToInput } from '../../../shared/utc-date-input';
import { formatUtcDateTime } from '../../../shared/utc-date-time';
import { ParamMap } from '@angular/router';
import { TelemetryQuery } from '../../../core/api/api-models';

export interface HistoryQuery extends TelemetryQuery {
  page: number;
  size: number;
  sort: 'observedAt,asc' | 'observedAt,desc';
}
export interface HistoryQueryIssue {
  field: string;
  message: string;
}
export const HISTORY_PAGE_LIMIT = 100;
const queryFields = ['from', 'to', 'page', 'size', 'sort'] as const;

export function defaultHistoryQuery(now: Date): HistoryQuery {
  return {
    from: new Date(now.getTime() - 86400000).toISOString(),
    to: now.toISOString(),
    page: 0,
    size: 50,
    sort: 'observedAt,desc',
  };
}

export function historyQueryParams(query: HistoryQuery): Record<string, string | number> {
  return { from: query.from, to: query.to, page: query.page, size: query.size, sort: query.sort };
}

export function historyQuerySignature(vehicleId: string, params: ParamMap): string {
  return JSON.stringify([vehicleId, ...queryFields.map((field) => params.getAll(field))]);
}

export function parseHistoryQuery(
  params: ParamMap,
  defaults: HistoryQuery,
): { query: HistoryQuery | null; issues: HistoryQueryIssue[]; canonicalize: boolean } {
  const issues: HistoryQueryIssue[] = [];
  for (const field of queryFields) {
    if (params.getAll(field).length > 1)
      issues.push({ field, message: `Il parametro ${field} deve comparire una sola volta.` });
  }
  const initialRange = !params.has('from') && !params.has('to');
  const from = initialRange ? defaults.from : (params.get('from') ?? '');
  const to = initialRange ? defaults.to : (params.get('to') ?? '');
  const start = utcInstant(from);
  const end = utcInstant(to);
  if (start === null)
    issues.push({
      field: 'from',
      message:
        'Inizio obbligatorio: correggi la data nei filtri o usa il suffisso Z nel parametro URL.',
    });
  if (end === null)
    issues.push({
      field: 'to',
      message:
        'Fine obbligatoria: correggi la data nei filtri o usa il suffisso Z nel parametro URL.',
    });
  if (start !== null && end !== null && start > end)
    issues.push({
      field: 'to',
      message: "L'inizio dell'intervallo deve precedere o coincidere con la fine.",
    });
  const pageText = params.get('page') ?? '0';
  const sizeText = params.get('size') ?? '50';
  const page = Number(pageText);
  const size = Number(sizeText);
  if (!/^\d+$/.test(pageText) || !Number.isSafeInteger(page) || page > 2147483647)
    issues.push({ field: 'page', message: 'La pagina deve essere un intero da 0 a 2147483647.' });
  if (
    !/^\d+$/.test(sizeText) ||
    !Number.isSafeInteger(size) ||
    size < 1 ||
    size > HISTORY_PAGE_LIMIT
  )
    issues.push({
      field: 'size',
      message: 'La dimensione pagina deve essere un intero da 1 a 100.',
    });
  const sort = params.get('sort') ?? 'observedAt,desc';
  if (sort !== 'observedAt,asc' && sort !== 'observedAt,desc')
    issues.push({
      field: 'sort',
      message: 'Ordinamento non supportato: usa la data di rilevazione crescente o decrescente.',
    });
  return {
    query: issues.length ? null : { from, to, page, size, sort: sort as HistoryQuery['sort'] },
    issues,
    canonicalize: !issues.length && queryFields.some((field) => !params.has(field)),
  };
}

export function formatUtcInstant(value: string): string {
  return formatUtcDateTime(value);
}
