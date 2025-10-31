import {Component, OnInit} from '@angular/core';
import {CommonModule} from '@angular/common';
import {MatSnackBar, MatSnackBarModule} from '@angular/material/snack-bar';
import {Event as RouterEvent, NavigationEnd, Router, RouterOutlet} from '@angular/router';

import {SidebarComponent} from './components/sidebar/sidebar.component';
import {HeaderComponent} from './components/header/header.component';
import {BreadcrumbComponent} from './components/breadcrumb/breadcrumb.component';
import {DownloadProgressComponent} from "./components/download-progress/download-progress.component";
import {ElementInfo} from "./models/document.models";
import {BreadcrumbService} from "./services/breadcrumb.service";

@Component({
  selector: 'app-main',
  standalone: true,
  templateUrl: './main.component.html',
  styleUrls: ['./main.component.css'],
  imports: [
    CommonModule,
    MatSnackBarModule,
    SidebarComponent,
    HeaderComponent,
    BreadcrumbComponent,
    DownloadProgressComponent,
    RouterOutlet
  ],
})
export class MainComponent implements OnInit {
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
    private breadcrumbService: BreadcrumbService
  ) {}

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
    this.isWipRoute = ['recycle-bin', 'favorites', 'settings'].includes(this.currentRoute);
  }

  onNavigate(item: any) {
    // Handle navigation events from breadcrumb
    if (item && item.id === '0') { // Root.INSTANCE has id of '0'
      this.router.navigate(['/my-folder']).then(() => {
        // Trigger breadcrumb reset in file explorer
        this.breadcrumbService.navigateTo(null);
      });
    } else if(item && item.id) {
      this.router.navigate(['/my-folder']).then(() => {
        // Trigger navigation to specific folder
        this.breadcrumbService.navigateTo(item);
      });
    }
  }

  onSearch(query: string) {
    if (query.trim()) {
      // TODO: Implement search functionality
      this.snackBar.open('Search functionality coming soon', 'Close', { duration: 3000 });
    }
  }

  onSidebarCollapsedChange(collapsed: boolean) {
    this.isSidebarCollapsed = collapsed;
  }
}