import { Component, EventEmitter, HostListener, Input, Output, inject } from '@angular/core';

import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatTooltipModule } from '@angular/material/tooltip';
import { FileItem } from '../../models/document.models';
import { FileIconService } from '../../services/file-icon.service';
import { TouchDetectionService } from '../../services/touch-detection.service';
import { TranslatePipe } from '@ngx-translate/core';
import { FileDraggableDirective } from '../../directives/file-draggable.directive';
import { FolderDropZoneDirective } from '../../directives/folder-drop-zone.directive';
import { DropEvent } from '../../services/drag-drop.service';

@Component({
  selector: 'app-file-grid',
  standalone: true,
  templateUrl: './file-grid.component.html',
  styleUrls: ['./file-grid.component.css'],
  imports: [
    MatCardModule,
    MatIconModule,
    MatButtonModule,
    MatCheckboxModule,
    MatTooltipModule,
    TranslatePipe,
    FileDraggableDirective,
    FolderDropZoneDirective
],
})
export class FileGridComponent {
  @Input() items: FileItem[] = [];
  @Input() fileOver: boolean = false;
  @Input() showFavoriteButton: boolean = true; // Control favorite button visibility
  @Input() currentFolderId: string | null = null; // Current folder for drop zone validation

  @Output() itemClick = new EventEmitter<FileItem>();
  @Output() itemDoubleClick = new EventEmitter<FileItem>();
  @Output() selectionChange = new EventEmitter<{ item: FileItem, selected: boolean }>();
  @Output() rename = new EventEmitter<FileItem>();
  @Output() download = new EventEmitter<FileItem>();
  @Output() move = new EventEmitter<FileItem>();
  @Output() copy = new EventEmitter<FileItem>();
  @Output() delete = new EventEmitter<FileItem>();
  @Output() toggleFavorite = new EventEmitter<FileItem>();
  @Output() viewProperties = new EventEmitter<FileItem>();
  @Output() itemsDroppedOnFolder = new EventEmitter<DropEvent>();

  // Keyboard navigation
  focusedIndex = 0;
  private gridColumns = 5; // Approximate columns, will be calculated dynamically


  private fileIconService = inject(FileIconService);
  private touchDetectionService = inject(TouchDetectionService);

  constructor() { }

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

  onContextMenu(event: MouseEvent, item: FileItem) {
    event.preventDefault();
    // Handle context menu
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
    event.stopPropagation(); // Prevent item click
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

  // Keyboard navigation methods
  @HostListener('keydown', ['$event'])
  handleKeyboardEvent(event: KeyboardEvent) {
    if (this.items.length === 0) return;

    const target = event.target as HTMLElement;
    // Only handle if focus is on a file item
    if (!target.classList.contains('file-item') && !target.closest('.file-item')) {
      return;
    }

    switch (event.key) {
      case 'ArrowRight':
        event.preventDefault();
        this.moveFocusRight();
        break;
      case 'ArrowLeft':
        event.preventDefault();
        this.moveFocusLeft();
        break;
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
        this.focusItem(this.focusedIndex);
        break;
      case 'End':
        event.preventDefault();
        this.focusedIndex = this.items.length - 1;
        this.focusItem(this.focusedIndex);
        break;
    }
  }

  private moveFocusRight() {
    if (this.focusedIndex < this.items.length - 1) {
      this.focusedIndex++;
      this.focusItem(this.focusedIndex);
    }
  }

  private moveFocusLeft() {
    if (this.focusedIndex > 0) {
      this.focusedIndex--;
      this.focusItem(this.focusedIndex);
    }
  }

  private moveFocusDown() {
    const newIndex = this.focusedIndex + this.gridColumns;
    if (newIndex < this.items.length) {
      this.focusedIndex = newIndex;
      this.focusItem(this.focusedIndex);
    } else {
      // Move to last item
      this.focusedIndex = this.items.length - 1;
      this.focusItem(this.focusedIndex);
    }
  }

  private moveFocusUp() {
    const newIndex = this.focusedIndex - this.gridColumns;
    if (newIndex >= 0) {
      this.focusedIndex = newIndex;
      this.focusItem(this.focusedIndex);
    } else {
      // Move to first item
      this.focusedIndex = 0;
      this.focusItem(this.focusedIndex);
    }
  }

  private focusItem(index: number) {
    // Use setTimeout to ensure DOM is updated
    setTimeout(() => {
      const gridElement = document.querySelector('.file-grid');
      if (gridElement) {
        const fileItems = gridElement.querySelectorAll('.file-item');
        if (fileItems[index]) {
          (fileItems[index] as HTMLElement).focus();
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