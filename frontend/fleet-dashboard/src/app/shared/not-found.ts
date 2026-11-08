import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-not-found',
  imports: [RouterLink],
  template: `
    <h1>Pagina non trovata</h1>
    <p>L'indirizzo richiesto non corrisponde a una pagina di FleetPulse.</p>
    <a routerLink="/dashboard">Torna alla dashboard</a>
  `,
})
export class NotFound {}
