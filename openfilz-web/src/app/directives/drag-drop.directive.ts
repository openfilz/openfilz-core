import { Directive, EventEmitter, HostBinding, HostListener, Output } from '@angular/core';

@Directive({
  selector: '[appDragDrop]',
  standalone: true
})
export class DragDropDirective {
  @Output() filesDropped = new EventEmitter<FileList>();
  @Output() fileOverChange = new EventEmitter<boolean>();
  @HostBinding('class.file-over') fileOver: boolean = false;

  private counter = 0;

  @HostListener('dragenter', ['$event'])
  onDragEnter(evt: DragEvent) {
    evt.preventDefault();
    evt.stopPropagation();
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
    this.fileOver = false;
    this.fileOverChange.emit(false);
    this.counter = 0;
    if (evt.dataTransfer?.files) {
      this.filesDropped.emit(evt.dataTransfer.files);
    }
  }
}
