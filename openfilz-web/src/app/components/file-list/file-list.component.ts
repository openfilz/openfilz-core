import { Component, EventEmitter, HostListener, Input, Output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSortModule, Sort } from '@angular/material/sort';
import { FileItem } from '../../models/document.models';
import { FileIconService } from '../../services/file-icon.service';
import { TouchDetectionService } from '../../services/touch-detection.service';
import { TranslatePipe } from '@ngx-translate/core';
import { FileDraggableDirective } from '../../directives/file-draggable.directive';
import { FolderDropZoneDirective } from '../../directives/folder-drop-zone.directive';
import { AuthImageDirective } from '../../directives/auth-image.directive';
import { DropEvent } from '../../services/drag-drop.service';

@Component({
  selector: 'app-file-list',
  standalone: true,
  templateUrl: './file-list.component.html',
  styleUrls: ['./file-list.component.css'],
  imports: [
    CommonModule,
    MatTableModule,
    MatSortModule,
    MatIconModule,
    MatButtonModule,
    MatCheckboxModule,
    MatTooltipModule,
    TranslatePipe,
    FileDraggableDirective,
    FolderDropZoneDirective,
    AuthImageDirective
  ],
})
export class FileListComponent {
  @Input() items: FileItem[] = [];
  @Input() fileOver: boolean = false;
  @Input() showFavoriteButton: boolean = true; // Control favorite button visibility
  @Input() sortBy: string = 'name';
  @Input() sortOrder: 'ASC' | 'DESC' = 'ASC';
  @Input() currentFolderId: string | null = null; // Current folder for drop zone validation

  @Output() itemClick = new EventEmitter<FileItem>();
  @Output() itemDoubleClick = new EventEmitter<FileItem>();
  @Output() selectionChange = new EventEmitter<{ item: FileItem, selected: boolean }>();
  @Output() selectAll = new EventEmitter<boolean>();
  @Output() rename = new EventEmitter<FileItem>();
  @Output() download = new EventEmitter<FileItem>();
  @Output() move = new EventEmitter<FileItem>();
  @Output() copy = new EventEmitter<FileItem>();
  @Output() delete = new EventEmitter<FileItem>();
  @Output() toggleFavorite = new EventEmitter<FileItem>();
  @Output() viewProperties = new EventEmitter<FileItem>();
  @Output() sortChange = new EventEmitter<{ sortBy: string, sortOrder: 'ASC' | 'DESC' }>();
  @Output() itemsDroppedOnFolder = new EventEmitter<DropEvent>();

  // Keyboard navigation
  focusedIndex = 0;

  get displayedColumns(): string[] {
    if (this.showFavoriteButton) {
      return ['drag', 'select', 'favorite', 'name', 'size', 'type', 'actions'];
    }
    return ['drag', 'select', 'name', 'size', 'type', 'actions'];
  }


  private fileIconService = inject(FileIconService);
  private touchDetectionService = inject(TouchDetectionService
  );

  constructor() { }

  get allSelected(): boolean {
    return this.items.length > 0 && this.items.every(item => item.selected);
  }

  get someSelected(): boolean {
    return this.items.some(item => item.selected);
  }

  onItemClick(item: FileItem) {
    this.itemClick.emit(item);
  }

  onItemDoubleClick(item: FileItem) {
    this.itemDoubleClick.emit(item);
  }

  onIconClick(event: Event, item: FileItem) {
    event.stopPropagation(); // Prevent parent click handler

    if (this.touchDetectionService.isTouchDevice()) {
      // On touch devices, single tap on icon = open
      // Add ripple effect
      const iconElement = event.currentTarget as HTMLElement;
      iconElement.classList.add('ripple');

      // Remove ripple class after animation completes
      setTimeout(() => {
        iconElement.classList.remove('ripple');
      }, 600);

      // Navigate immediately (same as double-click)
      this.itemDoubleClick.emit(item);
    }
    // On desktop: do nothing, preserve double-click behavior
  }

  onSelectionChange(item: FileItem, selected: boolean) {
    this.selectionChange.emit({ item, selected });
  }

  onSelectAll(selected: boolean) {
    this.selectAll.emit(selected);
  }

  onRename(item: FileItem) {
    this.rename.emit(item);
  }

  onDownload(item: FileItem) {
    this.download.emit(item);
  }

  onMove(item: FileItem) {
    this.move.emit(item);
  }

  onCopy(item: FileItem) {
    this.copy.emit(item);
  }

  onDelete(item: FileItem) {
    this.delete.emit(item);
  }

  onToggleFavorite(event: Event, item: FileItem) {
    event.stopPropagation();
    this.toggleFavorite.emit(item);
  }

  onViewProperties(item: FileItem) {
    this.viewProperties.emit(item);
  }

  onItemsDropped(event: DropEvent) {
    this.itemsDroppedOnFolder.emit(event);
  }

  getFileIcon(fileName: string, type: 'FILE' | 'FOLDER'): string {
    return this.fileIconService.getFileIcon(fileName, type);
  }

  formatFileSize(bytes: number): string {
    return this.fileIconService.getFileSize(bytes);
  }

  getFileTypeFromName(fileName: string): string {
    const extension = this.fileIconService.getFileExtension(fileName).toUpperCase();
    return extension ? `${extension} File` : 'File';
  }

  getFileExtension(fileName: string): string {
    return this.fileIconService.getFileExtension(fileName);
  }

  /**
   * Handle thumbnail loading error by clearing the URL to show fallback icon
   */
  onThumbnailError(event: Event, item: FileItem) {
    item.thumbnailUrl = undefined;
  }

  onSortChange(sort: Sort) {
    this.sortChange.emit({
      sortBy: sort.active,
      sortOrder: sort.direction.toUpperCase() as 'ASC' | 'DESC'
    });
  }

  // Keyboard navigation methods
  @HostListener('keydown', ['$event'])
  handleKeyboardEvent(event: KeyboardEvent) {
    if (this.items.length === 0) return;

    const target = event.target as HTMLElement;
    // Only handle if focus is on a table row
    if (!target.classList.contains('file-row') && !target.closest('.file-row')) {
      return;
    }

    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        this.moveFocusDown();
        break;
      case 'ArrowUp':
        event.preventDefault();
        this.moveFocusUp();
        break;
      case 'Enter':
        event.preventDefault();
        if (this.items[this.focusedIndex]) {
          this.onItemDoubleClick(this.items[this.focusedIndex]);
        }
        break;
      case ' ':
        event.preventDefault();
        if (this.items[this.focusedIndex]) {
          const item = this.items[this.focusedIndex];
          this.onSelectionChange(item, !item.selected);
        }
        break;
      case 'Home':
        event.preventDefault();
        this.focusedIndex = 0;
        this.focusRow(this.focusedIndex);
        break;
      case 'End':
        event.preventDefault();
        this.focusedIndex = this.items.length - 1;
        this.focusRow(this.focusedIndex);
        break;
    }
  }

  private moveFocusDown() {
    if (this.focusedIndex < this.items.length - 1) {
      this.focusedIndex++;
      this.focusRow(this.focusedIndex);
    }
  }

  private moveFocusUp() {
    if (this.focusedIndex > 0) {
      this.focusedIndex--;
      this.focusRow(this.focusedIndex);
    }
  }

  private focusRow(index: number) {
    // Use setTimeout to ensure DOM is updated
    setTimeout(() => {
      const tableElement = document.querySelector('.file-list-table');
      if (tableElement) {
        const rows = tableElement.querySelectorAll('.file-row');
        if (rows[index]) {
          (rows[index] as HTMLElement).focus();
        }
      }
    });
  }

  getTabIndex(index: number): number {
    return index === this.focusedIndex ? 0 : -1;
  }

  isFocused(index: number): boolean {
    return index === this.focusedIndex;
  }
}