import {Component, OnInit} from '@angular/core';
import {CommonModule} from '@angular/common';
import {MatButtonModule} from '@angular/material/button';
import {MatIconModule} from '@angular/material/icon';
import {MatSnackBar, MatSnackBarModule} from '@angular/material/snack-bar';
import {MatProgressSpinnerModule} from '@angular/material/progress-spinner';
import {MatButtonToggleModule} from '@angular/material/button-toggle';
import {DocumentApiService} from '../../services/document-api.service';
import {FileGridComponent} from '../../components/file-grid/file-grid.component';
import {FileListComponent} from '../../components/file-list/file-list.component';
import {ToolbarComponent} from '../../components/toolbar/toolbar.component';
import {ElementInfo, FileItem} from '../../models/document.models';
import {FileIconService} from '../../services/file-icon.service';
import {BreadcrumbService} from '../../services/breadcrumb.service';
import {AppConfig} from '../../config/app.config';

@Component({
  selector: 'app-favorites',
  standalone: true,
  imports: [
    CommonModule,
    MatButtonModule,
    MatIconModule,
    MatSnackBarModule,
    MatProgressSpinnerModule,
    MatButtonToggleModule,
    FileGridComponent,
    FileListComponent,
    ToolbarComponent
  ],
  templateUrl: './favorites.component.html',
  styleUrls: ['./favorites.component.css']
})
export class FavoritesComponent implements OnInit {
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

  constructor(
    private documentApi: DocumentApiService,
    private fileIconService: FileIconService,
    private snackBar: MatSnackBar,
    private breadcrumbService: BreadcrumbService
  ) {}

  ngOnInit() {
    this.loadFavorites();

    // Listen for breadcrumb navigation
    this.breadcrumbService.navigation$.subscribe(folder => {
      if (folder === null) {
        // Navigate back to favorites root
        this.breadcrumbTrail = [];
        this.currentFolder = undefined;
        this.loadFavorites();
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

  loadFavorites() {
    this.loading = true;

    // If we're in a folder, load that folder's contents
    if (this.currentFolder) {
      this.loadFolder(this.currentFolder);
      return;
    }

    // Otherwise, load root favorites
    this.documentApi.listFavorites().subscribe({
      next: (favorites: ElementInfo[]) => {
        this.items = favorites.map(fav => ({
          ...fav,
          selected: false,
          isFavorite: true, // All items in favorites are favorited
          icon: this.fileIconService.getFileIcon(fav.name, fav.type)
        }));
        this.totalItems = this.items.length;
        this.loading = false;
        this.updateBreadcrumbs();
      },
      error: (error) => {
        console.error('Error loading favorites:', error);
        this.snackBar.open('Failed to load favorites', 'Close', { duration: 3000 });
        this.loading = false;
      }
    });
  }

  loadFolder(folder: FileItem) {
    this.loading = true;
    this.currentFolder = folder;

    // Load the folder's contents (max page size is 100)
    this.documentApi.listFolder(folder.id, 1, 100).subscribe({
      next: (contents: ElementInfo[]) => {
        this.items = contents.map(item => ({
          ...item,
          selected: false,
          isFavorite: false, // Contents may or may not be favorited
          icon: this.fileIconService.getFileIcon(item.name, item.type)
        }));
        this.totalItems = this.items.length;
        this.loading = false;
        this.updateBreadcrumbs();
      },
      error: (error) => {
        console.error('Error loading folder:', error);
        this.snackBar.open('Failed to load folder contents', 'Close', { duration: 3000 });
        this.loading = false;
      }
    });
  }

  private updateBreadcrumbs() {
    this.breadcrumbService.updateBreadcrumbs(this.breadcrumbTrail);
  }

  onViewModeChange(mode: 'grid' | 'list') {
    this.viewMode = mode;
  }

  onPreviousPage() {
    // Favorites doesn't have server-side pagination
    // This could be implemented with client-side pagination if needed
  }

  onNextPage() {
    // Favorites doesn't have server-side pagination
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
      this.onDownloadItem(item);
    }
  }

  onSelectionChange(event: { item: FileItem, selected: boolean }) {
    event.item.selected = event.selected;
  }

  onSelectAll(selected: boolean) {
    this.items.forEach(item => item.selected = selected);
  }

  onRenameSelected() {
    const selectedItems = this.selectedItems;
    if (selectedItems.length === 1) {
      this.onRenameItem(selectedItems[0]);
    }
  }

  onDownloadSelected() {
    const selectedItems = this.selectedItems;
    if (selectedItems.length === 1) {
      this.onDownloadItem(selectedItems[0]);
    } else if (selectedItems.length > 1) {
      const documentIds = selectedItems.map(item => item.id);
      this.documentApi.downloadMultipleDocuments(documentIds).subscribe({
        next: (blob) => {
          const url = window.URL.createObjectURL(blob);
          const a = document.createElement('a');
          a.href = url;
          a.download = 'favorites.zip';
          document.body.appendChild(a);
          a.click();
          document.body.removeChild(a);
          window.URL.revokeObjectURL(url);
          this.snackBar.open('Download started', 'Close', { duration: 2000 });
        },
        error: (error) => {
          console.error('Error downloading files:', error);
          this.snackBar.open('Failed to download files', 'Close', { duration: 3000 });
        }
      });
    }
  }

  onMoveSelected() {
    this.snackBar.open('Move is not available in favorites view', 'Close', { duration: 2000 });
  }

  onCopySelected() {
    this.snackBar.open('Copy is not available in favorites view', 'Close', { duration: 2000 });
  }

  onDeleteSelected() {
    this.snackBar.open('Delete is not available in favorites view', 'Close', { duration: 2000 });
  }

  onClearSelection() {
    this.onSelectAll(false);
  }

  onToggleFavorite(item: FileItem) {
    this.documentApi.toggleFavorite(item.id).subscribe({
      next: (isFavorited) => {
        if (!isFavorited) {
          // Item was unfavorited, remove from list
          this.items = this.items.filter(i => i.id !== item.id);
          this.snackBar.open(`"${item.name}" removed from favorites`, 'Close', { duration: 2000 });
        }
      },
      error: (error) => {
        console.error('Error toggling favorite:', error);
        this.snackBar.open('Failed to update favorite status', 'Close', { duration: 3000 });
      }
    });
  }

  onRenameItem(item: FileItem) {
    // Not implemented in this view
    this.snackBar.open('Rename is not available in favorites view', 'Close', { duration: 2000 });
  }

  onDownloadItem(item: FileItem) {
    if (item.type === 'FILE') {
      this.documentApi.downloadDocument(item.id).subscribe({
        next: (blob) => {
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
        error: (error) => {
          console.error('Error downloading file:', error);
          this.snackBar.open('Failed to download file', 'Close', { duration: 3000 });
        }
      });
    }
  }

  onMoveItem(item: FileItem) {
    // Not implemented in this view
    this.snackBar.open('Move is not available in favorites view', 'Close', { duration: 2000 });
  }

  onCopyItem(item: FileItem) {
    // Not implemented in this view
    this.snackBar.open('Copy is not available in favorites view', 'Close', { duration: 2000 });
  }

  onDeleteItem(item: FileItem) {
    // Not implemented in this view
    this.snackBar.open('Delete is not available in favorites view', 'Close', { duration: 2000 });
  }
}
