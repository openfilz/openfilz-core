import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Event as RouterEvent, NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';

import { SidebarComponent } from './components/sidebar/sidebar.component';
import { HeaderComponent } from './components/header/header.component';
import { BreadcrumbComponent } from './components/breadcrumb/breadcrumb.component';
import { DownloadProgressComponent } from "./components/download-progress/download-progress.component";
import { ElementInfo } from "./models/document.models";
import { BreadcrumbService } from "./services/breadcrumb.service";
import { ThemeService } from './services/theme.service';

@Component({
  selector: 'app-main',
  standalone: true,
  templateUrl: './main.component.html',
  styleUrls: ['./main.component.css'],
  imports: [
    CommonModule,
    MatSnackBarModule,
    MatProgressBarModule,
    SidebarComponent,
    HeaderComponent,
    BreadcrumbComponent,
    DownloadProgressComponent,
    RouterOutlet
  ],
})
export class MainComponent implements OnInit {
  userData$ = this.oidcSecurityService.userData$;
  isAuthenticated$ = this.oidcSecurityService.isAuthenticated$;
  isDownloading = false;
  breadcrumbs: ElementInfo[] = [];
  currentRoute = '';
  isWipRoute = false;
  isSidebarCollapsed = false;

  // This is needed for the header component
  get hasSelectedItems(): boolean {
    // This will be implemented in child components that actually have selected items
    return false;
  }

  constructor(
    private router: Router,
    private snackBar: MatSnackBar,
    private breadcrumbService: BreadcrumbService,
    private oidcSecurityService: OidcSecurityService,
    private themeService: ThemeService
  ) { }

  ngOnInit() {
    // Initialize the current route based on the URL
    this.updateCurrentRoute();

    // Subscribe to router events to update the route when it changes
    this.router.events.subscribe((event: RouterEvent) => {
      if (event instanceof NavigationEnd) {
        this.updateCurrentRoute();
      }
    });

    // Subscribe to breadcrumb changes from child components
    this.breadcrumbService.currentBreadcrumbs.subscribe(breadcrumbs => {
      this.breadcrumbs = breadcrumbs;
    });
  }

  updateCurrentRoute() {
    const path = this.router.url.split('/')[1]; // Get the first part of the URL after the slash
    this.currentRoute = path || 'dashboard'; // Default to 'dashboard' if path is empty (root route)
    this.isWipRoute = ['recycle-bin', 'settings'].includes(this.currentRoute);
  }

  onNavigate(item: any) {
    // Handle navigation events from breadcrumb
    // Determine the target route based on current route
    let targetRoute = '/my-folder'; // Default

    if (this.currentRoute === 'favorites') {
      targetRoute = '/favorites';
    } else if (this.currentRoute === 'recycle-bin') {
      targetRoute = '/recycle-bin';
    }

    if (item && item.id === '0') { // Root.INSTANCE has id of '0'
      this.router.navigate([targetRoute]).then(() => {
        // Trigger breadcrumb reset in current context
        this.breadcrumbService.navigateTo(null);
      });
    } else if (item && item.id) {
      this.router.navigate([targetRoute]).then(() => {
        // Trigger navigation to specific folder
        this.breadcrumbService.navigateTo(item);
      });
    }
  }


  onSidebarCollapsedChange(collapsed: boolean) {
    this.isSidebarCollapsed = collapsed;
  }

  logout() {
    this.oidcSecurityService.logoff().subscribe((result) => console.log(result));
  }
}