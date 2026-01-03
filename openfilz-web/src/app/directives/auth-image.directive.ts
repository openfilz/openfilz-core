import { Directive, ElementRef, Input, OnChanges, OnDestroy, SimpleChanges, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Subscription } from 'rxjs';

@Directive({
  selector: 'img[authSrc]',
  standalone: true
})
export class AuthImageDirective implements OnChanges, OnDestroy {
  @Input() authSrc: string | undefined | null = '';

  private el = inject(ElementRef);
  private http = inject(HttpClient);
  private subscription?: Subscription;
  private objectUrl?: string;

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['authSrc']) {
      this.loadImage();
    }
  }

  ngOnDestroy(): void {
    this.cleanup();
  }

  private cleanup(): void {
    if (this.subscription) {
      this.subscription.unsubscribe();
      this.subscription = undefined;
    }
    if (this.objectUrl) {
      URL.revokeObjectURL(this.objectUrl);
      this.objectUrl = undefined;
    }
  }

  private loadImage(): void {
    this.cleanup();

    if (!this.authSrc) {
      this.el.nativeElement.src = '';
      return;
    }

    this.subscription = this.http.get(this.authSrc, { responseType: 'blob' })
      .subscribe({
        next: (blob) => {
          this.objectUrl = URL.createObjectURL(blob);
          this.el.nativeElement.src = this.objectUrl;
        },
        error: (err) => {
          console.error('Error loading image', err);
          // Optionally set a fallback image or keep the original broken state
          // this.el.nativeElement.src = 'assets/fallback.png'; 
        }
      });
  }
}
