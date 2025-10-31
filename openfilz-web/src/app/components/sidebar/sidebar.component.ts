import { Component, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router } from '@angular/router';

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
export class SidebarComponent {
  isCollapsed = false;

  @Output() collapsedChange = new EventEmitter<boolean>();

  navigationItems = [
    { id: 'dashboard', label: 'Dashboard', active: true, route: '/dashboard' },
    { id: 'my-folder', label: 'My Folder', active: false, route: '/my-folder' },
    { id: 'recycle-bin', label: 'Recycle Bin', active: false, route: '/recycle-bin' },
    { id: 'favorites', label: 'Favorites', active: false, route: '/favorites' },
    //{ id: 'shared-files', label: 'Shared Files', active: false, route: '/shared-files' },
    { id: 'settings', label: 'Settings', active: false, route: '/settings' },
    { id: 'logout', label: 'Log Out', active: false, route: '/logout' }
  ];

  constructor(private router: Router) {}

  toggleSidebar() {
    this.isCollapsed = !this.isCollapsed;
    this.collapsedChange.emit(this.isCollapsed);
  }

  getIconClass(id: string): string {
    switch(id) {
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
    // Update active state
    this.navigationItems.forEach(item => item.active = item.id === itemId);
    
    // Navigate to the appropriate route
    const item = this.navigationItems.find(navItem => navItem.id === itemId);
    if (item && item.route) {
      this.router.navigate([item.route]);
    }
  }
}