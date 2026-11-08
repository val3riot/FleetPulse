import { ParamMap } from '@angular/router';
import { AlertQuery } from '../../core/api/api-models';
import { utcInstant } from '../../shared/utc-date-input';
import { ALERT_TYPES, ALERT_SEVERITIES, ALERT_STATUSES } from '../../shared/alert-labels';

export interface AppliedAlertQuery extends AlertQuery {
  page: number;
  size: number;
  sort: 'createdAt,asc' | 'createdAt,desc';
}
export const DEFAULT_ALERT_QUERY: AppliedAlertQuery = { page: 0, size: 50, sort: 'createdAt,desc' };
export const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const fields = [
  'vehicleId',
  'status',
  'type',
  'severity',
  'from',
  'to',
  'page',
  'size',
  'sort',
] as const;
export function alertQueryParams(query: AppliedAlertQuery): Record<string, string | number> {
  return Object.fromEntries(Object.entries(query).filter(([, value]) => value !== undefined));
}
export function alertQuerySignature(params: ParamMap): string {
  return JSON.stringify(fields.map((field) => params.getAll(field)));
}
export function parseAlertQuery(params: ParamMap): {
  query: AppliedAlertQuery | null;
  issues: string[];
  canonicalize: boolean;
} {
  const issues: string[] = [];
  for (const field of fields) {
    if (params.getAll(field).length > 1)
      issues.push(`Il parametro ${field} deve comparire una sola volta.`);
    if (params.has(field) && params.get(field) === '')
      issues.push(`Il parametro ${field} non può essere vuoto.`);
  }
  const vehicleId = params.get('vehicleId') ?? undefined;
  if (vehicleId && !UUID_PATTERN.test(vehicleId))
    issues.push('Identificativo veicolo non valido: usa un UUID.');
  const enums = { status: ALERT_STATUSES, type: ALERT_TYPES, severity: ALERT_SEVERITIES };
  for (const [field, values] of Object.entries(enums)) {
    const value = params.get(field);
    if (value && !Object.hasOwn(values, value)) issues.push(`Il filtro ${field} non è supportato.`);
  }
  const from = params.get('from') ?? undefined;
  const to = params.get('to') ?? undefined;
  const start = from === undefined ? null : utcInstant(from);
  const end = to === undefined ? null : utcInstant(to);
  if (from !== undefined && start === null)
    issues.push(
      'Inizio non valido: correggi la data nei filtri o usa il suffisso Z nel parametro URL.',
    );
  if (to !== undefined && end === null)
    issues.push(
      'Fine non valida: correggi la data nei filtri o usa il suffisso Z nel parametro URL.',
    );
  if (start !== null && end !== null && start > end)
    issues.push("L'inizio deve precedere o coincidere con la fine.");
  const pageText = params.get('page') ?? '0';
  const sizeText = params.get('size') ?? '50';
  const page = Number(pageText);
  const size = Number(sizeText);
  if (!/^\d+$/.test(pageText) || !Number.isSafeInteger(page) || page > 2147483647)
    issues.push('La pagina deve essere un intero da 0 a 2147483647.');
  if (!/^\d+$/.test(sizeText) || !Number.isSafeInteger(size) || size < 1 || size > 100)
    issues.push('La dimensione pagina deve essere un intero da 1 a 100.');
  const sort = params.get('sort') ?? DEFAULT_ALERT_QUERY.sort;
  if (sort !== 'createdAt,asc' && sort !== 'createdAt,desc')
    issues.push('Ordinamento non supportato.');
  return {
    query: issues.length
      ? null
      : {
          vehicleId,
          status: (params.get('status') ?? undefined) as AlertQuery['status'],
          type: (params.get('type') ?? undefined) as AlertQuery['type'],
          severity: (params.get('severity') ?? undefined) as AlertQuery['severity'],
          from,
          to,
          page,
          size,
          sort: sort as AppliedAlertQuery['sort'],
        },
    issues,
    canonicalize: !issues.length && ['page', 'size', 'sort'].some((field) => !params.has(field)),
  };
}
