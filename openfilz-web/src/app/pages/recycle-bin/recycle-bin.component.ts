import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog } from '@angular/material/dialog';
import { DocumentApiService } from '../../services/document-api.service';
import { FileGridComponent } from '../../components/file-grid/file-grid.component';
import { FileListComponent } from '../../components/file-list/file-list.component';
import { ToolbarComponent } from '../../components/toolbar/toolbar.component';
import { ElementInfo, FileItem, ListFolderAndCountResponse } from '../../models/document.models';
import { FileIconService } from '../../services/file-icon.service';
import { MatTooltipModule } from '@angular/material/tooltip';
import { BreadcrumbService } from '../../services/breadcrumb.service';
import { AppConfig } from '../../config/app.config';
import { ConfirmDialogComponent, ConfirmDialogData } from '../../dialogs/confirm-dialog/confirm-dialog.component';

@Component({
  selector: 'app-recycle-bin',
  standalone: true,
  imports: [
    CommonModule,
    MatButtonModule,
    MatIconModule,
    MatSnackBarModule,
    MatProgressSpinnerModule,
    MatButtonToggleModule,
    MatTooltipModule,
    FileGridComponent,
    FileListComponent,
    ToolbarComponent
  ],
  templateUrl: './recycle-bin.component.html',
  styleUrls: ['./recycle-bin.component.css']
})
export class RecycleBinComponent implements OnInit {
  items: FileItem[] = [];
  loading = false;
  viewMode: 'grid' | 'list' = 'grid';

  // Pagination properties
  totalItems = 0;
  pageSize = AppConfig.pagination.defaultPageSize;
  pageIndex = 0;

  // Folder navigation
  currentFolder?: FileItem;
  breadcrumbTrail: FileItem[] = [];

  // Click delay handling
  private clickTimeout: any = null;
  private readonly CLICK_DELAY = 250; // milliseconds

  // Mobile FAB state
  fabOpen = false;

  private documentApi = inject(DocumentApiService);
  private fileIconService = inject(FileIconService);
  private snackBar = inject(MatSnackBar);
  private dialog = inject(MatDialog);
  private breadcrumbService = inject(BreadcrumbService);

  constructor() { }

  ngOnInit() {
    this.loadDeletedItems();

    // Listen for breadcrumb navigation
    this.breadcrumbService.navigation$.subscribe((folder: FileItem | null) => {
      if (folder === null) {
        // Navigate back to recycle bin root
        this.breadcrumbTrail = [];
        this.currentFolder = undefined;
        this.loadDeletedItems();
      } else {
        // Navigate to specific folder in breadcrumb trail
        const index = this.breadcrumbTrail.findIndex(f => f.id === folder.id);
        if (index !== -1) {
          // Remove all folders after this one in the trail
          this.breadcrumbTrail = this.breadcrumbTrail.slice(0, index + 1);
          this.currentFolder = this.breadcrumbTrail[index];
          this.loadFolder(this.currentFolder);
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

  get selectedItemsCount(): number {
    return this.selectedItems.length;
  }

  loadDeletedItems() {
    this.loading = true;

    // If we're in a folder, load that folder's contents
    if (this.currentFolder) {
      this.loadFolder(this.currentFolder);
      return;
    }

    // Otherwise, load root recycle bin items
    this.documentApi.listDeletedItems().subscribe({
      next: (deletedItems: ElementInfo[]) => {
        this.items = deletedItems.map(item => ({
          ...item,
          selected: false,
          favorite: false, // Items in recycle bin don't show favorite status
          icon: this.fileIconService.getFileIcon(item.name, item.type)
        }));
        this.totalItems = this.items.length;
        this.loading = false;
        this.updateBreadcrumbs();
      },
      error: (error: any) => {
        console.error('Error loading recycle bin:', error);
        this.snackBar.open('Failed to load recycle bin', 'Close', { duration: 3000 });
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
    this.loading = false;
  }

  loadFolder(folder: FileItem) {
    this.loading = true;
    this.currentFolder = folder;

    // Load the folder's contents (max page size is 100)
    this.documentApi.listDeletedFolderAndCount(folder.id, 1, 100).subscribe({
      next: (listAndCount: ListFolderAndCountResponse) => {
        this.totalItems = listAndCount.count;
        this.pageIndex = 0;
        this.populateFolderContents(listAndCount.listFolder);
      },
      error: (error: any) => {
        console.error('Error loading folder:', error);
        this.snackBar.open('Failed to load folder contents', 'Close', { duration: 3000 });
        this.loading = false;
      }
    });
  }

  private updateBreadcrumbs() {
    this.breadcrumbService.updateBreadcrumbs(this.breadcrumbTrail);
  }

  navigateToHome() {
    this.breadcrumbTrail = [];
    this.currentFolder = undefined;
    this.loadDeletedItems();
  }

  navigateBack() {
    if (this.breadcrumbTrail.length > 1) {
      // Navigate to parent folder (second to last in trail)
      this.breadcrumbTrail.pop(); // Remove current folder
      const parentFolder = this.breadcrumbTrail[this.breadcrumbTrail.length - 1];
      this.currentFolder = parentFolder;
      this.loadDeletedItems();
    } else if (this.breadcrumbTrail.length === 1) {
      // Navigate to root
      this.navigateToHome();
    }
  }

  onViewModeChange(mode: 'grid' | 'list') {
    this.viewMode = mode;
  }

  onPreviousPage() {
    // Recycle bin doesn't have server-side pagination
    // This could be implemented with client-side pagination if needed
  }

  onNextPage() {
    // Recycle bin doesn't have server-side pagination
    // This could be implemented with client-side pagination if needed
  }

  onPageSizeChange(newPageSize: number) {
    this.pageSize = newPageSize;
    // Could implement client-side pagination if needed
  }

  onItemClick(item: FileItem) {
    // Clear any existing timeout
    if (this.clickTimeout) {
      clearTimeout(this.clickTimeout);
    }

    // Delay the selection to allow double-click to be detected
    this.clickTimeout = setTimeout(() => {
      item.selected = !item.selected;
      this.clickTimeout = null;
    }, this.CLICK_DELAY);
  }

  onItemDoubleClick(item: FileItem) {
    // Clear the pending single-click timeout
    if (this.clickTimeout) {
      clearTimeout(this.clickTimeout);
      this.clickTimeout = null;
    }

    // Deselect the item if it was selected
    item.selected = false;

    if (item.type === 'FOLDER') {
      // Navigate into the folder
      this.breadcrumbTrail.push(item);
      this.loadFolder(item);
    } else if (item.type === 'FILE') {
      // Could download or preview the file
      this.onDownloadItem(item);
    }
  }

  onSelectionChange(event: { item: FileItem, selected: boolean }) {
    event.item.selected = event.selected;
  }

  onSelectAll(selected: boolean) {
    this.items.forEach(item => item.selected = selected);
  }

  onClearSelection() {
    this.onSelectAll(false);
  }

  onRenameSelected() {
    this.snackBar.open('Rename is not available in recycle bin', 'Close', { duration: 2000 });
  }

  onDownloadSelected() {
    const selectedItems = this.selectedItems;
    if (selectedItems.length === 1 && selectedItems[0].type === 'FILE') {
      this.onDownloadItem(selectedItems[0]);
    } else {
      this.snackBar.open('Download is only available for individual files', 'Close', { duration: 2000 });
    }
  }

  onMoveSelected() {
    this.snackBar.open('Move is not available in recycle bin. Use restore instead.', 'Close', { duration: 2000 });
  }

  onCopySelected() {
    this.snackBar.open('Copy is not available in recycle bin', 'Close', { duration: 2000 });
  }

  onDeleteSelected() {
    this.permanentlyDeleteSelected();
  }

  // Restore selected items
  restoreSelected() {
    const selectedItems = this.selectedItems;
    if (selectedItems.length === 0) {
      this.snackBar.open('Please select items to restore', 'Close', { duration: 2000 });
      return;
    }

    const documentIds = selectedItems.map(item => item.id);
    this.documentApi.restoreItems({ documentIds }).subscribe({
      next: () => {
        this.snackBar.open(`${selectedItems.length} item(s) restored successfully`, 'Close', { duration: 3000 });
        this.items = this.items.filter(item => !item.selected);
        this.totalItems = this.items.length;
      },
      error: (error: any) => {
        console.error('Error restoring items:', error);
        this.snackBar.open('Failed to restore items', 'Close', { duration: 3000 });
      }
    });
  }

  // Permanently delete selected items
  permanentlyDeleteSelected() {
    const selectedItems = this.selectedItems;
    if (selectedItems.length === 0) {
      this.snackBar.open('Please select items to permanently delete', 'Close', { duration: 2000 });
      return;
    }

    const dialogData: ConfirmDialogData = {
      title: 'Permanently Delete',
      message: `Are you sure you want to permanently delete ${selectedItems.length} item(s)?`,
      details: 'This action cannot be undone. These items will be permanently removed.',
      type: 'danger',
      confirmText: 'Delete Forever',
      cancelText: 'Cancel',
      icon: 'delete_forever'
    };
    const dialogRef = this.dialog.open(ConfirmDialogComponent, {
      width: '450px',
      data: dialogData
    });
    dialogRef.afterClosed().subscribe(confirmed => {
      if (!confirmed) return;

      const documentIds = selectedItems.map(item => item.id);
      this.documentApi.permanentlyDeleteItems({ documentIds }).subscribe({
        next: () => {
          this.snackBar.open(`${selectedItems.length} item(s) permanently deleted`, 'Close', { duration: 3000 });
          this.items = this.items.filter(item => !item.selected);
          this.totalItems = this.items.length;
        },
        error: (error: any) => {
          console.error('Error permanently deleting items:', error);
          this.snackBar.open('Failed to permanently delete items', 'Close', { duration: 3000 });
        }
      });
    });
  }

  // Empty entire recycle bin
  emptyRecycleBin() {
    if (this.items.length === 0) {
      this.snackBar.open('Recycle bin is already empty', 'Close', { duration: 2000 });
      return;
    }

    const dialogData: ConfirmDialogData = {
      title: 'Empty Recycle Bin',
      message: `Are you sure you want to permanently delete all ${this.items.length} item(s) in the recycle bin?`,
      details: 'This action cannot be undone. All items will be permanently removed.',
      type: 'danger',
      confirmText: 'Empty Recycle Bin',
      cancelText: 'Cancel',
      icon: 'delete_sweep'
    };
    const dialogRef = this.dialog.open(ConfirmDialogComponent, {
      width: '450px',
      data: dialogData
    });
    dialogRef.afterClosed().subscribe(confirmed => {
      if (!confirmed) return;

      this.documentApi.emptyRecycleBin().subscribe({
        next: () => {
          this.snackBar.open('Recycle bin emptied successfully', 'Close', { duration: 3000 });
          this.items = [];
          this.totalItems = 0;
        },
        error: (error: any) => {
          console.error('Error emptying recycle bin:', error);
          this.snackBar.open('Failed to empty recycle bin', 'Close', { duration: 3000 });
        }
      });
    });
  }

  // Event handlers for context menu actions (adapted for recycle bin)
  onToggleFavorite(item: FileItem) {
    this.snackBar.open('Favorites are not available for deleted items', 'Close', { duration: 2000 });
  }

  onRenameItem(item: FileItem) {
    this.snackBar.open('Rename is not available in recycle bin', 'Close', { duration: 2000 });
  }

  onDownloadItem(item: FileItem) {
    if (item.type === 'FILE') {
      this.documentApi.downloadDocument(item.id).subscribe({
        next: (blob: Blob) => {
          const url = window.URL.createObjectURL(blob);
          const a = document.createElement('a');
          a.href = url;
          a.download = item.name;
          document.body.appendChild(a);
          a.click();
          document.body.removeChild(a);
          window.URL.revokeObjectURL(url);
          this.snackBar.open('Download started', 'Close', { duration: 2000 });
        },
        error: (error: any) => {
          console.error('Error downloading file:', error);
          this.snackBar.open('Failed to download file', 'Close', { duration: 3000 });
        }
      });
    }
  }

  onMoveItem(item: FileItem) {
    this.snackBar.open('Move is not available in recycle bin. Use restore instead.', 'Close', { duration: 2000 });
  }

  onCopyItem(item: FileItem) {
    this.snackBar.open('Copy is not available in recycle bin', 'Close', { duration: 2000 });
  }

  onDeleteItem(item: FileItem) {
    // In recycle bin, "delete" means permanent delete
    const dialogData: ConfirmDialogData = {
      title: 'Permanently Delete',
      message: `Are you sure you want to permanently delete "${item.name}"?`,
      details: 'This action cannot be undone. This item will be permanently removed.',
      type: 'danger',
      confirmText: 'Delete Forever',
      cancelText: 'Cancel',
      icon: 'delete_forever'
    };
    const dialogRef = this.dialog.open(ConfirmDialogComponent, {
      width: '450px',
      data: dialogData
    });
    dialogRef.afterClosed().subscribe(confirmed => {
      if (!confirmed) return;

      this.documentApi.permanentlyDeleteItems({ documentIds: [item.id] }).subscribe({
        next: () => {
          this.snackBar.open(`"${item.name}" permanently deleted`, 'Close', { duration: 3000 });
          this.items = this.items.filter(i => i.id !== item.id);
        },
        error: (error: any) => {
          console.error('Error permanently deleting item:', error);
          this.snackBar.open('Failed to permanently delete item', 'Close', { duration: 3000 });
        }
      });
    });
  }

  // Restore a single item (could be called from context menu)
  restoreItem(item: FileItem) {
    this.documentApi.restoreItems({ documentIds: [item.id] }).subscribe({
      next: () => {
        this.snackBar.open(`"${item.name}" restored successfully`, 'Close', { duration: 2000 });
        this.items = this.items.filter(i => i.id !== item.id);
      },
      error: (error: any) => {
        console.error('Error restoring item:', error);
        this.snackBar.open('Failed to restore item', 'Close', { duration: 3000 });
      }
    });
  }

  // Mobile FAB methods
  toggleFab() {
    this.fabOpen = !this.fabOpen;
  }

  closeFab() {
    this.fabOpen = false;
  }

  onRestoreFromFab() {
    this.closeFab();
    this.restoreSelected();
  }

  onDeleteForeverFromFab() {
    this.closeFab();
    this.permanentlyDeleteSelected();
  }

  onEmptyBinFromFab() {
    this.closeFab();
    this.emptyRecycleBin();
  }
}
