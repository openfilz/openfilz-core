import { Directive, OnInit, inject } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { CopyRequest, DocumentType, FileItem, MoveRequest, RenameRequest } from '../../models/document.models';
import { DocumentApiService } from '../../services/document-api.service';
import { RenameDialogComponent, RenameDialogData } from '../../dialogs/rename-dialog/rename-dialog.component';
import { FolderTreeDialogComponent } from '../../dialogs/folder-tree-dialog/folder-tree-dialog.component';
import { ConfirmDialogComponent, ConfirmDialogData } from '../../dialogs/confirm-dialog/confirm-dialog.component';
import { Observable } from "rxjs";
import { AppConfig } from '../../config/app.config';
import { Router } from "@angular/router";
import { UserPreferencesService } from '../../services/user-preferences.service';

@Directive()
export abstract class FileOperationsComponent implements OnInit {
  viewMode: 'grid' | 'list' = 'grid';
  loading = false;
  isDownloading = false;
  items: FileItem[] = [];
  totalItems = 0;
  pageSize = AppConfig.pagination.defaultPageSize;
  pageIndex = 0;
  sortBy: string = 'name';
  sortOrder: 'ASC' | 'DESC' = 'ASC';

  protected router = inject(Router);
  protected documentApi = inject(DocumentApiService);
  protected dialog = inject(MatDialog);
  protected snackBar = inject(MatSnackBar);
  protected userPreferencesService = inject(UserPreferencesService);

  constructor() {
    const prefs = this.userPreferencesService.getPreferences();
    this.pageSize = prefs.pageSize;
    this.sortBy = prefs.sortBy;
    this.sortOrder = prefs.sortOrder;
  }

  abstract reloadData(): void;

  ngOnInit(): void {
    // Subscribe to preferences changes to keep UI in sync
    this.userPreferencesService.preferences$.subscribe(prefs => {
      let needsReload = false;
      if (this.pageSize !== prefs.pageSize) {
        this.pageSize = prefs.pageSize;
        this.pageIndex = 0; // Reset to first page on page size change
        needsReload = true;
      }
      if (this.sortBy !== prefs.sortBy || this.sortOrder !== prefs.sortOrder) {
        this.sortBy = prefs.sortBy;
        this.sortOrder = prefs.sortOrder;
        needsReload = true;
      }

      if (needsReload) {
        this.reloadData();
      }
    });
  }

  get hasSelectedItems(): boolean {
    return this.items.some(item => item.selected);
  }

  get selectedItems(): FileItem[] {
    return this.items.filter(item => item.selected);
  }

  onViewModeChange(mode: 'grid' | 'list'): void {
    this.viewMode = mode;
  }

  onSortChange(event: { sortBy: string, sortOrder: 'ASC' | 'DESC' }) {
    this.sortBy = event.sortBy;
    this.sortOrder = event.sortOrder;
    this.userPreferencesService.setSort(this.sortBy, this.sortOrder);
    this.reloadData();
  }

  onItemClick(item: FileItem): void {
    item.selected = !item.selected;
  }

  onSelectionChange(event: { item: FileItem, selected: boolean }): void {
    event.item.selected = event.selected;
  }

  onSelectAll(selected: boolean): void {
    this.items.forEach(item => item.selected = selected);
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
            this.reloadData();
          },
          error: () => this.snackBar.open('Failed to rename item', 'Close', { duration: 3000 })
        });
      }
    });
  }

  onDownloadItem(item: FileItem): void {
    this.isDownloading = true;
    item.selected = false;
    this.documentApi.downloadDocument(item.id).subscribe({
      next: (blob) => {
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = item.name + (item.type === 'FILE' ? '' : '.zip');
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        window.URL.revokeObjectURL(url);
        this.isDownloading = false;
      },
      error: () => {
        this.snackBar.open('Failed to download file', 'Close', { duration: 3000 });
        this.isDownloading = false;
      }
    });
  }

  onMoveItem(item: FileItem): void {
    const dialogRef = this.dialog.open(FolderTreeDialogComponent, {
      width: '700px',
      data: { title: 'dialogs.folderTree.moveItem', actionType: 'move', excludeIds: [item.id] }
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
            this.reloadData();
          },
          error: () => this.snackBar.open('Failed to move item', 'Close', { duration: 3000 })
        });
      }
    });
  }

  onCopyItem(item: FileItem): void {
    const dialogRef = this.dialog.open(FolderTreeDialogComponent, {
      width: '700px',
      data: { title: 'dialogs.folderTree.copyItem', actionType: 'copy', excludeIds: [] }
    });

    dialogRef.afterClosed().subscribe(targetFolderId => {
      if (targetFolderId !== undefined) {
        const request: CopyRequest = { documentIds: [item.id], targetFolderId: targetFolderId || undefined };
        const copyObservable: Observable<any> = item.type === DocumentType.FOLDER
          ? this.documentApi.copyFolders(request)
          : this.documentApi.copyFiles(request);
        copyObservable.subscribe({
          next: () => {
            this.snackBar.open('Item copied successfully', 'Close', { duration: 3000 });
            this.reloadData();
          },
          error: () => this.snackBar.open('Failed to copy item', 'Close', { duration: 3000 })
        });
      }
    });
  }

  onDeleteItem(item: FileItem): void {
    const dialogData: ConfirmDialogData = {
      title: 'recycleBin.deleteConfirmItem',
      messageParams: { name: item.name },
      message: 'recycleBin.deleteConfirmItem', // Using title key as message for consistency with existing keys
      details: 'recycleBin.deleteDetails',
      type: 'danger',
      confirmText: 'common.delete',
      cancelText: 'common.cancel'
    };
    const dialogRef = this.dialog.open(ConfirmDialogComponent, {
      width: '450px',
      data: dialogData
    });
    dialogRef.afterClosed().subscribe(confirmed => {
      if (confirmed) {
        this.deleteItems([item]);
      }
    });
  }

  onDownloadSelected(): void {
    const selected = this.selectedItems;
    if (selected.length === 1) {
      this.onDownloadItem(selected[0]);
    } else if (selected.length > 1) {
      this.isDownloading = true;
      const documentIds = selected.map(item => item.id);
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
        error: () => {
          this.snackBar.open('Failed to download files', 'Close', { duration: 3000 });
          this.isDownloading = false;
        }
      });
    }
  }

  onMoveSelected(): void {
    const selected = this.selectedItems;
    if (selected.length > 0) {
      const dialogRef = this.dialog.open(FolderTreeDialogComponent, {
        width: '700px',
        data: {
          title: 'dialogs.folderTree.moveItems',
          titleParams: { count: selected.length },
          actionType: 'move',
          excludeIds: selected.map(item => item.id)
        }
      });
      dialogRef.afterClosed().subscribe(targetFolderId => {
        if (targetFolderId !== undefined) {
          this.performBulkMove(selected, targetFolderId);
        }
      });
    }
  }

  onCopySelected(): void {
    const selected = this.selectedItems;
    if (selected.length > 0) {
      const dialogRef = this.dialog.open(FolderTreeDialogComponent, {
        width: '700px',
        data: {
          title: 'dialogs.folderTree.copyItems',
          titleParams: { count: selected.length },
          actionType: 'copy',
          excludeIds: []
        }
      });
      dialogRef.afterClosed().subscribe(targetFolderId => {
        if (targetFolderId !== undefined) {
          this.performBulkCopy(selected, targetFolderId);
        }
      });
    }
  }

  onRenameSelected(): void {
    if (this.selectedItems.length === 1) {
      this.onRenameItem(this.selectedItems[0]);
    }
  }

  onDeleteSelected(): void {
    const selected = this.selectedItems;
    if (selected.length > 0) {
      const itemNames = selected.map(item => item.name).join(', ');
      const dialogData: ConfirmDialogData = {
        title: 'recycleBin.deleteConfirmMessage',
        messageParams: { count: selected.length },
        message: 'recycleBin.deleteConfirmMessage',
        details: 'recycleBin.deleteDetails',
        type: 'danger',
        confirmText: 'common.delete',
        cancelText: 'common.cancel'
      };
      const dialogRef = this.dialog.open(ConfirmDialogComponent, {
        width: '450px',
        data: dialogData
      });
      dialogRef.afterClosed().subscribe(confirmed => {
        if (confirmed) {
          this.deleteItems(selected);
        }
      });
    }
  }

  private deleteItems(itemsToDelete: FileItem[]): void {
    const folders = itemsToDelete.filter(item => item.type === 'FOLDER');
    const files = itemsToDelete.filter(item => item.type === 'FILE');
    const observables = [];
    if (folders.length > 0) {
      observables.push(this.documentApi.deleteFolders({ documentIds: folders.map(f => f.id) }));
    }
    if (files.length > 0) {
      observables.push(this.documentApi.deleteFiles({ documentIds: files.map(f => f.id) }));
    }
    observables.forEach(obs => obs.subscribe({
      next: () => {
        this.snackBar.open('Items deleted successfully', 'Close', { duration: 3000 });
        this.reloadData();
      },
      error: () => this.snackBar.open('Failed to delete items', 'Close', { duration: 3000 })
    }));
  }

  private performBulkMove(itemsToMove: FileItem[], targetFolderId: string | null): void {
    const folders = itemsToMove.filter(item => item.type === 'FOLDER');
    const files = itemsToMove.filter(item => item.type === 'FILE');
    const request: MoveRequest = { documentIds: [], targetFolderId: targetFolderId || undefined };
    if (folders.length > 0) {
      this.documentApi.moveFolders({ ...request, documentIds: folders.map(f => f.id) }).subscribe(this.bulkOperationObserver('moved'));
    }
    if (files.length > 0) {
      this.documentApi.moveFiles({ ...request, documentIds: files.map(f => f.id) }).subscribe(this.bulkOperationObserver('moved'));
    }
  }

  private performBulkCopy(itemsToCopy: FileItem[], targetFolderId: string | null): void {
    const folders = itemsToCopy.filter(item => item.type === 'FOLDER');
    const files = itemsToCopy.filter(item => item.type === 'FILE');
    const request: CopyRequest = { documentIds: [], targetFolderId: targetFolderId || undefined };
    if (folders.length > 0) {
      this.documentApi.copyFolders({ ...request, documentIds: folders.map(f => f.id) }).subscribe(this.bulkOperationObserver('copied'));
    }
    if (files.length > 0) {
      this.documentApi.copyFiles({ ...request, documentIds: files.map(f => f.id) }).subscribe(this.bulkOperationObserver('copied'));
    }
  }

  private bulkOperationObserver(action: 'moved' | 'copied') {
    return {
      next: () => {
        this.snackBar.open(`Items ${action} successfully`, 'Close', { duration: 3000 });
        this.reloadData();
      },
      error: () => this.snackBar.open(`Failed to ${action} items`, 'Close', { duration: 3000 })
    };
  }


  onClearSelection() {
    this.onSelectAll(false);
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
    this.userPreferencesService.setPageSize(newPageSize);
    this.pageIndex = 0;
    this.loadItems();
  }

  onItemDoubleClick(item: FileItem): void {
    if (item.type === 'FOLDER') {
      this.router.navigate(['/my-folder'], { queryParams: { folderId: item.id } });
    } else {
      this.onDownloadItem(item);
    }
  }

  abstract loadItems(): void;
}
