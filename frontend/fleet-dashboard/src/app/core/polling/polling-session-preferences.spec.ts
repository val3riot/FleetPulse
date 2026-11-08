import { TestBed } from '@angular/core/testing';
import { PollingSessionPreferences } from './polling-session-preferences';

describe('Preferenze polling della sessione applicativa', () => {
  it('conserva la pausa condivisa per ambito senza accoppiare dashboard e telemetria', () => {
    const preferences = TestBed.inject(PollingSessionPreferences);
    const dashboard = preferences.paused('dashboard');
    const telemetry = preferences.paused('telemetry');
    preferences.toggle('dashboard');
    expect(dashboard()).toBe(true);
    expect(telemetry()).toBe(false);
    expect(TestBed.inject(PollingSessionPreferences).paused('dashboard')()).toBe(true);
    preferences.toggle('telemetry');
    preferences.toggle('dashboard');
    expect(dashboard()).toBe(false);
    expect(telemetry()).toBe(true);
  });

  it('una nuova istanza applicativa riparte con aggiornamento attivo', () => {
    TestBed.inject(PollingSessionPreferences).toggle('dashboard');
    TestBed.resetTestingModule();
    expect(TestBed.inject(PollingSessionPreferences).paused('dashboard')()).toBe(false);
    expect(TestBed.inject(PollingSessionPreferences).paused('telemetry')()).toBe(false);
  });
});
