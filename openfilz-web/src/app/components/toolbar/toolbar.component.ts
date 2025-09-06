import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { FormsModule } from '@angular/forms';

@Component({
  selector: 'app-toolbar',
  standalone: true,
  templateUrl: './toolbar.component.html',
  styleUrls: ['./toolbar.component.css'],
  imports: [
    CommonModule,
    MatToolbarModule,
    MatButtonModule,
    MatIconModule,
    MatMenuModule,
    MatFormFieldModule,
    MatInputModule,
    FormsModule
  ],
})
export class ToolbarComponent {
  @Input() viewMode: 'grid' | 'list' = 'grid';
  @Input() hasSelection = false;
  
  @Output() createFolder = new EventEmitter<void>();
  @Output() uploadFiles = new EventEmitter<void>();
  @Output() viewModeChange = new EventEmitter<'grid' | 'list'>();
  @Output() downloadSelected = new EventEmitter<void>();
  @Output() deleteSelected = new EventEmitter<void>();
  @Output() search = new EventEmitter<string>();
  
  searchQuery = '';

  onCreateFolder() {
    this.createFolder.emit();
  }

  onUploadFiles() {
    this.uploadFiles.emit();
  }

  onViewChange(mode: 'grid' | 'list') {
    this.viewMode = mode;
    this.viewModeChange.emit(mode);
  }

  onDownloadSelected() {
    this.downloadSelected.emit();
  }

  onDeleteSelected() {
    this.deleteSelected.emit();
  }

  onSearch() {
    this.search.emit(this.searchQuery);
  }
}