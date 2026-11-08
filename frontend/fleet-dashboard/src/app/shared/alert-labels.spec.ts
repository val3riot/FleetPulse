import { alertLabels } from './alert-labels';

describe('Etichette alert non riconosciute', () => {
  it.each(['FUTURE_VALUE', '__proto__', 'toString'])(
    'mantiene il dato %s e mostra un fallback leggibile',
    (unknown) => {
      const alert = {
        type: unknown,
        severity: unknown,
        status: unknown,
        description: 'Descrizione server',
      };
      expect(alertLabels(alert)).toEqual({
        ...alert,
        typeLabel: 'Tipo non riconosciuto',
        severityLabel: 'Severità non riconosciuta',
        statusLabel: 'Stato non riconosciuto',
      });
    },
  );
});
