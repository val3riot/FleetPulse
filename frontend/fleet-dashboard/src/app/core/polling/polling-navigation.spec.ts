import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter, Router } from '@angular/router';
import { App } from '../../app';
import { routes } from '../../app.routes';
import { FleetApi } from '../api/fleet-api';
import { Dashboard } from '../../features/dashboard/dashboard';
import { VehicleState } from '../../features/vehicles/vehicle-state';
import { createFleetApiStub } from '../../testing/fleet-api.stub';

describe('Pausa conservata durante la navigazione della sessione', () => {
  let fixture: ComponentFixture<App>;
  let api: ReturnType<typeof createFleetApiStub>;
  beforeEach(() => {
    api = createFleetApiStub();
    TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter(routes), { provide: FleetApi, useValue: api }],
    });
    fixture = TestBed.createComponent(App);
    fixture.detectChanges();
  });
  afterEach(() => {
    fixture.destroy();
    vi.useRealTimers();
  });

  async function open(url: string): Promise<void> {
    await TestBed.inject(Router).navigateByUrl(url);
    await fixture.whenStable();
  }

  it('ritrova la dashboard in pausa con una sola lettura iniziale, senza nuovi cicli', async () => {
    await open('/dashboard');
    const original = fixture.debugElement.query(By.directive(Dashboard))
      .componentInstance as Dashboard;
    original.resource.togglePause();
    await fixture.whenStable();
    await open('/vehicles/id');
    const telemetry = fixture.debugElement.query(By.directive(VehicleState))
      .componentInstance as VehicleState;
    expect(telemetry.resource.paused()).toBe(false);
    await open('/dashboard');
    const returned = fixture.debugElement.query(By.directive(Dashboard))
      .componentInstance as Dashboard;
    expect(returned).not.toBe(original);
    expect(returned.resource.paused()).toBe(true);
    expect(returned.resource.data()?.totalVehicles).toBe(1);
    expect(api.getDashboard).toHaveBeenCalledTimes(2);
    expect(fixture.nativeElement.textContent).toContain('Riprendi dashboard');
    vi.useFakeTimers();
    await vi.advanceTimersByTimeAsync(15000);
    expect(api.getDashboard).toHaveBeenCalledTimes(2);
    vi.useRealTimers();
    const resume: HTMLButtonElement = fixture.nativeElement.querySelector(
      'app-polling-controls button:nth-child(2)',
    );
    resume.click();
    await fixture.whenStable();
    expect(returned.resource.paused()).toBe(false);
    expect(api.getDashboard).toHaveBeenCalledTimes(3);
    await open('/vehicles/id');
    await open('/dashboard');
    expect(
      (
        fixture.debugElement.query(By.directive(Dashboard)).componentInstance as Dashboard
      ).resource.paused(),
    ).toBe(false);
  });

  it('mantiene una preferenza telemetrica separata anche passando a un altro veicolo', async () => {
    await open('/dashboard');
    const dashboard = fixture.debugElement.query(By.directive(Dashboard))
      .componentInstance as Dashboard;
    dashboard.resource.togglePause();
    await fixture.whenStable();
    await open('/vehicles/first');
    const first = fixture.debugElement.query(By.directive(VehicleState))
      .componentInstance as VehicleState;
    first.resource.togglePause();
    await fixture.whenStable();
    await open('/dashboard');
    const returned = fixture.debugElement.query(By.directive(Dashboard))
      .componentInstance as Dashboard;
    returned.resource.togglePause();
    await fixture.whenStable();
    expect(returned.resource.paused()).toBe(false);
    await open('/vehicles/second');
    const second = fixture.debugElement.query(By.directive(VehicleState))
      .componentInstance as VehicleState;
    expect(second.resource.paused()).toBe(true);
    expect(second.resource.data()?.vehicleId).toBe('second');
    expect(api.getVehicleState).toHaveBeenCalledWith('second');
    expect(fixture.nativeElement.textContent).toContain('Riprendi telemetria');
  });
});
