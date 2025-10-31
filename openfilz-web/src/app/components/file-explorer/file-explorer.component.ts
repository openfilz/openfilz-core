import { Component, OnInit, ViewChild, ElementRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatIcon } from "@angular/material/icon";

import { FileGridComponent } from '../../components/file-grid/file-grid.component';
import { FileListComponent } from '../../components/file-list/file-list.component';
import { ToolbarComponent } from '../../components/toolbar/toolbar.component';
import { CreateFolderDialogComponent } from '../../dialogs/create-folder-dialog/create-folder-dialog.component';
import { RenameDialogComponent, RenameDialogData } from '../../dialogs/rename-dialog/rename-dialog.component';
import { FolderTreeDialogComponent } from '../../dialogs/folder-tree-dialog/folder-tree-dialog.component';

import { DocumentApiService } from '../../services/document-api.service';
import { FileIconService } from '../../services/file-icon.service';
import { BreadcrumbService } from '../../services/breadcrumb.service';

import {
    CopyRequest,
    CreateFolderRequest,
    DeleteRequest,
    DocumentType,
    ElementInfo,
    FileItem,
    ListFolderAndCountResponse,
    MoveRequest,
    RenameRequest,
    Root
} from '../../models/document.models';

import { DragDropDirective } from "../../directives/drag-drop.directive";
import { DownloadSnackbarComponent } from "../../components/download-snackbar/download-snackbar.component";
import { DownloadProgressComponent } from "../../components/download-progress/download-progress.component";

@Component({
  selector: 'app-file-explorer',
  template: `
    <div class="file-explorer-container">
      <!-- Toolbar with integrated pagination -->
      <app-toolbar
        [viewMode]="viewMode"
        [hasSelection]="hasSelectedItems"
        [selectionCount]="selectedItems.length"
        [pageIndex]="pageIndex"
        [pageSize]="pageSize"
        [totalItems]="totalItems"
        (uploadFiles)="triggerFileInput()"
        (createFolder)="onCreateFolder()"
        (viewModeChange)="onViewModeChange($event)"
        (renameSelected)="onRenameSelected()"
        (downloadSelected)="onDownloadSelected()"
        (moveSelected)="onMoveSelected()"
        (copySelected)="onCopySelected()"
        (deleteSelected)="onDeleteSelected()"
        (clearSelection)="onSelectAll(false)"
        (previousPage)="onPreviousPage()"
        (nextPage)="onNextPage()"
        (pageSizeChange)="onPageSizeChange($event)">
      </app-toolbar>

      <div class="file-explorer-content" appDragDrop
           (filesDropped)="onFilesDropped($event)"
           (fileOverChange)="onFileOverChange($event)"
           [class.file-over]="fileOver">

        <!-- Hidden file input -->
        <input type="file" #fileInput multiple style="display: none;" (change)="onFileSelected($event)">

      @if(!loading) {
          @if(items.length === 0) {
              <div class="empty-state">
                  <div class="empty-content">
                      <mat-icon class="empty-icon">folder_open</mat-icon>
                      <h3>This folder is empty</h3>
                      <p>Drop files here or create a new folder to get started</p>
                  </div>
              </div>
          }
          @if(viewMode === 'grid' && items.length > 0) {
              <app-file-grid
                      [items]="items"
                      [fileOver]="fileOver"
                      (itemClick)="onItemClick($event)"
                      (itemDoubleClick)="onItemDoubleClick($event)"
                      (selectionChange)="onSelectionChange($event)"
                      (rename)="onRenameItem($event)"
                      (download)="onDownloadItem($event)"
                      (move)="onMoveItem($event)"
                      (copy)="onCopyItem($event)"
                      (delete)="onDeleteItem($event)">
              </app-file-grid>
          }
          @if(viewMode === 'list' && items.length > 0) {
              <app-file-list
                      [items]="items"
                      [fileOver]="fileOver"
                      (itemClick)="onItemClick($event)"
                      (itemDoubleClick)="onItemDoubleClick($event)"
                      (selectionChange)="onSelectionChange($event)"
                      (selectAll)="onSelectAll($event)"
                      (rename)="onRenameItem($event)"
                      (download)="onDownloadItem($event)"
                      (move)="onMoveItem($event)"
                      (copy)="onCopyItem($event)"
                      (delete)="onDeleteItem($event)">
              </app-file-list>
          }
      }
      @else {
          <div class="loading-container">
              <mat-spinner></mat-spinner>
              <p>Loading...</p>
          </div>
        }
      </div>

      @if (isDownloading) {
          <app-download-progress></app-download-progress>
      }
    </div>
  `,
  styleUrls: ['./file-explorer.component.css'],
  standalone: true,
  imports: [
    CommonModule,
    MatDialogModule,
    MatSnackBarModule,
    MatProgressSpinnerModule,
    FileGridComponent,
    FileListComponent,
    ToolbarComponent,
    MatIcon,
    DragDropDirective,
    DownloadProgressComponent
  ],
})
export class FileExplorerComponent implements OnInit {
  static itemsPerPage = 'itemsPerPage';
  viewMode: 'grid' | 'list' = 'grid';
  loading = false;
  isDownloading = false;
  showUploadZone = false;
  fileOver: boolean = false;

  items: FileItem[] = [];
  breadcrumbs: ElementInfo[] = [];
  breadcrumbTrail: FileItem[] = []; // Track full path
  currentFolder?: FileItem;

  totalItems = 0;
  pageSize = 70;
  pageIndex = 0;

  @ViewChild('fileInput') fileInput!: ElementRef;

  constructor(
    private documentApi: DocumentApiService,
    private fileIconService: FileIconService,
    private dialog: MatDialog,
    private snackBar: MatSnackBar,
    private breadcrumbService: BreadcrumbService
  ) {}

  ngOnInit() {
    const storedItemsPerPage = localStorage.getItem(FileExplorerComponent.itemsPerPage);
    if (storedItemsPerPage) {
      this.pageSize = parseInt(storedItemsPerPage, 10);
    }
    this.loadFolder();

    // Listen for breadcrumb navigation
    this.breadcrumbService.navigation$.subscribe(folder => {
      if (folder === null) {
        // Navigate to root
        this.breadcrumbTrail = [];
        this.loadFolder(undefined, true);
      } else {
        // Navigate to specific folder in breadcrumb trail
        const index = this.breadcrumbTrail.findIndex(f => f.id === folder.id);
        if (index !== -1) {
          // Remove all folders after this one in the trail
          this.breadcrumbTrail = this.breadcrumbTrail.slice(0, index + 1);
          this.loadFolder(this.breadcrumbTrail[index], true);
        }
      }
    });
  }

  get hasSelectedItems(): boolean {
    return this.items.some(item => item.selected);
  }

  get selectedItems(): FileItem[] {
    return this.items.filter(item => item.selected);
  }

  loadFolder(folder?: FileItem, fromBreadcrumb: boolean = false, appendBreadCrumb: boolean = false) {
    this.loading = true;
    this.currentFolder = folder;

    // Update breadcrumb trail
    if (!fromBreadcrumb) {
      if (folder) {
          if(appendBreadCrumb) {
              // Add folder to breadcrumb trail
              this.breadcrumbTrail.push(folder);
          }
      } else {
        // Reset to root
        this.breadcrumbTrail = [];
      }
    }

    this.documentApi.listFolderAndCount(this.currentFolder?.id, 1, this.pageSize).subscribe({
      next: (listAndCount: ListFolderAndCountResponse) => {
        this.totalItems = listAndCount.count;
        this.pageIndex = 0;
        this.populateFolderContents(listAndCount.listFolder);
      },
      error: (error) => {
        this.snackBar.open('Failed to load folder contents', 'Close', { duration: 3000 });
        this.loading = false;
      }
    });
  }

  loadItems() {
    this.loading = true;
    this.documentApi.listFolder(this.currentFolder?.id, this.pageIndex + 1, this.pageSize).subscribe({
      next: (response: ElementInfo[]) => {
        this.populateFolderContents(response);
      },
      error: (error) => {
        this.snackBar.open('Failed to load folder contents', 'Close', { duration: 3000 });
        this.loading = false;
      }
    });
  }

  private populateFolderContents(response: ElementInfo[]) {
    this.items = response.map(item => ({
      ...item,
      selected: false,
      icon: this.fileIconService.getFileIcon(item.name, item.type)
    }));
    this.showUploadZone = this.items.length === 0;
    this.loading = false;
    this.updateBreadcrumbs();
  }

  private updateBreadcrumbs() {
    // Update breadcrumbs with the full path
    this.breadcrumbService.updateBreadcrumbs(this.breadcrumbTrail);
  }

  onPreviousPage() {
    if (this.pageIndex > 0) {
      this.pageIndex--;
      this.loadItems();
    }
  }

  onNextPage() {
    const totalPages = Math.ceil(this.totalItems / this.pageSize);
    if (this.pageIndex < totalPages - 1) {
      this.pageIndex++;
      this.loadItems();
    }
  }

  onPageSizeChange(newPageSize: number) {
    this.pageSize = newPageSize;
    localStorage.setItem(FileExplorerComponent.itemsPerPage, newPageSize.toString());
    this.pageIndex = 0; // Reset to first page when page size changes
    this.loadItems();
  }

  onFileOverChange(isOver: boolean) {
    this.fileOver = isOver;
  }

  onItemClick(item: FileItem) {
    item.selected = !item.selected;
  }

  onItemDoubleClick(item: FileItem) {
    if (item.type === 'FOLDER') {
      this.loadFolder(item, false, true);
    } else {
      this.onDownloadItem(item);
    }
  }

  onSelectionChange(event: { item: FileItem, selected: boolean }) {
    event.item.selected = event.selected;
  }

  onSelectAll(selected: boolean) {
    this.items.forEach(item => item.selected = selected);
  }

  onViewModeChange(mode: 'grid' | 'list') {
    this.viewMode = mode;
  }

  onCreateFolder() {
    const dialogRef = this.dialog.open(CreateFolderDialogComponent, {
      maxWidth: '500px',
      width: '90vw',
      autoFocus: true
    });

    dialogRef.afterClosed().subscribe(folderName => {
      if (folderName) {
        const request: CreateFolderRequest = {
          name: folderName,
          parentId: this.currentFolder?.id
        };

        this.documentApi.createFolder(request).subscribe({
          next: () => {
            this.snackBar.open('Folder created successfully', 'Close', { duration: 3000 });
            this.loadFolder(this.currentFolder);
          },
          error: (error) => {
            this.snackBar.open('Failed to create folder', 'Close', { duration: 3000 });
          }
        });
      }
    });
  }

  onRenameItem(item: FileItem) {
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
            this.loadFolder(this.currentFolder);
          },
          error: (error) => {
            this.snackBar.open('Failed to rename item', 'Close', { duration: 3000 });
          }
        });
      }
    });
  }

  onDownloadItem(item: FileItem) {
    this.isDownloading = true;
    item.selected = false;
    this.documentApi.downloadDocument(item.id).subscribe({
      next: (blob) => {
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = item.name + ((item.type == 'FILE' ? '' : '.zip'));
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

  onMoveItem(item: FileItem) {
    const dialogRef = this.dialog.open(FolderTreeDialogComponent, {
      width: '700px',
      data: {
        title: 'Move item',
        actionType: 'move',
        currentFolderId: this.currentFolder?.id,
        excludeIds: [item.id]
      }
    });

    dialogRef.afterClosed().subscribe(targetFolderId => {
      if (targetFolderId !== undefined) {
        this.performMoveWithRetry(item, targetFolderId);
      }
    });
  }

  onCopyItem(item: FileItem) {
    const dialogRef = this.dialog.open(FolderTreeDialogComponent, {
      width: '700px',
      data: {
        title: 'Copy item',
        actionType: 'copy',
        currentFolderId: this.currentFolder?.id,
        excludeIds: []
      }
    });

    dialogRef.afterClosed().subscribe(targetFolderId => {
      if (targetFolderId !== undefined) {
        this.performCopyWithRetry(item, targetFolderId);
      }
    });
  }

  onDeleteItem(item: FileItem) {
    if (confirm(`Are you sure you want to delete "${item.name}"?`)) {
      this.deleteItems([item]);
    }
  }

  onDownloadSelected() {
    const selectedItems = this.selectedItems;
    if (selectedItems.length === 1 && selectedItems[0].type === 'FILE') {
      this.onDownloadItem(selectedItems[0]);
    } else if (selectedItems.length > 1) {
      this.isDownloading = true;
      const documentIds = selectedItems.map(item => item.id);
      this.selectedItems.forEach(item => item.selected = false);
      this.documentApi.downloadMultipleDocuments(documentIds).subscribe({
        next: (blob) => {
          const url = window.URL.createObjectURL(blob);
          const a = document.createElement('a');
          a.href = url;
          a.download = 'documents.zip';
          document.body.appendChild(a);
          a.click();
          document.body.removeChild(a);
          window.URL.revokeObjectURL(url);
          this.isDownloading = false;
        },
        error: (error) => {
          this.snackBar.open('Failed to download files', 'Close', { duration: 3000 });
          this.isDownloading = false;
        }
      });
    }
  }

  onMoveSelected() {
    const selectedItems = this.selectedItems;
    if (selectedItems.length > 0) {
      const dialogRef = this.dialog.open(FolderTreeDialogComponent, {
        width: '700px',
        data: {
          title: `Move ${selectedItems.length} item${selectedItems.length > 1 ? 's' : ''}`,
          actionType: 'move',
          currentFolderId: this.currentFolder?.id,
          excludeIds: selectedItems.map(item => item.id)
        }
      });

      dialogRef.afterClosed().subscribe(targetFolderId => {
        if (targetFolderId !== undefined) {
          const folders = selectedItems.filter(item => item.type === 'FOLDER');
          const files = selectedItems.filter(item => item.type === 'FILE');

          let totalOperations = 0;
          if (folders.length > 0) totalOperations++;
          if (files.length > 0) totalOperations++;

          let completed = 0;
          const handleCompletion = () => {
            completed++;
            if (completed === totalOperations) {
              this.snackBar.open('Items moved successfully', 'Close', { duration: 3000 });
              this.loadFolder(this.currentFolder);
            }
          };

          if (folders.length > 0) {
            this.performBulkMove(folders, targetFolderId, handleCompletion, 'folders');
          }

          if (files.length > 0) {
            this.performBulkMove(files, targetFolderId, handleCompletion, 'files');
          }
        }
      });
    }
  }

  onCopySelected() {
    const selectedItems = this.selectedItems;
    if (selectedItems.length > 0) {
      const dialogRef = this.dialog.open(FolderTreeDialogComponent, {
        width: '700px',
        data: {
          title: `Copy ${selectedItems.length} item${selectedItems.length > 1 ? 's' : ''}`,
          actionType: 'copy',
          currentFolderId: this.currentFolder?.id,
          excludeIds: []
        }
      });

      dialogRef.afterClosed().subscribe(targetFolderId => {
        if (targetFolderId !== undefined) {
          const folders = selectedItems.filter(item => item.type === 'FOLDER');
          const files = selectedItems.filter(item => item.type === 'FILE');

          let totalOperations = 0;
          if (folders.length > 0) totalOperations++;
          if (files.length > 0) totalOperations++;

          let completed = 0;
          const handleCompletion = () => {
            completed++;
            if (completed === totalOperations) {
              this.snackBar.open('Items copied successfully', 'Close', { duration: 3000 });
              this.loadFolder(this.currentFolder);
            }
          };

          if (folders.length > 0) {
            this.performBulkCopy(folders, targetFolderId, handleCompletion, 'folders');
          }

          if (files.length > 0) {
            this.performBulkCopy(files, targetFolderId, handleCompletion, 'files');
          }
        }
      });
    }
  }

  onRenameSelected() {
    const selectedItems = this.selectedItems;
    if (selectedItems.length === 1) {
      this.onRenameItem(selectedItems[0]);
    }
  }

  onDeleteSelected() {
    const selectedItems = this.selectedItems;
    if (selectedItems.length > 0) {
      const itemNames = selectedItems.map(item => item.name).join(', ');
      if (confirm(`Are you sure you want to delete: ${itemNames}?`)) {
        this.deleteItems(selectedItems);
      }
    }
  }

  private deleteItems(items: FileItem[]) {
    const request: DeleteRequest = {
      documentIds: items.map(item => item.id)
    };

    const folders = items.filter(item => item.type === 'FOLDER');
    const files = items.filter(item => item.type === 'FILE');

    const deleteObservables = [];

    if (folders.length > 0) {
      deleteObservables.push(this.documentApi.deleteFolders({ documentIds: folders.map(f => f.id) }));
    }

    if (files.length > 0) {
      deleteObservables.push(this.documentApi.deleteFiles({ documentIds: files.map(f => f.id) }));
    }

    deleteObservables.forEach(observable => {
      observable.subscribe({
        next: () => {
          this.snackBar.open('Items deleted successfully', 'Close', { duration: 3000 });
          this.loadFolder(this.currentFolder);
        },
        error: (error) => {
          this.snackBar.open('Failed to delete items', 'Close', { duration: 3000 });
        }
      });
    });
  }

  private performMoveWithRetry(item: FileItem, targetFolderId: string | null, attempt: number = 1, maxAttempts: number = 5) {
    const request: MoveRequest = {
      documentIds: [item.id],
      targetFolderId: targetFolderId || undefined,
      allowDuplicateFileNames: attempt > 1
    };

    const moveObservable = item.type === 'FOLDER'
      ? this.documentApi.moveFolders(request)
      : this.documentApi.moveFiles(request);

    moveObservable.subscribe({
      next: () => {
        this.snackBar.open('Item moved successfully', 'Close', { duration: 3000 });
        this.loadFolder(this.currentFolder);
      },
      error: (error: any) => {
        if (error.status === 409 && attempt < maxAttempts) {
          if (attempt === 1) {
            this.performMoveWithRetry(item, targetFolderId, attempt + 1, maxAttempts);
          } else {
            this.snackBar.open(`Name conflict detected. Maximum retry attempts (${maxAttempts}) reached.`, 'Close', { duration: 5000 });
          }
        } else {
          this.snackBar.open('Failed to move item', 'Close', { duration: 3000 });
        }
      }
    });
  }

  private performCopyWithRetry(item: FileItem, targetFolderId: string | null, attempt: number = 1, maxAttempts: number = 5) {
    const request: CopyRequest = {
      documentIds: [item.id],
      targetFolderId: targetFolderId || undefined,
      allowDuplicateFileNames: attempt > 1
    };

    if (item.type === 'FOLDER') {
      this.documentApi.copyFolders(request).subscribe({
        next: () => {
          this.snackBar.open('Item copied successfully', 'Close', { duration: 3000 });
          this.loadFolder(this.currentFolder);
        },
        error: (error: any) => {
          if (error.status === 409 && attempt < maxAttempts) {
            if (attempt === 1) {
              this.performCopyWithRetry(item, targetFolderId, attempt + 1, maxAttempts);
            } else {
              this.snackBar.open(`Name conflict detected. Maximum retry attempts (${maxAttempts}) reached.`, 'Close', { duration: 5000 });
            }
          } else {
            this.snackBar.open('Failed to copy item', 'Close', { duration: 3000 });
          }
        }
      });
    } else {
      this.documentApi.copyFiles(request).subscribe({
        next: () => {
          this.snackBar.open('Item copied successfully', 'Close', { duration: 3000 });
          this.loadFolder(this.currentFolder);
        },
        error: (error: any) => {
          if (error.status === 409 && attempt < maxAttempts) {
            if (attempt === 1) {
              this.performCopyWithRetry(item, targetFolderId, attempt + 1, maxAttempts);
            } else {
              this.snackBar.open(`Name conflict detected. Maximum retry attempts (${maxAttempts}) reached.`, 'Close', { duration: 5000 });
            }
          } else {
            this.snackBar.open('Failed to copy item', 'Close', { duration: 3000 });
          }
        }
      });
    }
  }

  private performBulkMove(items: FileItem[], targetFolderId: string | null, onComplete: () => void, type: 'files' | 'folders', attempt: number = 1, maxAttempts: number = 5) {
    const request: MoveRequest = {
      documentIds: items.map(f => f.id),
      targetFolderId: targetFolderId || undefined,
      allowDuplicateFileNames: attempt > 1
    };

    const moveObservable = type === 'folders'
      ? this.documentApi.moveFolders(request)
      : this.documentApi.moveFiles(request);

    moveObservable.subscribe({
      next: () => onComplete(),
      error: (error: any) => {
        if (error.status === 409 && attempt < maxAttempts) {
          if (attempt === 1) {
            this.performBulkMove(items, targetFolderId, onComplete, type, attempt + 1, maxAttempts);
          } else {
            this.snackBar.open(`Name conflict detected. Maximum retry attempts (${maxAttempts}) reached.`, 'Close', { duration: 5000 });
          }
        } else {
          this.snackBar.open('Failed to move items', 'Close', { duration: 3000 });
        }
      }
    });
  }

  private performBulkCopy(items: FileItem[], targetFolderId: string | null, onComplete: () => void, type: 'files' | 'folders', attempt: number = 1, maxAttempts: number = 5) {
    const request: CopyRequest = {
      documentIds: items.map(f => f.id),
      targetFolderId: targetFolderId || undefined,
      allowDuplicateFileNames: attempt > 1
    };

    if (type === 'folders') {
      this.documentApi.copyFolders(request).subscribe({
        next: () => onComplete(),
        error: (error: any) => {
          if (error.status === 409 && attempt < maxAttempts) {
            if (attempt === 1) {
              this.performBulkCopy(items, targetFolderId, onComplete, type, attempt + 1, maxAttempts);
            } else {
              this.snackBar.open(`Name conflict detected. Maximum retry attempts (${maxAttempts}) reached.`, 'Close', { duration: 5000 });
            }
          } else {
            this.snackBar.open('Failed to copy items', 'Close', { duration: 3000 });
          }
        }
      });
    } else {
      this.documentApi.copyFiles(request).subscribe({
        next: () => onComplete(),
        error: (error: any) => {
          if (error.status === 409 && attempt < maxAttempts) {
            if (attempt === 1) {
              this.performBulkCopy(items, targetFolderId, onComplete, type, attempt + 1, maxAttempts);
            } else {
              this.snackBar.open(`Name conflict detected. Maximum retry attempts (${maxAttempts}) reached.`, 'Close', { duration: 5000 });
            }
          } else {
            this.snackBar.open('Failed to copy items', 'Close', { duration: 3000 });
          }
        }
      });
    }
  }

  triggerFileInput() {
    const input = document.createElement('input');
    input.type = 'file';
    input.multiple = true;
    input.onchange = (event) => {
      const files = (event.target as HTMLInputElement).files;
      if (files) {
        this.handleFileUpload(files);
      }
    };
    input.click();
  }

  onFileSelected(event: any) {
    const files: FileList = event.target.files;
    this.handleFileUpload(files);
  }

  onFilesDropped(files: FileList) {
    this.handleFileUpload(files);
  }

  private handleFileUpload(files: FileList) {
    this.documentApi.uploadMultipleDocuments(Array.from(files), this.currentFolder?.id).subscribe({
      next: (item) => {
      },
      error: (error) => {
        this.snackBar.open(`Failed to upload files`, 'Close', { duration: 3000 });
      },
      complete: () => {
        this.snackBar.open(`Files uploaded successfully`, 'Close', { duration: 3000 });
        this.loadFolder(this.currentFolder);
      }
    });
  }
}