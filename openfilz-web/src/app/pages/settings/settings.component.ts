import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { ThemeService, Theme } from '../../services/theme.service';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';

@Component({
  selector: 'app-settings',
  standalone: true,
  imports: [CommonModule, MatIconModule, TranslatePipe],
  templateUrl: './settings.component.html',
  styleUrls: ['./settings.component.css']
})
export class SettingsComponent implements OnInit {
  currentTheme: Theme | undefined;
  availableThemes: Theme[] = [];
  firstName: string = '';

  private themeService = inject(ThemeService);
  private oidcSecurityService = inject(OidcSecurityService);
  private translate = inject(TranslateService);

  constructor() { }

  ngOnInit(): void {
    this.availableThemes = this.themeService.availableThemes;
    this.themeService.currentTheme$.subscribe(theme => {
      this.currentTheme = theme;
    });

    this.oidcSecurityService.userData$.subscribe((result: any) => {
      const userData = result.userData || result; // Handle both wrapper and direct object
      if (userData) {
        this.firstName = userData.given_name || userData.name || this.translate.instant('common.user');
      } else {
        this.firstName = this.translate.instant('common.user');
      }
    });
  }

  onThemeChange(themeName: string) {
    this.themeService.setTheme(themeName);
  }
}
