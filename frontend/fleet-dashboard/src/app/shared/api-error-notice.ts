import { Component, input } from '@angular/core';
import { ApiError } from '../core/api/api-error';

@Component({
  selector: 'app-api-error-notice',
  template: `
    <section role="alert">
      <p>{{ error().message }}</p>
      @if (error().details.length) {
        <ul>
          @for (detail of error().details; track $index) {
            <li>{{ detail.field }}: {{ detail.message }}</li>
          }
        </ul>
      }
    </section>
  `,
})
export class ApiErrorNotice {
  readonly error = input.required<ApiError>();
}
