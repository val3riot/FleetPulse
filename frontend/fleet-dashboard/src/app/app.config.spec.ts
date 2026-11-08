import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { App } from './app';
import { appConfig } from './app.config';
import { TEST_DASHBOARD } from './testing/fleet-api.stub';

describe('Avvio con configurazione applicativa', () => {
  it('collega router e client HTTP reali senza contattare il backend', async () => {
    TestBed.configureTestingModule({
      imports: [App],
      providers: [...appConfig.providers, provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    await TestBed.inject(Router).navigateByUrl('/');
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/v1/dashboard').flush(TEST_DASHBOARD);
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('h1').textContent).toBe('Dashboard');
    expect(fixture.nativeElement.querySelector('[aria-current="page"]').textContent).toBe(
      'Dashboard',
    );
    fixture.destroy();
    http.verify();
  });
});
