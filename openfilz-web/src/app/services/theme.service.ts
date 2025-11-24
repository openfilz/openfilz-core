import { Injectable, inject } from '@angular/core';
import { BehaviorSubject } from 'rxjs';
import { UserPreferencesService } from './user-preferences.service';

export interface Theme {
  name: string;
  displayName: string;
  className: string;
}

@Injectable({
  providedIn: 'root'
})
export class ThemeService {

  public readonly availableThemes: Theme[] = [
    { name: 'light', displayName: 'Light (Default)', className: 'theme-light' },
    { name: 'dark', displayName: 'Dark', className: 'theme-dark' },
    { name: 'ocean', displayName: 'Ocean', className: 'theme-ocean' },
    { name: 'forest', displayName: 'Forest', className: 'theme-forest' }
  ];

  private currentThemeSubject = new BehaviorSubject<Theme>(this.availableThemes[0]);
  currentTheme$ = this.currentThemeSubject.asObservable();

  private userPreferencesService = inject(UserPreferencesService);

  constructor() {
    this.loadSavedTheme();
  }

  setTheme(themeName: string): void {
    const theme = this.availableThemes.find(t => t.name === themeName);
    if (theme) {
      this.applyTheme(theme);
      this.userPreferencesService.setTheme(themeName);
    }
  }

  getTheme(): Theme {
    return this.currentThemeSubject.value;
  }

  private loadSavedTheme(): void {
    const savedThemeName = this.userPreferencesService.getPreferences().theme;
    if (savedThemeName) {
      const theme = this.availableThemes.find(t => t.name === savedThemeName);
      if (theme) {
        this.applyTheme(theme);
        return;
      }
    }
    // Default to light theme if no saved theme or invalid saved theme
    this.applyTheme(this.availableThemes[0]);
  }

  private applyTheme(theme: Theme): void {
    // Remove all theme classes
    this.availableThemes.forEach(t => {
      document.body.classList.remove(t.className);
    });

    // Add new theme class
    document.body.classList.add(theme.className);
    this.currentThemeSubject.next(theme);
  }
}
