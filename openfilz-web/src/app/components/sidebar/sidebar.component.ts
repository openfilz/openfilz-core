import { Component, EventEmitter, Output, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, NavigationEnd } from '@angular/router';
import { filter } from 'rxjs/operators';

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
    MatTooltipModule
  ],
})
export class SidebarComponent implements OnInit {
  isCollapsed = false;

  @Output() collapsedChange = new EventEmitter<boolean>();
  @Output() logout = new EventEmitter<void>();

  navigationItems = [
    { id: 'dashboard', label: 'Dashboard', active: true, route: '/dashboard' },
    { id: 'my-folder', label: 'My Folder', active: false, route: '/my-folder' },
    { id: 'recycle-bin', label: 'Recycle Bin', active: false, route: '/recycle-bin' },
    { id: 'favorites', label: 'Favorites', active: false, route: '/favorites' },
    //{ id: 'shared-files', label: 'Shared Files', active: false, route: '/shared-files' },
    { id: 'settings', label: 'Settings', active: false, route: '/settings' },
    { id: 'logout', label: 'Log Out', active: false, route: null }
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
      case 'dashboard': return 'chart-line';
      case 'my-folder': return 'folder';
      case 'recycle-bin': return 'trash';
      case 'favorites': return 'heart';
      //case 'shared-files': return 'share-alt';
      case 'settings': return 'cog';
      case 'logout': return 'sign-out-alt';
      default: return 'question';
    }
  }

  onNavigationClick(itemId: string) {
    if (itemId === 'logout') {
      this.logout.emit();
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
    }
  }
}