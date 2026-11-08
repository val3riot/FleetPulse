import { Component, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';

@Component({
  selector: 'app-feature-preview',
  template: `
    <h1>{{ heading }}</h1>
    <p>{{ description }}</p>
    <p>Funzionalità in preparazione.</p>
  `,
})
export class FeaturePreview {
  private readonly route = inject(ActivatedRoute);
  readonly heading = this.route.snapshot.data['heading'] as string;
  readonly description = this.route.snapshot.data['description'] as string;
}
