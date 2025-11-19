import {Component, EventEmitter, Input, Output} from '@angular/core';
import {CommonModule} from '@angular/common';
import {MatButtonModule} from '@angular/material/button';
import {MatIconModule} from '@angular/material/icon';
import {MatMenuModule} from '@angular/material/menu';
import {MatTooltipModule} from '@angular/material/tooltip';
import {AppConfig} from '../../config/app.config';

@Component({
  selector: 'app-toolbar',
  standalone: true,
  templateUrl: './toolbar.component.html',
  styleUrls: ['./toolbar.component.css'],
  imports: [
    CommonModule,
    MatButtonModule,
    MatIconModule,
    MatMenuModule,
    MatTooltipModule
  ],
})
export class ToolbarComponent {
  @Input() viewMode: 'grid' | 'list' = 'grid';
  @Input() hasSelection = false;
  @Input() selectionCount = 0;

  // Page title (optional)
  @Input() pageTitle?: string;
  @Input() pageIcon?: string;

  // Feature toggles
  @Input() showUploadButton = true;
  @Input() showCreateFolderButton = true;
  @Input() showStandardSelectionActions = true; // Show rename, download, move, copy, delete buttons

  // Pagination inputs
  @Input() pageIndex = 0;
  @Input() pageSize = AppConfig.pagination.defaultPageSize;
  @Input() totalItems = 0;
  @Input() isSearchResultsPage = false;

  @Output() createFolder = new EventEmitter<void>();
  @Output() uploadFiles = new EventEmitter<void>();
  @Output() viewModeChange = new EventEmitter<'grid' | 'list'>();
  @Output() renameSelected = new EventEmitter<void>();
  @Output() downloadSelected = new EventEmitter<void>();
  @Output() moveSelected = new EventEmitter<void>();
  @Output() copySelected = new EventEmitter<void>();
  @Output() deleteSelected = new EventEmitter<void>();
  @Output() clearSelection = new EventEmitter<void>();

  // Pagination outputs
  @Output() previousPage = new EventEmitter<void>();
  @Output() nextPage = new EventEmitter<void>();
  @Output() pageSizeChange = new EventEmitter<number>();

  // Page size options from global config
  pageSizeOptions = AppConfig.pagination.pageSizeOptions;

  onCreateFolder() {
    this.createFolder.emit();
  }

  onUploadFiles() {
    this.uploadFiles.emit();
  }

  toggleViewMode() {
    const newMode = this.viewMode === 'grid' ? 'list' : 'grid';
    this.viewModeChange.emit(newMode);
  }

  onRenameSelected() {
    this.renameSelected.emit();
  }

  onDownloadSelected() {
    this.downloadSelected.emit();
  }

  onMoveSelected() {
    this.moveSelected.emit();
  }

  onCopySelected() {
    this.copySelected.emit();
  }

  onDeleteSelected() {
    this.deleteSelected.emit();
  }

  onClearSelection() {
    this.clearSelection.emit();
  }

  // Pagination methods
  onPreviousPage() {
    if (this.hasPreviousPage()) {
      this.previousPage.emit();
    }
  }

  onNextPage() {
    if (this.hasNextPage()) {
      this.nextPage.emit();
    }
  }

  hasPreviousPage(): boolean {
    return this.pageIndex > 0;
  }

  hasNextPage(): boolean {
    const totalPages = Math.ceil(this.totalItems / this.pageSize);
    return this.pageIndex < totalPages - 1;
  }

  getStartIndex(): number {
    return this.pageIndex * this.pageSize + 1;
  }

  getEndIndex(): number {
    return Math.min((this.pageIndex + 1) * this.pageSize, this.totalItems);
  }

  onPageSizeChange(newPageSize: number) {
    this.pageSizeChange.emit(newPageSize);
  }
}