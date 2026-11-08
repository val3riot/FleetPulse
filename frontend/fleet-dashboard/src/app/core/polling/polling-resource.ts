import { DOCUMENT } from '@angular/common';
import { computed, DestroyRef, effect, inject, Signal, signal, untracked } from '@angular/core';
import { defer, finalize, Observable, Subscription, take, timer } from 'rxjs';
import { ApiError, mapApiError } from '../api/api-error';
import { POLLING_CONFIG } from './polling-config';
import { PollingPauseScope, PollingSessionPreferences } from './polling-session-preferences';

export interface PollingControlsState {
  readonly paused: Signal<boolean>;
  readonly hidden: Signal<boolean>;
  readonly suspended: Signal<boolean>;
  readonly stopped: Signal<boolean>;
  readonly loading: Signal<boolean>;
  readonly lastSuccessfulAt: Signal<Date | null>;
  refresh(): void;
  togglePause(): void;
}

export interface PollingResource<T> extends PollingControlsState {
  readonly data: Signal<T | null>;
  readonly error: Signal<ApiError | null>;
  readonly initialLoading: Signal<boolean>;
}

interface PollingOptions {
  readonly pauseScope?: PollingPauseScope;
  readonly key?: Signal<string>;
  readonly suspended?: Signal<boolean>;
  readonly clearDataOnError?: (error: ApiError) => boolean;
  readonly continueOnError?: (error: ApiError) => boolean;
}

/** One resource per component: completion-based polling, never a global cache. */
export function createPollingResource<T>(
  request: () => Observable<T>,
  options: PollingOptions = {},
): PollingResource<T> {
  const { intervalMs } = inject(POLLING_CONFIG);
  if (!Number.isSafeInteger(intervalMs) || intervalMs <= 0 || intervalMs > 2147483647) {
    throw new Error('Polling intervalMs must be a positive integer within the timer range');
  }
  const document = inject(DOCUMENT);
  const destroyRef = inject(DestroyRef);
  const data = signal<T | null>(null);
  const error = signal<ApiError | null>(null);
  const loading = signal(false);
  const completed = signal(false);
  const localPaused = signal(false);
  const preferences = options.pauseScope ? inject(PollingSessionPreferences) : null;
  const paused =
    preferences && options.pauseScope
      ? preferences.paused(options.pauseScope)
      : localPaused.asReadonly();
  const hidden = signal(document.hidden);
  const stopped = signal(false);
  const lastSuccessfulAt = signal<Date | null>(null);
  const suspended = computed(() => options.suspended?.() ?? false);
  const automaticAllowed = computed(() => !paused() && !hidden() && !suspended());
  let pendingRequest: Subscription | undefined;
  let scheduled: Subscription | undefined;
  let generation = 0;
  let disposed = false;
  let initialized = false;
  let currentKey: string | undefined;

  function cancelTimer(): void {
    scheduled?.unsubscribe();
    scheduled = undefined;
  }

  function schedule(): void {
    cancelTimer();
    if (disposed || stopped() || !automaticAllowed()) return;
    scheduled = timer(intervalMs).subscribe(() => {
      if (automaticAllowed()) read();
    });
  }

  function read(): void {
    if (disposed || loading() || suspended()) return;
    cancelTimer();
    const requestGeneration = generation;
    loading.set(true);
    pendingRequest = defer(request)
      .pipe(
        take(1),
        finalize(() => {
          // A cancelled request must never update a different vehicle's resource.
          if (disposed || requestGeneration !== generation) return;
          completed.set(true);
          loading.set(false);
          schedule();
        }),
      )
      .subscribe({
        next: (value) => {
          if (requestGeneration !== generation) return;
          data.set(value);
          error.set(null);
          lastSuccessfulAt.set(new Date());
        },
        error: (failure: unknown) => {
          if (requestGeneration !== generation) return;
          const mapped = mapApiError(failure);
          error.set(mapped);
          if (options.clearDataOnError?.(mapped)) data.set(null);
          stopped.set(!(options.continueOnError?.(mapped) ?? mapped.retryable));
        },
      });
  }

  effect(() => {
    const key = options.key?.();
    const allowed = automaticAllowed();
    const visible = !hidden();
    const blocked = suspended();
    untracked(() => {
      if (!initialized || key !== currentKey) {
        generation++;
        cancelTimer();
        pendingRequest?.unsubscribe();
        currentKey = key;
        initialized = true;
        data.set(null);
        error.set(null);
        lastSuccessfulAt.set(null);
        completed.set(false);
        loading.set(false);
        stopped.set(false);
        // Entering a page is an explicit navigation: show one snapshot even when paused.
        if (visible && !blocked) read();
      } else if (!completed() && visible && !blocked && !loading()) {
        read();
      } else if (allowed && !stopped()) {
        read();
      } else {
        cancelTimer();
      }
    });
  });

  const onVisibilityChange = () => hidden.set(document.hidden);
  document.addEventListener('visibilitychange', onVisibilityChange);
  destroyRef.onDestroy(() => {
    disposed = true;
    generation++;
    cancelTimer();
    pendingRequest?.unsubscribe();
    document.removeEventListener('visibilitychange', onVisibilityChange);
  });

  return {
    data: data.asReadonly(),
    error: error.asReadonly(),
    loading: loading.asReadonly(),
    initialLoading: computed(() => loading() && !completed()),
    lastSuccessfulAt: lastSuccessfulAt.asReadonly(),
    paused,
    hidden: hidden.asReadonly(),
    suspended,
    stopped: stopped.asReadonly(),
    refresh: () => {
      if (!initialized || disposed || loading() || suspended()) return;
      stopped.set(false);
      read();
    },
    togglePause: () => {
      if (disposed) return;
      if (preferences && options.pauseScope) preferences.toggle(options.pauseScope);
      else localPaused.update((value) => !value);
      if (paused()) cancelTimer();
    },
  };
}
