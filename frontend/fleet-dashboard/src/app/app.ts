import { afterRenderEffect, Component, ElementRef, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { Header } from './layout/header/header';

@Component({
  imports: [Header, RouterOutlet],
  selector: 'app-root',
  styleUrl: './app.css',
  templateUrl: './app.html',
})
export class App {
  private readonly content = viewChild.required<ElementRef<HTMLElement>>('mainContent');
  private readonly pageChanges = signal(0);
  private lastPath?: string;

  constructor() {
    const router = inject(Router);
    router.events
      .pipe(
        filter((event): event is NavigationEnd => event instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe((event) => {
        const path = event.urlAfterRedirects.split(/[?#]/)[0];
        if (path === this.lastPath) return;
        this.lastPath = path;
        // Preserve explicit anchors and leave filter/query updates at their current focus.
        if (!router.parseUrl(event.urlAfterRedirects).fragment) {
          this.pageChanges.update((count) => count + 1);
        }
      });

    afterRenderEffect(() => {
      if (this.pageChanges() > 0) this.content().nativeElement.focus();
    });
  }
}
