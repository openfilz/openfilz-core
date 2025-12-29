import { Directive, EventEmitter, HostBinding, HostListener, Input, Output, OnInit, OnDestroy } from '@angular/core';
import { Subscription } from 'rxjs';
import { FileItem, DocumentType } from '../models/document.models';
import { DragDropService, DropEvent } from '../services/drag-drop.service';

@Directive({
  selector: '[appFolderDropZone]',
  standalone: true
})
export class FolderDropZoneDirective implements OnInit, OnDestroy {
  // Input can be FileItem (for folder items) or string (for breadcrumb folder IDs) or null (for root)
  @Input() appFolderDropZone: FileItem | string | null = null;
  @Input() currentFolderId: string | null = null;

  @Output() itemsDropped = new EventEmitter<DropEvent>();

  @HostBinding('class.drop-zone-active') isDropZoneActive = false;
  @HostBinding('class.drop-zone-valid') isValidTarget = false;
  @HostBinding('class.drop-zone-hover') isHovered = false;
  @HostBinding('class.drop-zone-invalid') isInvalidTarget = false;

  private subscription?: Subscription;
  private dragEnterCounter = 0;

  constructor(private dragDropService: DragDropService) {}

  ngOnInit(): void {
    this.subscription = this.dragDropService.isDragging$.subscribe(isDragging => {
      this.isDropZoneActive = isDragging && this.isFolder();
      this.updateValidState();
    });
  }

  ngOnDestroy(): void {
    this.subscription?.unsubscribe();
  }

  @HostListener('dragenter', ['$event'])
  onDragEnter(event: DragEvent): void {
    if (!this.isFolder()) return;

    event.preventDefault();
    event.stopPropagation();
    this.dragEnterCounter++;

    if (this.dragEnterCounter === 1) {
      this.isHovered = true;
      this.updateValidState();
    }
  }

  @HostListener('dragover', ['$event'])
  onDragOver(event: DragEvent): void {
    if (!this.isFolder()) return;

    event.preventDefault();
    event.stopPropagation();

    // Set drop effect based on validity
    if (event.dataTransfer) {
      event.dataTransfer.dropEffect = this.isValidTarget ? 'move' : 'none';
    }
  }

  @HostListener('dragleave', ['$event'])
  onDragLeave(event: DragEvent): void {
    if (!this.isFolder()) return;

    event.preventDefault();
    event.stopPropagation();
    this.dragEnterCounter--;

    if (this.dragEnterCounter === 0) {
      this.isHovered = false;
      this.updateValidState();
    }
  }

  @HostListener('drop', ['$event'])
  onDrop(event: DragEvent): void {
    if (!this.isFolder()) return;

    event.preventDefault();
    event.stopPropagation();

    this.dragEnterCounter = 0;
    this.isHovered = false;

    const draggedItems = this.dragDropService.getDraggedItems();
    const targetFolderId = this.getTargetFolderId();

    if (draggedItems.length > 0 && this.isValidTarget) {
      this.itemsDropped.emit({
        items: draggedItems,
        targetFolderId
      });
    }

    this.updateValidState();
  }

  private isFolder(): boolean {
    // null means root folder
    if (this.appFolderDropZone === null) {
      return true;
    }

    // string means folder ID (from breadcrumbs)
    if (typeof this.appFolderDropZone === 'string') {
      return true;
    }

    // FileItem - check if it's a folder
    return this.appFolderDropZone.type === DocumentType.FOLDER;
  }

  private getTargetFolderId(): string | null {
    if (this.appFolderDropZone === null) {
      return null;
    }

    if (typeof this.appFolderDropZone === 'string') {
      return this.appFolderDropZone;
    }

    return this.appFolderDropZone.id;
  }

  private updateValidState(): void {
    if (!this.dragDropService.isDragging()) {
      this.isValidTarget = false;
      this.isInvalidTarget = false;
      return;
    }

    const targetItem = this.getTargetAsFileItem();
    this.isValidTarget = this.dragDropService.isValidDropTarget(targetItem, this.currentFolderId);
    this.isInvalidTarget = this.isDropZoneActive && !this.isValidTarget;
  }

  private getTargetAsFileItem(): FileItem | null {
    if (this.appFolderDropZone === null) {
      return null;
    }

    if (typeof this.appFolderDropZone === 'string') {
      // Create a minimal FileItem for validation
      return {
        id: this.appFolderDropZone,
        name: '',
        type: DocumentType.FOLDER
      };
    }

    return this.appFolderDropZone;
  }
}
