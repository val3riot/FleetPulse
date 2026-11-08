import { convertToParamMap } from '@angular/router';
import { alertQueryParams, DEFAULT_ALERT_QUERY, parseAlertQuery } from './alert-query';
import { TEST_ALERT } from '../../testing/fleet-api.stub';

describe('Query alert', () => {
  const parse = (values: Record<string, unknown>) => parseAlertQuery(convertToParamMap(values));
  it('usa tutti gli stati senza imporre un intervallo e normalizza solo i default mancanti', () => {
    expect(parse({})).toEqual({ query: DEFAULT_ALERT_QUERY, issues: [], canonicalize: true });
    expect(parse({ ...DEFAULT_ALERT_QUERY }).canonicalize).toBe(false);
  });
  it('combina tutti i filtri validi e conserva la precisione', () => {
    const query = {
      ...DEFAULT_ALERT_QUERY,
      vehicleId: TEST_ALERT.vehicleId,
      status: 'OPEN',
      type: 'ENGINE_TEMPERATURE_HIGH',
      severity: 'HIGH',
      from: '2026-11-08T20:00:00.123456Z',
      to: '2026-11-08T20:00:00.123456Z',
    };
    expect(parse(query).query).toEqual(query);
    expect(alertQueryParams({ ...DEFAULT_ALERT_QUERY, from: undefined })).toEqual(
      DEFAULT_ALERT_QUERY,
    );
  });
  it.each([{ from: '2026-11-08T20:00:00Z' }, { to: '2026-11-08T20:00:00Z' }])(
    'ammette un solo limite: %j',
    (limit) => expect(parse(limit).query).not.toBeNull(),
  );
  it.each([
    { page: '-1' },
    { page: '2147483648' },
    { page: '1.2' },
    { size: 0 },
    { size: 101 },
    { size: '1.5' },
    { status: 'UNKNOWN' },
    { severity: 'toString' },
    { type: 'UNKNOWN' },
    { vehicleId: 'abc' },
    { sort: 'id,desc' },
    { from: '2026-02-30T00:00:00Z' },
    { to: 'invalid' },
    { from: '2026-11-08T20:00:00.123457Z', to: '2026-11-08T20:00:00.123456Z' },
  ])('rifiuta %j', (values) => expect(parse(values).query).toBeNull());
  it.each(['vehicleId', 'status', 'type', 'severity', 'from', 'to', 'page', 'size', 'sort'])(
    'rifiuta vuoti o duplicati per %s',
    (field) => {
      expect(parse({ [field]: '' }).query).toBeNull();
      expect(parse({ [field]: ['a', 'b'] }).issues.join(' ')).toContain('una sola volta');
    },
  );
});
