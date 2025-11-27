import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class TouchDetectionService {
  private touchDeviceSubject: BehaviorSubject<boolean>;
  public touchDevice$: Observable<boolean>;

  constructor() {
    // Detect touch capability on initialization
    const isTouchDevice = this.detectTouchCapability();
    this.touchDeviceSubject = new BehaviorSubject<boolean>(isTouchDevice);
    this.touchDevice$ = this.touchDeviceSubject.asObservable();

    // Re-detect on window resize (for hybrid devices)
    window.addEventListener('resize', () => {
      const newTouchCapability = this.detectTouchCapability();
      if (newTouchCapability !== this.touchDeviceSubject.value) {
        this.touchDeviceSubject.next(newTouchCapability);
      }
    });
  }

  /**
   * Detects if the current device has touch capability
   * Uses multiple detection methods for accuracy
   */
  private detectTouchCapability(): boolean {
    // Method 1: Media query - pointer: coarse (primary touch input)
    const hasCoarsePointer = window.matchMedia('(pointer: coarse)').matches;

    // Method 2: Media query - hover capability (touch devices typically don't hover)
    const cannotHover = window.matchMedia('(hover: none)').matches;

    // Method 3: Touch events API support
    const hasTouchEvents = 'ontouchstart' in window || navigator.maxTouchPoints > 0;

    // Device is touch if:
    // - Has coarse pointer (finger/stylus) OR
    // - Cannot hover AND supports touch events
    return hasCoarsePointer || (cannotHover && hasTouchEvents);
  }

  /**
   * Returns current touch capability status (synchronous)
   */
  public isTouchDevice(): boolean {
    return this.touchDeviceSubject.value;
  }

  /**
   * Returns observable for reactive touch capability detection
   */
  public getTouchDeviceObservable(): Observable<boolean> {
    return this.touchDevice$;
  }
}
