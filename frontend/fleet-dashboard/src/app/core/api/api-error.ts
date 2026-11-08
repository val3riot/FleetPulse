import { HttpErrorResponse } from '@angular/common/http';
import { TimeoutError } from 'rxjs';
import { ValidationErrorDetail } from './api-models';

export const API_ERROR_MESSAGES = {
  REQUEST_INVALID: 'Controlla i dati inseriti.',
  REQUEST_MALFORMED_JSON: 'Non è stato possibile inviare la richiesta. Verifica i dati.',
  REQUEST_INVALID_TIME_RANGE: "L'inizio dell'intervallo deve precedere o coincidere con la fine.",
  REQUEST_METHOD_NOT_ALLOWED: 'Operazione non supportata.',
  REQUEST_UNSUPPORTED_MEDIA_TYPE: 'Formato della richiesta non supportato.',
  VEHICLE_NOT_FOUND: 'Veicolo non trovato.',
  VEHICLE_STATE_NOT_AVAILABLE: 'Nessuna telemetria disponibile per questo veicolo.',
  VEHICLE_PLATE_CONFLICT: 'La targa è già assegnata a un altro veicolo.',
  VEHICLE_EXTERNAL_CODE_CONFLICT: 'Il codice esterno è già assegnato a un altro veicolo.',
  ALERT_NOT_FOUND: 'Alert non trovato.',
  ALERT_STATUS_TRANSITION_CONFLICT:
    'Lo stato dell’alert è cambiato o la transizione non è consentita. Aggiorna i dati.',
  SERVICE_UNAVAILABLE: 'Servizio temporaneamente non disponibile. Riprova tra poco.',
  INTERNAL_ERROR: 'Si è verificato un errore interno. Riprova più tardi.',
} as const;

export class ApiError extends Error {
  override readonly name = 'ApiError';
  readonly fieldErrors: ReadonlyMap<string, readonly string[]>;

  constructor(
    readonly code: string,
    readonly status: number,
    message: string,
    readonly retryable: boolean,
    readonly details: readonly ValidationErrorDetail[] = [],
  ) {
    super(message);
    const fields = new Map<string, string[]>();
    for (const detail of details) {
      fields.set(detail.field, [...(fields.get(detail.field) ?? []), detail.message]);
    }
    this.fieldErrors = fields;
  }
}

export function isTemporaryReadFailure(error: unknown): boolean {
  if (error instanceof TimeoutError) return true;
  if (!(error instanceof HttpErrorResponse)) return false;
  if (error.status === 0) return true;
  return isRecord(error.error) && error.error['code'] === 'SERVICE_UNAVAILABLE';
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function mapApiError(error: unknown): ApiError {
  if (error instanceof ApiError) return error;
  if (error instanceof TimeoutError) {
    return new ApiError(
      'REQUEST_TIMEOUT',
      0,
      'Il servizio non ha risposto in tempo. Riprova.',
      true,
    );
  }
  if (!(error instanceof HttpErrorResponse)) {
    return new ApiError('UNKNOWN_ERROR', 0, 'Si è verificato un errore inatteso.', false);
  }
  if (error.status === 0) {
    return new ApiError(
      'NETWORK_ERROR',
      0,
      'Impossibile contattare il servizio. Verifica la connessione.',
      true,
    );
  }
  const body: unknown = error.error;
  const code = isRecord(body) && typeof body['code'] === 'string' ? body['code'] : 'UNKNOWN_ERROR';
  const known = Object.hasOwn(API_ERROR_MESSAGES, code);
  const message = known
    ? API_ERROR_MESSAGES[code as keyof typeof API_ERROR_MESSAGES]
    : error.status >= 500
      ? 'Il servizio ha restituito un errore. Riprova più tardi.'
      : error.status >= 400
        ? 'Non è stato possibile completare la richiesta.'
        : 'La risposta del servizio non è valida.';
  let details: ValidationErrorDetail[] = [];
  if (code === 'REQUEST_INVALID' && isRecord(body) && Array.isArray(body['details'])) {
    details = body['details'].filter(
      (item): item is ValidationErrorDetail =>
        isRecord(item) && typeof item['field'] === 'string' && typeof item['message'] === 'string',
    );
  }
  if (code === 'VEHICLE_PLATE_CONFLICT' || code === 'VEHICLE_EXTERNAL_CODE_CONFLICT') {
    details = [{ field: code === 'VEHICLE_PLATE_CONFLICT' ? 'plate' : 'externalCode', message }];
  }
  return new ApiError(code, error.status, message, isTemporaryReadFailure(error), details);
}
