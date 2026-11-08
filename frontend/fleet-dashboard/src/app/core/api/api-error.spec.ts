import { HttpErrorResponse } from '@angular/common/http';
import { TimeoutError } from 'rxjs';
import { API_ERROR_MESSAGES, ApiError, mapApiError } from './api-error';

function response(code: string, status = 400, details: unknown = []) {
  return new HttpErrorResponse({
    status,
    error: { code, message: 'Diagnostica backend', details },
  });
}

describe('Mapping errori Fleet API', () => {
  it.each(Object.keys(API_ERROR_MESSAGES))('localizza %s usando il codice', (code) => {
    const error = mapApiError(response(code));
    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe(code);
    expect(error.message).toBe(API_ERROR_MESSAGES[code as keyof typeof API_ERROR_MESSAGES]);
    expect(error.message).not.toContain('Diagnostica backend');
  });

  it('associa i dettagli ai campi e conserva più errori sullo stesso campo', () => {
    const details = [
      { field: 'plate', message: 'obbligatoria' },
      { field: 'plate', message: 'massimo 16 caratteri' },
      null,
      { field: 'status', message: 12 },
    ];
    const error = mapApiError(response('REQUEST_INVALID', 400, details));
    expect(error.details).toHaveLength(2);
    expect(error.fieldErrors.get('plate')).toEqual(['obbligatoria', 'massimo 16 caratteri']);
  });

  it.each([
    ['VEHICLE_PLATE_CONFLICT', 'plate'],
    ['VEHICLE_EXTERNAL_CODE_CONFLICT', 'externalCode'],
  ])('associa %s al campo %s', (code, field) => {
    const error = mapApiError(response(code, 409));
    expect(error.fieldErrors.get(field)).toEqual([error.message]);
  });

  it('non associa dettagli di JSON malformato ai campi', () => {
    expect(
      mapApiError(response('REQUEST_MALFORMED_JSON', 400, [{ field: 'plate', message: 'errore' }]))
        .details,
    ).toEqual([]);
  });

  it.each([null, 'HTML', [], { code: 12 }, { code: '__proto__' }, { code: 'toString' }])(
    'gestisce un payload non riconosciuto: %j',
    (body) => {
      const error = mapApiError(new HttpErrorResponse({ status: 502, error: body }));
      expect(error.message).toBe('Il servizio ha restituito un errore. Riprova più tardi.');
      expect(error.retryable).toBe(false);
    },
  );

  it.each([400, 404, 409])('usa un fallback per un codice sconosciuto HTTP %s', (status) => {
    expect(mapApiError(response('FUTURE_ERROR', status)).message).toBe(
      'Non è stato possibile completare la richiesta.',
    );
  });

  it('distingue errore di rete, timeout ed errore inatteso', () => {
    expect(mapApiError(new HttpErrorResponse({ status: 0 })).code).toBe('NETWORK_ERROR');
    expect(mapApiError(new TimeoutError()).code).toBe('REQUEST_TIMEOUT');
    expect(mapApiError(new Error()).retryable).toBe(false);
  });

  it('consente retry per SERVICE_UNAVAILABLE ma non per INTERNAL_ERROR o 503 sconosciuto', () => {
    expect(mapApiError(response('SERVICE_UNAVAILABLE', 503)).retryable).toBe(true);
    expect(mapApiError(response('INTERNAL_ERROR', 500)).retryable).toBe(false);
    expect(mapApiError(response('FUTURE_ERROR', 503)).retryable).toBe(false);
  });
});
