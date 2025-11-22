import {Component, EventEmitter, Input, Output} from '@angular/core';
import {CommonModule} from '@angular/common';
import {MatTableModule} from '@angular/material/table';
import {MatIconModule} from '@angular/material/icon';
import {MatButtonModule} from '@angular/material/button';
import {MatCheckboxModule} from '@angular/material/checkbox';
import {MatTooltipModule} from '@angular/material/tooltip';
import {MatSortModule, Sort} from '@angular/material/sort';
import {FileItem} from '../../models/document.models';
import {FileIconService} from '../../services/file-icon.service';

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
    MatTooltipModule
  ],
})
export class FileListComponent {
  @Input() items: FileItem[] = [];
  @Input() fileOver: boolean = false;
  @Input() showFavoriteButton: boolean = true; // Control favorite button visibility
  @Input() sortBy: string = 'name';
  @Input() sortOrder: 'ASC' | 'DESC' = 'ASC';
  
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

  get displayedColumns(): string[] {
    if (this.showFavoriteButton) {
      return ['select', 'favorite', 'name', 'size', 'type', 'actions'];
    }
    return ['select', 'name', 'size', 'type', 'actions'];
  }

  constructor(private fileIconService: FileIconService) {}

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

  getFileIcon(fileName: string, type: 'FILE' | 'FOLDER'): string {
    return this.fileIconService.getFileIcon(fileName, type);
  }

  formatFileSize(bytes: number): string {
    return this.fileIconService.getFileSize(bytes);
  }

  getFileTypeFromName(fileName: string): string {
    const extension = fileName.split('.').pop()?.toUpperCase();
    return extension ? `${extension} File` : 'File';
  }

  onSortChange(sort: Sort) {
    this.sortChange.emit({
      sortBy: sort.active,
      sortOrder: sort.direction.toUpperCase() as 'ASC' | 'DESC'
    });
  }
}