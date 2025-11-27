import {Component, EventEmitter, Input, Output} from '@angular/core';
import {CommonModule} from '@angular/common';
import {MatButtonModule} from '@angular/material/button';
import {MatIconModule} from '@angular/material/icon';
import {MatMenuModule} from '@angular/material/menu';
import {MatTooltipModule} from '@angular/material/tooltip';
import {MatDividerModule} from '@angular/material/divider';
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
    MatTooltipModule,
    MatDividerModule
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
  @Output() sortChange = new EventEmitter<{ sortBy: string, sortOrder: 'ASC' | 'DESC' }>();

  @Input() sortBy = 'name';
  @Input() sortOrder: 'ASC' | 'DESC' = 'ASC';

  sortOptions = [
    { label: 'Name', value: 'name' },
    { label: 'Date Modified', value: 'updatedAt' },
    { label: 'Size', value: 'size' },
    { label: 'Type', value: 'type' },
    { label: 'Owner', value: 'createdBy' },
    { label: 'Date Created', value: 'createdAt' }
  ];

  // Pagination outputs
  @Output() previousPage = new EventEmitter<void>();
  @Output() nextPage = new EventEmitter<void>();
  @Output() pageSizeChange = new EventEmitter<number>();

  // Page size options from global config
  pageSizeOptions = AppConfig.pagination.pageSizeOptions;

  // Menu state for accessibility
  sortMenuOpen = false;
  pageSizeMenuOpen = false;

  // FAB state for mobile
  fabOpen = false;

  // Bottom sheet state for mobile selection actions
  bottomSheetOpen = false;

  onCreateFolder() {
    this.createFolder.emit();
  }

  onUploadFiles() {
    this.uploadFiles.emit();
  }

  // FAB methods
  toggleFab() {
    this.fabOpen = !this.fabOpen;
  }

  closeFab() {
    this.fabOpen = false;
  }

  onUploadFilesFromFab() {
    this.uploadFiles.emit();
    this.closeFab();
  }

  onCreateFolderFromFab() {
    this.createFolder.emit();
    this.closeFab();
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

  onSortChange(sortBy: string) {
    this.sortBy = sortBy;
    this.emitSortChange();
  }

  toggleSortOrder() {
    this.sortOrder = this.sortOrder === 'ASC' ? 'DESC' : 'ASC';
    this.emitSortChange();
  }

  private emitSortChange() {
    this.sortChange.emit({ sortBy: this.sortBy, sortOrder: this.sortOrder });
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

  getSortLabel(): string {
    const option = this.sortOptions.find(opt => opt.value === this.sortBy);
    return option ? option.label : 'Name';
  }

  // Bottom sheet methods for mobile selection actions
  toggleBottomSheet(): void {
    this.bottomSheetOpen = !this.bottomSheetOpen;

    // Prevent body scroll when sheet is open
    if (this.bottomSheetOpen) {
      document.body.style.overflow = 'hidden';
    } else {
      document.body.style.overflow = '';
    }
  }

  closeBottomSheet(): void {
    if (this.bottomSheetOpen) {
      this.bottomSheetOpen = false;
      document.body.style.overflow = '';
    }
  }

  onActionSelected(action: string): void {
    // Execute action based on type
    switch(action) {
      case 'move':
        this.onMoveSelected();
        break;
      case 'copy':
        this.onCopySelected();
        break;
      case 'rename':
        if (this.selectionCount === 1) {
          this.onRenameSelected();
        }
        break;
      case 'download':
        this.onDownloadSelected();
        break;
      case 'delete':
        this.onDeleteSelected();
        break;
    }

    // Auto-close sheet after action
    this.closeBottomSheet();
  }

  getAvailableActionsCount(): number {
    // Count available actions in bottom sheet
    // Base actions: move, copy, download, delete = 4
    // Rename: only if 1 item selected
    let count = 4;
    if (this.selectionCount === 1) {
      count += 1; // Add rename
    }
    return count;
  }
}