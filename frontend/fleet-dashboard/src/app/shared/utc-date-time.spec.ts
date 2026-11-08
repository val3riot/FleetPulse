import { formatUtcDateTime } from './utc-date-time';

describe('Date UTC visibili', () => {
  it.each([
    '2026-11-08T20:00:01Z',
    '2026-11-08T20:00:01.123456789Z',
    new Date('2026-11-08T20:00:01.999Z'),
  ])('formatta %s fino ai secondi senza arrotondare', (value) => {
    expect(formatUtcDateTime(value)).toBe('08/11/2026 20:00:01');
  });
  it('converte offset espliciti in UTC anche al cambio giorno', () => {
    expect(formatUtcDateTime('2026-11-09T00:30:15.999+02:00')).toBe('08/11/2026 22:30:15');
  });
});
