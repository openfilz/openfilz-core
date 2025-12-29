import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';
import { FileItem, DocumentType } from '../models/document.models';

export interface DropEvent {
  items: FileItem[];
  targetFolderId: string | null;
}

@Injectable({ providedIn: 'root' })
export class DragDropService {
  private draggedItemsSubject = new BehaviorSubject<FileItem[]>([]);
  private isDraggingSubject = new BehaviorSubject<boolean>(false);

  readonly draggedItems$ = this.draggedItemsSubject.asObservable();
  readonly isDragging$ = this.isDraggingSubject.asObservable();

  startDrag(items: FileItem[]): void {
    this.draggedItemsSubject.next(items);
    this.isDraggingSubject.next(true);
  }

  endDrag(): void {
    this.draggedItemsSubject.next([]);
    this.isDraggingSubject.next(false);
  }

  getDraggedItems(): FileItem[] {
    return this.draggedItemsSubject.getValue();
  }

  isDragging(): boolean {
    return this.isDraggingSubject.getValue();
  }

  isValidDropTarget(
    targetItem: FileItem | null,
    currentFolderId: string | null
  ): boolean {
    const draggedItems = this.getDraggedItems();

    if (draggedItems.length === 0) {
      return false;
    }

    // If target is null, it means root folder - always valid unless we're already at root
    if (targetItem === null) {
      // Cannot drop into root if already at root
      if (currentFolderId === null) {
        return false;
      }
      // Check if any dragged item is being dropped into its current location
      return true;
    }

    // Cannot drop onto a file
    if (targetItem.type !== DocumentType.FOLDER) {
      return false;
    }

    // Cannot drop onto the current folder (same location)
    if (targetItem.id === currentFolderId) {
      return false;
    }

    // Cannot drop a folder onto itself
    const draggedIds = new Set(draggedItems.map(item => item.id));
    if (draggedIds.has(targetItem.id)) {
      return false;
    }

    return true;
  }
}
