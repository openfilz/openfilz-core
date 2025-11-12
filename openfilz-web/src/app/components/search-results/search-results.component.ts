import {Component, OnInit} from '@angular/core';
import {ActivatedRoute, Router} from '@angular/router';
import {CommonModule} from '@angular/common';
import {MatProgressSpinnerModule} from '@angular/material/progress-spinner';
import {MatDialog, MatDialogModule} from '@angular/material/dialog';
import {MatSnackBar, MatSnackBarModule} from '@angular/material/snack-bar';

import {SearchService} from '../../services/search.service';
import {DocumentApiService} from '../../services/document-api.service';
import {FileIconService} from '../../services/file-icon.service';

import {FileListComponent} from '../file-list/file-list.component';
import {FileGridComponent} from '../file-grid/file-grid.component';
import {ToolbarComponent} from '../toolbar/toolbar.component';
import {RenameDialogComponent, RenameDialogData} from '../../dialogs/rename-dialog/rename-dialog.component';
import {FolderTreeDialogComponent} from '../../dialogs/folder-tree-dialog/folder-tree-dialog.component';

import {
    CopyRequest,
    DocumentSearchInfo,
    DocumentType,
    FileItem,
    MoveRequest,
    RenameRequest
} from '../../models/document.models';
import {Observable} from "rxjs";

@Component({
  selector: 'app-search-results',
  standalone: true,
  imports: [
    CommonModule,
    FileListComponent,
    FileGridComponent,
    ToolbarComponent,
    MatProgressSpinnerModule,
    MatDialogModule,
    MatSnackBarModule
  ],
  templateUrl: './search-results.component.html',
  styleUrls: ['./search-results.component.css']
})
export class SearchResultsComponent implements OnInit {
  loading = false;
  isDownloading = false;
  items: FileItem[] = [];
  viewMode: 'grid' | 'list' = 'grid';
  totalItems = 0;
  searchQuery = '';

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private searchService: SearchService,
    private documentApi: DocumentApiService,
    private fileIconService: FileIconService,
    private dialog: MatDialog,
    private snackBar: MatSnackBar
  ) { }

  ngOnInit(): void {
    this.route.queryParams.subscribe(params => {
      this.searchQuery = params['q'];
      if (this.searchQuery) {
        this.performSearch();
      }
    });
  }

  get hasSelectedItems(): boolean {
    return this.items.some(item => item.selected);
  }

  get selectedItems(): FileItem[] {
    return this.items.filter(item => item.selected);
  }

  performSearch(): void {
    this.loading = true;
    this.searchService.searchDocuments(this.searchQuery).subscribe({
      next: (result) => {
        this.totalItems = result.totalHits;
        this.items = result.documents.map(doc => this.transformToFileItem(doc));
        this.loading = false;
      },
      error: (err) => {
        console.error('Search failed', err);
        this.loading = false;
      }
    });
  }

  private transformToFileItem(doc: DocumentSearchInfo): FileItem {
    const fileType = doc.extension == null ? DocumentType.FOLDER : DocumentType.FILE;
    return {
      id: doc.id,
      name: doc.name,
      type: fileType,
      size: doc.size,
      icon: this.fileIconService.getFileIcon(doc.name, fileType),
      selected: false
    };
  }

  onViewModeChange(mode: 'grid' | 'list'): void {
    this.viewMode = mode;
  }

  onItemClick(item: FileItem): void {
    item.selected = !item.selected;
  }

  onItemDoubleClick(item: FileItem): void {
    if (item.type === 'FOLDER') {
      this.router.navigate(['/my-folder'], { queryParams: { folderId: item.id } });
    } else {
      this.onDownloadItem(item);
    }
  }

  onSelectionChange(event: { item: FileItem, selected: boolean }): void {
    event.item.selected = event.selected;
  }

  onSelectAll(selected: boolean): void {
    this.items.forEach(item => item.selected = selected);
  }

  onDownloadItem(item: FileItem): void {
    this.isDownloading = true;
    item.selected = false;
    this.documentApi.downloadDocument(item.id).subscribe({
      next: (blob) => {
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = item.name + ((item.type === 'FILE' ? '' : '.zip'));
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        window.URL.revokeObjectURL(url);
        this.isDownloading = false;
      },
      error: (error) => {
        this.snackBar.open('Failed to download file', 'Close', { duration: 3000 });
        this.isDownloading = false;
      }
    });
  }

  onRenameItem(item: FileItem): void {
    const dialogRef = this.dialog.open(RenameDialogComponent, {
      width: '400px',
      data: { name: item.name, type: item.type } as RenameDialogData
    });

    dialogRef.afterClosed().subscribe(newName => {
      if (newName) {
        const request: RenameRequest = { newName };
        const renameObservable = item.type === 'FOLDER'
          ? this.documentApi.renameFolder(item.id, request)
          : this.documentApi.renameFile(item.id, request);

        renameObservable.subscribe({
          next: () => {
            this.snackBar.open('Item renamed successfully', 'Close', { duration: 3000 });
            this.performSearch();
          },
          error: (error) => {
            this.snackBar.open('Failed to rename item', 'Close', { duration: 3000 });
          }
        });
      }
    });
  }

  onMoveItem(item: FileItem): void {
    const dialogRef = this.dialog.open(FolderTreeDialogComponent, {
      width: '700px',
      data: {
        title: 'Move item',
        actionType: 'move',
        excludeIds: [item.id]
      }
    });

    dialogRef.afterClosed().subscribe(targetFolderId => {
      if (targetFolderId !== undefined) {
        const request: MoveRequest = { documentIds: [item.id], targetFolderId: targetFolderId || undefined };
        const moveObservable = item.type === 'FOLDER'
          ? this.documentApi.moveFolders(request)
          : this.documentApi.moveFiles(request);

        moveObservable.subscribe({
          next: () => {
            this.snackBar.open('Item moved successfully', 'Close', { duration: 3000 });
            this.performSearch();
          },
          error: () => this.snackBar.open('Failed to move item', 'Close', { duration: 3000 })
        });
      }
    });
  }

  onCopyItem(item: FileItem): void {
    const dialogRef = this.dialog.open(FolderTreeDialogComponent, {
      width: '700px',
      data: {
        title: 'Copy item',
        actionType: 'copy',
        excludeIds: []
      }
    });

    dialogRef.afterClosed().subscribe(targetFolderId => {
      if (targetFolderId !== undefined) {
        const request: CopyRequest = { documentIds: [item.id], targetFolderId: targetFolderId || undefined };
        const copyObservable : Observable<any> = item.type === DocumentType.FOLDER
          ? this.documentApi.copyFolders(request)
          : this.documentApi.copyFiles(request);

        copyObservable.subscribe({
          next: () => {
            this.snackBar.open('Item copied successfully', 'Close', { duration: 3000 });
            this.performSearch();
          },
          error: () => this.snackBar.open('Failed to copy item', 'Close', { duration: 3000 })
        });
      }
    });
  }

  onDeleteItem(item: FileItem): void {
    if (confirm(`Are you sure you want to delete "${item.name}"?`)) {
      const deleteObservable = item.type === 'FOLDER'
        ? this.documentApi.deleteFolders({ documentIds: [item.id] })
        : this.documentApi.deleteFiles({ documentIds: [item.id] });

      deleteObservable.subscribe({
        next: () => {
          this.snackBar.open('Item deleted successfully', 'Close', { duration: 3000 });
          this.performSearch();
        },
        error: () => this.snackBar.open('Failed to delete item', 'Close', { duration: 3000 })
      });
    }
  }
}
