import { Directive, ElementRef, EventEmitter, HostBinding, HostListener, Input, Output, OnDestroy } from '@angular/core';
import { FileItem } from '../models/document.models';
import { DragDropService } from '../services/drag-drop.service';

@Directive({
  selector: '[appFileDraggable]',
  standalone: true
})
export class FileDraggableDirective implements OnDestroy {
  @Input('appFileDraggable') item!: FileItem;
  @Input() allItems: FileItem[] = [];
  @Input() dragHandleSelector: string = '.drag-handle';

  @Output() dragStarted = new EventEmitter<FileItem[]>();
  @Output() dragEnded = new EventEmitter<void>();

  @HostBinding('class.is-dragging') isDragging = false;
  @HostBinding('attr.draggable') get draggable() {
    return this.isDragHandle ? 'true' : 'false';
  }

  private isDragHandle = false;

  constructor(
    private dragDropService: DragDropService,
    private elementRef: ElementRef
  ) {}

  @HostListener('mousedown', ['$event'])
  onMouseDown(event: MouseEvent): void {
    const target = event.target as HTMLElement;
    const hostElement = this.elementRef.nativeElement as HTMLElement;

    // Check if selector matches the host element itself
    if (hostElement.matches(this.dragHandleSelector)) {
      this.isDragHandle = hostElement.contains(target);
    } else {
      // Otherwise look for a nested element matching the selector
      const dragHandle = hostElement.querySelector(this.dragHandleSelector);
      this.isDragHandle = dragHandle?.contains(target) ?? false;
    }
  }

  @HostListener('dragstart', ['$event'])
  onDragStart(event: DragEvent): void {
    if (!this.isDragHandle) {
      event.preventDefault();
      return;
    }

    const itemsToDrag = this.getItemsToDrag();

    this.isDragging = true;
    this.dragDropService.startDrag(itemsToDrag);
    this.dragStarted.emit(itemsToDrag);

    // Set drag data for identification
    if (event.dataTransfer) {
      event.dataTransfer.effectAllowed = 'move';
      event.dataTransfer.setData('text/plain', JSON.stringify(itemsToDrag.map(i => i.id)));

      // Create a simple drag image
      const dragImage = this.createDragImage(itemsToDrag);
      document.body.appendChild(dragImage);
      event.dataTransfer.setDragImage(dragImage, 20, 20);

      // Remove drag image after a short delay
      setTimeout(() => {
        document.body.removeChild(dragImage);
      }, 0);
    }
  }

  @HostListener('dragend', ['$event'])
  onDragEnd(event: DragEvent): void {
    this.isDragging = false;
    this.isDragHandle = false;
    this.dragDropService.endDrag();
    this.dragEnded.emit();
  }

  private getItemsToDrag(): FileItem[] {
    // If current item is selected, drag all selected items
    if (this.item.selected) {
      const selectedItems = this.allItems.filter(i => i.selected);
      return selectedItems.length > 0 ? selectedItems : [this.item];
    }
    // Otherwise, drag only the current item
    return [this.item];
  }

  private createDragImage(items: FileItem[]): HTMLElement {
    const dragImage = document.createElement('div');
    dragImage.className = 'drag-preview';
    dragImage.style.cssText = `
      position: absolute;
      top: -1000px;
      left: -1000px;
      padding: 8px 16px;
      background: var(--mat-sys-surface, #fff);
      border: 2px solid var(--mat-sys-primary, #6366f1);
      border-radius: 8px;
      box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
      display: flex;
      align-items: center;
      gap: 8px;
      font-family: var(--mat-sys-body-medium-font, Roboto, sans-serif);
      font-size: 14px;
      font-weight: 500;
      color: var(--mat-sys-on-surface, #1f2937);
      z-index: 10000;
      pointer-events: none;
    `;

    if (items.length === 1) {
      const icon = items[0].type === 'FOLDER' ? 'folder' : 'description';
      dragImage.innerHTML = `
        <span class="material-icons" style="font-size: 20px;">${icon}</span>
        <span>${this.truncateName(items[0].name, 25)}</span>
      `;
    } else {
      dragImage.innerHTML = `
        <span class="material-icons" style="font-size: 20px;">content_copy</span>
        <span>${items.length} items</span>
      `;
    }

    return dragImage;
  }

  private truncateName(name: string, maxLength: number): string {
    if (name.length <= maxLength) return name;
    return name.substring(0, maxLength - 3) + '...';
  }

  ngOnDestroy(): void {
    if (this.isDragging) {
      this.dragDropService.endDrag();
    }
  }
}
