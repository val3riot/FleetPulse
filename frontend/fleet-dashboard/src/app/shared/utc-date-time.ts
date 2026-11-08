import { formatDate } from '@angular/common';
import { Pipe, PipeTransform } from '@angular/core';

export function formatUtcDateTime(value: string | Date): string {
  return formatDate(value, 'dd/MM/yyyy HH:mm:ss', 'en-US', 'UTC');
}

@Pipe({ name: 'utcDateTime' })
export class UtcDateTime implements PipeTransform {
  transform(value: string | Date): string {
    return formatUtcDateTime(value);
  }
}
