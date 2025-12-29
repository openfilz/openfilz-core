import {Directive, EventEmitter, HostBinding, HostListener, Output} from '@angular/core';

@Directive({
  selector: '[appDragDrop]',
  standalone: true
})
export class DragDropDirective {
  @Output() filesDropped = new EventEmitter<FileList>();
  @Output() fileOverChange = new EventEmitter<boolean>();
  @HostBinding('class.file-over') fileOver: boolean = false;

  private counter = 0;

  /**
   * Check if the drag event is from an external file (OS file system)
   * vs an internal drag operation (moving files within the app)
   */
  private isExternalFileDrag(evt: DragEvent): boolean {
    if (!evt.dataTransfer?.types) {
      return false;
    }
    // External file drags have 'Files' type
    // Internal drags have 'text/plain' (set by FileDraggableDirective)
    const types = Array.from(evt.dataTransfer.types);
    return types.includes('Files') && !types.includes('text/plain');
  }

  @HostListener('dragenter', ['$event'])
  onDragEnter(evt: DragEvent) {
    evt.preventDefault();
    evt.stopPropagation();

    // Only show upload overlay for external file drags
    if (!this.isExternalFileDrag(evt)) {
      return;
    }

    this.counter++;
    if (!this.fileOver) {
      this.fileOver = true;
      this.fileOverChange.emit(true);
    }
  }

  @HostListener('dragover', ['$event'])
  onDragOver(evt: DragEvent) {
    evt.preventDefault();
    evt.stopPropagation();
  }

  @HostListener('dragleave', ['$event'])
  onDragLeave(evt: DragEvent) {
    evt.preventDefault();
    evt.stopPropagation();

    // Only handle external file drags
    if (!this.isExternalFileDrag(evt)) {
      return;
    }

    this.counter--;
    if (this.counter === 0) {
      this.fileOver = false;
      this.fileOverChange.emit(false);
    }
  }

  @HostListener('drop', ['$event'])
  onDrop(evt: DragEvent) {
    evt.preventDefault();
    evt.stopPropagation();

    // Only handle external file drops
    if (!this.isExternalFileDrag(evt)) {
      return;
    }

    this.fileOver = false;
    this.fileOverChange.emit(false);
    this.counter = 0;
    if (evt.dataTransfer?.files && evt.dataTransfer.files.length > 0) {
      this.filesDropped.emit(evt.dataTransfer.files);
    }
  }
}
