import { Component, EventEmitter, Input, Output, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatMenuModule } from '@angular/material/menu';
import { FileItem } from '../../models/document.models';
import { FileIconService } from '../../services/file-icon.service';
import {MatDivider} from "@angular/material/divider";
import {DragDropDirective} from "../../directives/drag-drop.directive";

@Component({
  selector: 'app-file-grid',
  standalone: true,
  templateUrl: './file-grid.component.html',
  styleUrls: ['./file-grid.component.css'],
  imports: [
    CommonModule,
    MatCardModule,
    MatIconModule,
    MatButtonModule,
    MatCheckboxModule,
    MatMenuModule,
    MatDivider
  ],
})
export class FileGridComponent {
  @Input() items: FileItem[] = [];
  @Input() fileOver: boolean = false;

  @Output() itemClick = new EventEmitter<FileItem>();
  @Output() itemDoubleClick = new EventEmitter<FileItem>();
  @Output() selectionChange = new EventEmitter<{ item: FileItem, selected: boolean }>();
  @Output() rename = new EventEmitter<FileItem>();
  @Output() download = new EventEmitter<FileItem>();
  @Output() move = new EventEmitter<FileItem>();
  @Output() copy = new EventEmitter<FileItem>();
  @Output() delete = new EventEmitter<FileItem>();

  constructor(private fileIconService: FileIconService) {}

  onItemClick(item: FileItem) {
    this.itemClick.emit(item);
  }

  onItemDoubleClick(item: FileItem) {
    this.itemDoubleClick.emit(item);
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

  getFileIcon(fileName: string, type: 'FILE' | 'FOLDER'): string {
    return this.fileIconService.getFileIcon(fileName, type);
  }

  formatFileSize(bytes: number): string {
    return this.fileIconService.getFileSize(bytes);
  }
}