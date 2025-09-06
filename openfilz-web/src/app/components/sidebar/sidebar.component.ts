import { Component, EventEmitter, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';

@Component({
  selector: 'app-sidebar',
  standalone: true,
  templateUrl: './sidebar.component.html',
  styleUrls: ['./sidebar.component.css'],
  imports: [CommonModule, MatButtonModule, MatIconModule, MatListModule],
})
export class SidebarComponent {
  @Output() navigationClick = new EventEmitter<string>();

  navigationItems = [
    { id: 'home', label: 'Home', icon: 'home', active: true },
    { id: 'starred', label: 'Starred', icon: 'star_border', active: false },
    { id: 'recent', label: 'Recent', icon: 'schedule', active: false },
    { id: 'trash', label: 'Trash', icon: 'delete_outline', active: false }
  ];

  onNavigationClick(itemId: string) {
    this.navigationItems.forEach(item => item.active = item.id === itemId);
    this.navigationClick.emit(itemId);
  }
}