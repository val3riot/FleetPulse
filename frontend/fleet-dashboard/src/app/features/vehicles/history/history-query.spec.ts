import { convertToParamMap } from '@angular/router';
import {
  defaultHistoryQuery,
  formatUtcInstant,
  historyQueryParams,
  parseHistoryQuery,
  utcInputToInstant,
  utcInstant,
  utcInstantToInput,
} from './history-query';

const defaults = defaultHistoryQuery(new Date('2026-11-08T20:00:00.123Z'));
const valid = historyQueryParams(defaults);

describe('Intervalli storico UTC', () => {
  it('propone le ultime 24 ore e rende espliciti tutti i parametri', () => {
    const parsed = parseHistoryQuery(convertToParamMap({}), defaults);
    expect(parsed.query).toEqual({
      from: '2026-11-07T20:00:00.123Z',
      to: '2026-11-08T20:00:00.123Z',
      page: 0,
      size: 50,
      sort: 'observedAt,desc',
    });
    expect(parsed.canonicalize).toBe(true);
    expect(parseHistoryQuery(convertToParamMap(valid), defaults).canonicalize).toBe(false);
  });
  it('interpreta i controlli come UTC senza conversioni del fuso del browser', () => {
    expect(utcInputToInstant('2026-03-29T02:30')).toBe('2026-03-29T02:30:00Z');
    expect(utcInputToInstant('2026-11-08T20:00:01.123')).toBe('2026-11-08T20:00:01.123Z');
    expect(utcInstantToInput('2026-11-08T20:00:01.123456Z')).toBe('2026-11-08T20:00:01');
    expect(formatUtcInstant('2026-11-08T20:00:01.123456Z')).toBe('08/11/2026 20:00:01');
  });
  it.each([
    '2026-02-29T00:00:00Z',
    '2026-13-01T00:00:00Z',
    '2026-00-01T00:00:00Z',
    '2026-01-00T00:00:00Z',
    '2026-01-01T24:00:00Z',
    '2026-01-01T00:60:00Z',
    '2026-01-01T00:00:60Z',
    '0000-01-01T00:00:00Z',
    '2026-01-01T00:00:00+01:00',
    '2026-01-01T00:00:00',
    'x',
  ])('rifiuta la data UTC non valida %s', (value) => expect(utcInstant(value)).toBeNull());
  it('accetta anni bisestili e confronta anche frazioni sotto il millisecondo', () => {
    expect(utcInstant('2024-02-29T00:00:00Z')).not.toBeNull();
    const from = '2026-11-08T20:00:00.123456789Z';
    const to = '2026-11-08T20:00:00.123456788Z';
    expect(parseHistoryQuery(convertToParamMap({ ...valid, from, to }), defaults).query).toBeNull();
    expect(
      parseHistoryQuery(convertToParamMap({ ...valid, from, to: from }), defaults).query?.from,
    ).toBe(from);
  });
  it.each([
    { from: '' },
    { to: '' },
    { from: ['2026-11-07T00:00:00Z', '2026-11-07T01:00:00Z'] },
    { page: '-1' },
    { page: '1.5' },
    { page: '2147483648' },
    { page: '' },
    { size: '0' },
    { size: '101' },
    { size: 'NaN' },
    { size: ['1', '2'] },
    { sort: 'sequenceNumber,asc' },
    { sort: '' },
  ])('non invia parametri non supportati: %j', (overrides) => {
    const parsed = parseHistoryQuery(convertToParamMap({ ...valid, ...overrides }), defaults);
    expect(parsed.query).toBeNull();
    expect(parsed.issues.length).toBeGreaterThan(0);
    expect(parsed.canonicalize).toBe(false);
  });
  it('non completa un intervallo parziale con date implicite', () => {
    expect(
      parseHistoryQuery(convertToParamMap({ from: defaults.from }), defaults).query,
    ).toBeNull();
    expect(parseHistoryQuery(convertToParamMap({ to: defaults.to }), defaults).query).toBeNull();
  });
});
