import { CommonModule } from '@angular/common';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { FormsModule } from '@angular/forms';
import { ElementInfo } from '../../models/document.models';
import {Component, EventEmitter, Input, Output} from "@angular/core";

@Component({
  selector: 'app-header',
  standalone: true,
  templateUrl: './header.component.html',
  styleUrls: ['./header.component.css'],
  imports: [
    CommonModule,
    MatToolbarModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatMenuModule,
    FormsModule
  ],
})
export class HeaderComponent {
  @Input() breadcrumbs: ElementInfo[] = [];
  @Input() hasSelection = false;
  @Input() selectedCount = 0;
  @Output() uploadFiles = new EventEmitter<void>();
  @Output() createFolder = new EventEmitter<void>();
  @Output() search = new EventEmitter<string>();
  @Output() viewModeChange = new EventEmitter<'grid' | 'list'>();
  @Output() downloadSelected = new EventEmitter<void>();
  @Output() deleteSelected = new EventEmitter<void>();
  @Output() breadcrumbClick = new EventEmitter<ElementInfo>();

  searchQuery = '';

  onUploadFiles() {
    this.uploadFiles.emit();
  }

  onCreateFolder() {
    this.createFolder.emit();
  }

  onSearch() {
    this.search.emit(this.searchQuery);
  }

  onViewModeChange(mode: 'grid' | 'list') {
    this.viewModeChange.emit(mode);
  }

  onDownloadSelected() {
    this.downloadSelected.emit();
  }

  onDeleteSelected() {
    this.deleteSelected.emit();
  }

  onBreadcrumbClick(item: ElementInfo) {
    this.breadcrumbClick.emit(item);
  }
}