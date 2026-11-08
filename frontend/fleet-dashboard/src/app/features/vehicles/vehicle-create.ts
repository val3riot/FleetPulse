import {
  afterNextRender,
  Component,
  DestroyRef,
  ElementRef,
  inject,
  Injector,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { ApiError } from '../../core/api/api-error';
import { FleetApi } from '../../core/api/fleet-api';
import { VEHICLE_LIMITS } from './vehicle-limits';

const integer = (control: AbstractControl) =>
  control.value === null || Number.isSafeInteger(control.value) ? null : { integer: true };
type Field = 'externalCode' | 'plate' | 'serviceIntervalKm' | 'nextServiceAtKm';

@Component({
  selector: 'app-vehicle-create',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './vehicle-create.html',
  styleUrl: './vehicle-pages.css',
})
export class VehicleCreate {
  private readonly api = inject(FleetApi);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly formElement = viewChild.required<ElementRef<HTMLFormElement>>('vehicleForm');
  readonly limits = VEHICLE_LIMITS;
  readonly saving = signal(false);
  readonly error = signal<ApiError | null>(null);
  readonly uncertain = signal(false);
  readonly form = new FormGroup({
    externalCode: new FormControl('', {
      nonNullable: true,
      validators: [
        Validators.required,
        Validators.pattern(/\S/),
        Validators.maxLength(VEHICLE_LIMITS.externalCodeMaxLength),
      ],
    }),
    plate: new FormControl('', {
      nonNullable: true,
      validators: [
        Validators.required,
        Validators.pattern(/\S/),
        Validators.maxLength(VEHICLE_LIMITS.plateMaxLength),
      ],
    }),
    serviceIntervalKm: new FormControl<number | null>(null, [
      Validators.required,
      Validators.min(VEHICLE_LIMITS.serviceIntervalMinKm),
      Validators.max(VEHICLE_LIMITS.serviceIntervalMaxKm),
      integer,
    ]),
    nextServiceAtKm: new FormControl<number | null>(null, [
      Validators.required,
      Validators.min(VEHICLE_LIMITS.nextServiceMinKm),
      Validators.max(VEHICLE_LIMITS.nextServiceMaxKm),
      integer,
    ]),
  });

  fieldError(field: Field): string | null {
    const control = this.form.controls[field];
    if (!control.touched || !control.errors) return null;
    if (control.hasError('api')) return (control.getError('api') as string[]).join(' ');
    if (control.hasError('required') || control.hasError('pattern')) return 'Campo obbligatorio.';
    if (control.hasError('maxlength'))
      return `Massimo ${field === 'plate' ? this.limits.plateMaxLength : this.limits.externalCodeMaxLength} caratteri.`;
    if (control.hasError('integer')) return 'Inserisci un numero intero valido.';
    if (control.hasError('min'))
      return field === 'serviceIntervalKm'
        ? 'Inserisci un intero maggiore di zero.'
        : 'Inserisci un intero maggiore o uguale a zero.';
    const maximum =
      field === 'serviceIntervalKm'
        ? this.limits.serviceIntervalMaxKm
        : this.limits.nextServiceMaxKm;
    return `Il valore massimo è ${maximum} km.`;
  }

  submit(): void {
    if (this.saving() || this.uncertain()) return;
    this.form.markAllAsTouched();
    if (this.form.invalid) {
      this.focusFirstInvalidField();
      return;
    }
    const value = this.form.getRawValue();
    this.saving.set(true);
    this.error.set(null);
    this.form.disable({ emitEvent: false });
    this.api
      .createVehicle({
        externalCode: value.externalCode.trim(),
        plate: value.plate.trim(),
        serviceIntervalKm: value.serviceIntervalKm!,
        nextServiceAtKm: value.nextServiceAtKm!,
      })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          this.saving.set(false);
          if (this.form.disabled) this.form.enable({ emitEvent: false });
        }),
      )
      .subscribe({
        next: (vehicle) => {
          void this.router.navigate(['/vehicles', vehicle.id]);
        },
        error: (error: ApiError) => {
          this.error.set(error);
          this.uncertain.set(error.status === 0 || error.status >= 500);
          // Re-enable before assigning API errors: enabling recalculates validators.
          this.form.enable({ emitEvent: false });
          for (const [field, messages] of error.fieldErrors) {
            if (Object.hasOwn(this.form.controls, field)) {
              const control = this.form.controls[field as Field];
              control.setErrors({ ...control.errors, api: messages });
              control.markAsTouched();
            }
          }
          if (this.form.invalid) this.focusFirstInvalidField();
        },
      });
  }

  private focusFirstInvalidField(): void {
    afterNextRender(
      () => {
        if (this.destroyRef.destroyed) return;
        this.formElement()
          .nativeElement.querySelector<HTMLElement>('[aria-invalid="true"]')
          ?.focus();
      },
      { injector: this.injector },
    );
  }
}
