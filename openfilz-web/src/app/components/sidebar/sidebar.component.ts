import { Component, EventEmitter, Output, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, NavigationEnd } from '@angular/router';
import { filter } from 'rxjs/operators';
import { TranslatePipe } from '@ngx-translate/core';

@Component({
  selector: 'app-sidebar',
  standalone: true,
  templateUrl: './sidebar.component.html',
  styleUrls: ['./sidebar.component.css'],
  imports: [
    CommonModule,
    MatButtonModule,
    MatIconModule,
    MatListModule,
    MatTooltipModule,
    TranslatePipe
  ],
})
export class SidebarComponent implements OnInit {
  isCollapsed = false;

  @Output() collapsedChange = new EventEmitter<boolean>();
  @Output() logout = new EventEmitter<void>();
  @Output() mobileMenuClose = new EventEmitter<void>();

  navigationItems = [
    { id: 'dashboard', labelKey: 'sidebar.dashboard', active: true, route: '/dashboard' },
    { id: 'my-folder', labelKey: 'sidebar.myFolder', active: false, route: '/my-folder' },
    { id: 'recycle-bin', labelKey: 'sidebar.recycleBin', active: false, route: '/recycle-bin' },
    { id: 'favorites', labelKey: 'sidebar.favorites', active: false, route: '/favorites' },
    //{ id: 'shared-files', labelKey: 'sidebar.sharedFiles', active: false, route: '/shared-files' },
    { id: 'settings', labelKey: 'sidebar.settings', active: false, route: '/settings' },
    { id: 'logout', labelKey: 'sidebar.logout', active: false, route: null }
  ];

  private router = inject(Router);

  constructor() { }

  ngOnInit() {
    // Update active state based on current route
    this.updateActiveState(this.router.url);

    // Listen to route changes
    this.router.events.pipe(
      filter(event => event instanceof NavigationEnd)
    ).subscribe((event: any) => {
      this.updateActiveState(event.urlAfterRedirects);
    });
  }

  private updateActiveState(url: string) {
    // Extract the base route from the URL (remove query params)
    const baseRoute = url.split('?')[0];

    // Update active state for all items
    this.navigationItems.forEach(item => {
      item.active = item.route === baseRoute;
    });
  }

  toggleSidebar() {
    this.isCollapsed = !this.isCollapsed;
    this.collapsedChange.emit(this.isCollapsed);
  }

  getIconClass(id: string): string {
    switch (id) {
      case 'dashboard': return 'dashboard';
      case 'my-folder': return 'folder';
      case 'recycle-bin': return 'delete';
      case 'favorites': return 'favorite';
      //case 'shared-files': return 'share';
      case 'settings': return 'settings';
      case 'logout': return 'logout';
      default: return 'help_outline';
    }
  }

  onNavigationClick(itemId: string) {
    if (itemId === 'logout') {
      this.logout.emit();
      this.mobileMenuClose.emit();
      return;
    }

    // Update active state
    this.navigationItems.forEach(item => item.active = item.id === itemId);

    // Navigate to the appropriate route
    const item = this.navigationItems.find(navItem => navItem.id === itemId);
    if (item && item.route) {
      // --- START: Added logic to force reload ---
      if (this.router.url === item.route) {
        // If we are already on the same route, force a reload
        this.router.navigateByUrl('/', { skipLocationChange: true }).then(() => {
          this.router.navigate([item.route]);
        });
      } else {
        // Otherwise, navigate normally
        this.router.navigate([item.route]);
      }
      // Close mobile menu after navigation
      this.mobileMenuClose.emit();
    }
  }
}