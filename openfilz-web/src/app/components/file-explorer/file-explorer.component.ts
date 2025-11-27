import {Component, ElementRef, OnDestroy, OnInit, ViewChild} from '@angular/core';
import { CommonModule } from '@angular/common';
import {ActivatedRoute, NavigationEnd, Router} from '@angular/router';
import {filter, Subscription, take} from 'rxjs';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatIcon, MatIconModule } from "@angular/material/icon";
import { MatButtonModule } from "@angular/material/button";

import { FileGridComponent } from '../file-grid/file-grid.component';
import { FileListComponent } from '../file-list/file-list.component';
import { ToolbarComponent } from '../toolbar/toolbar.component';
import { MetadataPanelComponent } from '../metadata-panel/metadata-panel.component';
import { CreateFolderDialogComponent } from '../../dialogs/create-folder-dialog/create-folder-dialog.component';
import { RenameDialogComponent, RenameDialogData } from '../../dialogs/rename-dialog/rename-dialog.component';
import { FolderTreeDialogComponent } from '../../dialogs/folder-tree-dialog/folder-tree-dialog.component';
import { FileViewerDialogComponent } from '../../dialogs/file-viewer-dialog/file-viewer-dialog.component';
import { KeyboardShortcutsDialogComponent } from '../../dialogs/keyboard-shortcuts-dialog/keyboard-shortcuts-dialog.component';
import { FileOperationsComponent } from '../base/file-operations.component';

import { DocumentApiService } from '../../services/document-api.service';
import { FileIconService } from '../../services/file-icon.service';
import { BreadcrumbService } from '../../services/breadcrumb.service';
import { SearchService } from '../../services/search.service';
import { UserPreferencesService } from '../../services/user-preferences.service';
import { KeyboardShortcutsService } from '../../services/keyboard-shortcuts.service';

import {
    CreateFolderRequest,
    ElementInfo,
    FileItem,
    ListFolderAndCountResponse,
    DocumentType,
    RenameRequest,
    MoveRequest,
    CopyRequest,
    DeleteRequest,
    SearchFilters
} from '../../models/document.models';

import { DragDropDirective } from "../../directives/drag-drop.directive";
import { DownloadProgressComponent } from "../download-progress/download-progress.component";
import {AppConfig} from '../../config/app.config';

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
        (pageSizeChange)="onPageSizeChange($event)"
        [sortBy]="sortBy"
        [sortOrder]="sortOrder"
        (sortChange)="onSortChange($event)">

        <!-- Breadcrumb projected into toolbar for mobile visibility -->
        <div toolbarBreadcrumb class="toolbar-breadcrumb-compact">
          @if(breadcrumbTrail.length > 0) {
            <button mat-icon-button (click)="navigateBack()"
                    aria-label="Navigate back"
                    class="breadcrumb-back-btn">
              <mat-icon>arrow_back</mat-icon>
            </button>
            <mat-icon class="separator">chevron_right</mat-icon>
            <span class="breadcrumb-text">{{ breadcrumbTrail[breadcrumbTrail.length - 1].name }}</span>
          } @else {
            <button mat-icon-button (click)="navigateToHome()"
                    aria-label="Home"
                    class="breadcrumb-home-btn">
              <mat-icon>home</mat-icon>
            </button>
            <span class="breadcrumb-text">My Folder</span>
          }
        </div>
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
                      (delete)="onDeleteItem($event)"
                      (toggleFavorite)="onToggleFavorite($event)"
                      (viewProperties)="onViewProperties($event)">
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
                      (delete)="onDeleteItem($event)"
                      (toggleFavorite)="onToggleFavorite($event)"
                      (toggleFavorite)="onToggleFavorite($event)"
                      (viewProperties)="onViewProperties($event)"
                      [sortBy]="sortBy"
                      [sortOrder]="sortOrder"
                      (sortChange)="onSortChange($event)">
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

      <!-- Metadata Panel -->
      <app-metadata-panel
        [documentId]="selectedDocumentForMetadata"
        [isOpen]="metadataPanelOpen"
        (closePanel)="closeMetadataPanel()"
        (metadataSaved)="onMetadataSaved()">
      </app-metadata-panel>
    </div>
  `,
  styleUrls: ['./file-explorer.component.css'],
  standalone: true,
  imports: [
    CommonModule,
    MatDialogModule,
    MatSnackBarModule,
    MatProgressSpinnerModule,
    MatIconModule,
    MatButtonModule,
    FileGridComponent,
    FileListComponent,
    ToolbarComponent,
    MetadataPanelComponent,
    MatIcon,
    DragDropDirective,
    DownloadProgressComponent
  ],
})
export class FileExplorerComponent extends FileOperationsComponent implements OnInit, OnDestroy {
  showUploadZone = false;
  fileOver: boolean = false;
  metadataPanelOpen: boolean = false;
  selectedDocumentForMetadata?: string;

  breadcrumbTrail: FileItem[] = []; // Track full path
  currentFolder?: FileItem;
  currentFilters?: SearchFilters;

  // Click delay handling
  private clickTimeout: any = null;
  private readonly CLICK_DELAY = 250; // milliseconds

  @ViewChild('fileInput') fileInput!: ElementRef;

  private routerEventsSubscription!: Subscription;
  private shortcutsSubscription?: Subscription;

  constructor(
    private fileIconService: FileIconService,
    private breadcrumbService: BreadcrumbService,
    private route: ActivatedRoute,
    private searchService: SearchService,
    private keyboardShortcuts: KeyboardShortcutsService,
    router: Router,
    documentApi: DocumentApiService,
    dialog: MatDialog,
    snackBar: MatSnackBar,
    userPreferencesService: UserPreferencesService
  ) {
    super(router, documentApi, dialog, snackBar, userPreferencesService);
  }

  // A new method to handle the logic
  private handleFolderIdChange(): void {
      const folderId = this.route.snapshot.queryParamMap.get('folderId');
      if (folderId) {
          this.loadFolderById(folderId);
      } else {
          this.loadFolder();
      }
  }

  ngOnDestroy(): void {
      if (this.routerEventsSubscription) {
          this.routerEventsSubscription.unsubscribe();
      }
      if (this.shortcutsSubscription) {
          this.shortcutsSubscription.unsubscribe();
      }
  }

  private registerKeyboardShortcuts(): void {
    // Register shortcuts for file explorer
    this.keyboardShortcuts.registerShortcut({
      key: 'u',
      ctrlKey: true,
      description: 'Upload files',
      action: () => this.triggerFileInput(),
      context: 'file-explorer'
    });

    this.keyboardShortcuts.registerShortcut({
      key: 'n',
      ctrlKey: true,
      description: 'Create new folder',
      action: () => this.onCreateFolder(),
      context: 'file-explorer'
    });

    this.keyboardShortcuts.registerShortcut({
      key: 'a',
      ctrlKey: true,
      description: 'Select all items',
      action: () => this.onSelectAll(true),
      context: 'file-explorer'
    });

    this.keyboardShortcuts.registerShortcut({
      key: 'Delete',
      description: 'Delete selected items',
      action: () => {
        if (this.hasSelectedItems) {
          this.onDeleteSelected();
        }
      },
      context: 'file-explorer'
    });

    this.keyboardShortcuts.registerShortcut({
      key: 'F2',
      description: 'Rename selected item',
      action: () => {
        if (this.selectedItems.length === 1) {
          this.onRenameSelected();
        }
      },
      context: 'file-explorer'
    });

    this.keyboardShortcuts.registerShortcut({
      key: 'Escape',
      description: 'Clear selection',
      action: () => {
        if (this.hasSelectedItems) {
          this.onSelectAll(false);
        }
      },
      context: 'file-explorer'
    });

    this.keyboardShortcuts.registerShortcut({
      key: 'd',
      ctrlKey: true,
      description: 'Download selected items',
      action: () => {
        if (this.hasSelectedItems) {
          this.onDownloadSelected();
        }
      },
      context: 'file-explorer'
    });

    this.keyboardShortcuts.registerShortcut({
      key: 'x',
      ctrlKey: true,
      description: 'Move selected items',
      action: () => {
        if (this.hasSelectedItems) {
          this.onMoveSelected();
        }
      },
      context: 'file-explorer'
    });

    this.keyboardShortcuts.registerShortcut({
      key: 'c',
      ctrlKey: true,
      shiftKey: true,
      description: 'Copy selected items',
      action: () => {
        if (this.hasSelectedItems) {
          this.onCopySelected();
        }
      },
      context: 'file-explorer'
    });

    this.keyboardShortcuts.registerShortcut({
      key: '?',
      shiftKey: true,
      description: 'Show keyboard shortcuts help',
      action: () => this.showKeyboardShortcuts(),
      context: 'global'
    });

    // Subscribe to shortcut events
    this.shortcutsSubscription = this.keyboardShortcuts.shortcutTriggered$.subscribe(
      shortcut => {
        // Shortcuts are handled by their action callbacks
        // This subscription is mainly for logging or analytics if needed
      }
    );
  }

  showKeyboardShortcuts(): void {
    this.dialog.open(KeyboardShortcutsDialogComponent, {
      width: '750px',
      maxWidth: '95vw',
      autoFocus: true,
      ariaLabelledBy: 'shortcuts-dialog-title',
      ariaDescribedBy: 'shortcuts-dialog-description'
    });
  }

  override ngOnInit() {
      super.ngOnInit();

      // Initial load
      //this.handleFolderIdChange();

      // Listen for subsequent navigation events to the same route
      this.routerEventsSubscription = this.router.events.pipe(
          // Filter for the NavigationEnd event
          filter(event => event instanceof NavigationEnd)
      ).subscribe(() => {
          // Manually trigger the logic when navigation ends
          this.handleFolderIdChange();
      });

    this.breadcrumbService.navigation$.subscribe(folder => {
      if (folder === null) {
        this.breadcrumbTrail = [];
        this.loadFolder(undefined, true);
      } else {
        const index = this.breadcrumbTrail.findIndex(f => f.id === folder.id);
        if (index !== -1) {
          this.breadcrumbTrail = this.breadcrumbTrail.slice(0, index + 1);
          this.loadFolder(this.breadcrumbTrail[index], true);
        }
      }
    });

    this.searchService.filters$.subscribe(filters => {
      this.currentFilters = filters;
      this.reloadData();
    });

    // Register keyboard shortcuts
    this.registerKeyboardShortcuts();
  }

  reloadData(): void {
    this.loadFolder(this.currentFolder);
  }

  private loadFolderById(folderId: string) {
    this.loading = true;
    this.documentApi.getDocumentInfo(folderId).subscribe({
      next: (folderInfo) => {
        const folderItem: FileItem = {
          id: folderId,
          name: folderInfo.name,
          type: DocumentType.FOLDER,
          size: folderInfo.size,
          icon: this.fileIconService.getFileIcon(folderInfo.name, 'FOLDER'),
          selected: false
        };
        this.breadcrumbTrail = [folderItem];
        this.loadFolder(folderItem, true);

        this.router.navigate([], {
            relativeTo: this.route,
            queryParams: {folderId: null},
            queryParamsHandling: 'merge',
        });
      },
      error: (err) => {
        this.snackBar.open('Could not load the specified folder.', 'Close', { duration: 3000 });
        this.loading = false;
        this.router.navigate(['/my-folder']);
        this.loadFolder();
      }
    });
  }

  loadFolder(folder?: FileItem, fromBreadcrumb: boolean = false, appendBreadCrumb: boolean = false) {
    this.loading = true;
    this.currentFolder = folder;

    if (!fromBreadcrumb) {
      if (folder) {
        if (appendBreadCrumb) {
          this.breadcrumbTrail.push(folder);
        }
      } else {
        this.breadcrumbTrail = [];
      }
    }

    this.documentApi.listFolderAndCount(this.currentFolder?.id, 1, this.pageSize, this.currentFilters, this.sortBy, this.sortOrder).subscribe({
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

  override loadItems() {
    this.loading = true;
    this.documentApi.listFolder(this.currentFolder?.id, this.pageIndex + 1, this.pageSize, this.currentFilters, this.sortBy, this.sortOrder).subscribe({
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
    this.breadcrumbService.updateBreadcrumbs(this.breadcrumbTrail);
  }

  navigateToHome() {
    this.breadcrumbTrail = [];
    this.loadFolder(undefined, true);
  }

  navigateBack() {
    if (this.breadcrumbTrail.length > 1) {
      // Navigate to parent folder (second to last in trail)
      this.breadcrumbTrail.pop(); // Remove current folder
      const parentFolder = this.breadcrumbTrail[this.breadcrumbTrail.length - 1];
      this.loadFolder(parentFolder, true);
    } else if (this.breadcrumbTrail.length === 1) {
      // Navigate to root
      this.navigateToHome();
    }
  }


  onFileOverChange(isOver: boolean) {
    this.fileOver = isOver;
  }

  override onItemClick(item: FileItem) {
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

  override onItemDoubleClick(item: FileItem) {
    // Clear the pending single-click timeout
    if (this.clickTimeout) {
      clearTimeout(this.clickTimeout);
      this.clickTimeout = null;
    }

    // Deselect the item if it was selected
    item.selected = false;

    if (item.type === 'FOLDER') {
      this.loadFolder(item, false, true);
    } else {
      // Open file viewer for files
      this.openFileViewer(item);
    }
  }

  private openFileViewer(item: FileItem) {
    const dialogRef = this.dialog.open(FileViewerDialogComponent, {
      width: '95vw',
      height: '95vh',
      maxWidth: '1400px',
      maxHeight: '900px',
      panelClass: 'file-viewer-dialog-container',
      data: {
        documentId: item.id,
        fileName: item.name,
        contentType: item.contentType || ''
      }
    });
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
          error: () => {
            this.snackBar.open('Failed to create folder', 'Close', { duration: 3000 });
          }
        });
      }
    });
  }

  override onRenameItem(item: FileItem) {
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

  override onDownloadItem(item: FileItem) {
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

  override onMoveItem(item: FileItem) {
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

  override onCopyItem(item: FileItem) {
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

  onToggleFavorite(item: FileItem) {
    const action = item.favorite ? 'remove from' : 'add to';
    this.documentApi.toggleFavorite(item.id).subscribe({
      next: () => {
        item.favorite = !item.favorite;
        this.snackBar.open(`Successfully ${action === 'add to' ? 'added to' : 'removed from'} favorites`, 'Close', { duration: 3000 });
      },
      error: (error) => {
        this.snackBar.open(`Failed to ${action} favorites`, 'Close', { duration: 3000 });
      }
    });
  }

  override onDownloadSelected() {
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

  override onMoveSelected() {
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
          this.performBulkMoveInternal(selectedItems, targetFolderId);
        }
      });
    }
  }

  override onCopySelected() {
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
          this.performBulkCopyInternal(selectedItems, targetFolderId);
        }
      });
    }
  }

  override onRenameSelected() {
    const selectedItems = this.selectedItems;
    if (selectedItems.length === 1) {
      this.onRenameItem(selectedItems[0]);
    }
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

  private performBulkMoveInternal(itemsToMove: FileItem[], targetFolderId: string | null): void {
    const folders = itemsToMove.filter(item => item.type === 'FOLDER');
    const files = itemsToMove.filter(item => item.type === 'FILE');

    let totalOperations = 0;
    if (folders.length > 0) totalOperations++;
    if (files.length > 0) totalOperations++;

    let completed = 0;
    const handleCompletion = () => {
      completed++;
      if (completed === totalOperations) {
        this.snackBar.open('Items moved successfully', 'Close', { duration: 3000 });
        this.reloadData();
      }
    };

    if (folders.length > 0) {
      this.performBulkMoveWithRetry(folders, targetFolderId, handleCompletion, 'folders');
    }

    if (files.length > 0) {
      this.performBulkMoveWithRetry(files, targetFolderId, handleCompletion, 'files');
    }
  }

  private performBulkMoveWithRetry(items: FileItem[], targetFolderId: string | null, onComplete: () => void, type: 'files' | 'folders', attempt: number = 1, maxAttempts: number = 5) {
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
            this.performBulkMoveWithRetry(items, targetFolderId, onComplete, type, attempt + 1, maxAttempts);
          } else {
            this.snackBar.open(`Name conflict detected. Maximum retry attempts (${maxAttempts}) reached.`, 'Close', { duration: 5000 });
          }
        } else {
          this.snackBar.open('Failed to move items', 'Close', { duration: 3000 });
        }
      }
    });
  }

  private performBulkCopyInternal(itemsToCopy: FileItem[], targetFolderId: string | null): void {
    const folders = itemsToCopy.filter(item => item.type === 'FOLDER');
    const files = itemsToCopy.filter(item => item.type === 'FILE');

    let totalOperations = 0;
    if (folders.length > 0) totalOperations++;
    if (files.length > 0) totalOperations++;

    let completed = 0;
    const handleCompletion = () => {
      completed++;
      if (completed === totalOperations) {
        this.snackBar.open('Items copied successfully', 'Close', { duration: 3000 });
        this.reloadData();
      }
    };

    if (folders.length > 0) {
      this.performBulkCopyWithRetry(folders, targetFolderId, handleCompletion, 'folders');
    }

    if (files.length > 0) {
      this.performBulkCopyWithRetry(files, targetFolderId, handleCompletion, 'files');
    }
  }

  private performBulkCopyWithRetry(items: FileItem[], targetFolderId: string | null, onComplete: () => void, type: 'files' | 'folders', attempt: number = 1, maxAttempts: number = 5) {
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
              this.performBulkCopyWithRetry(items, targetFolderId, onComplete, type, attempt + 1, maxAttempts);
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
              this.performBulkCopyWithRetry(items, targetFolderId, onComplete, type, attempt + 1, maxAttempts);
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
    const fileArray = Array.from(files);
    const isSingleFile = fileArray.length === 1;
    const singleFileName = isSingleFile ? fileArray[0].name : undefined;

    // Show uploading notification
    const uploadMessage = isSingleFile
      ? `Uploading ${singleFileName}...`
      : `Uploading ${fileArray.length} files...`;
    this.snackBar.open(uploadMessage, undefined, { duration: undefined });

    // Upload files directly without dialog
    this.documentApi.uploadMultipleDocuments(
      fileArray,
      this.currentFolder?.id,
      false // allowDuplicates = false by default
    ).subscribe({
      next: (response) => {
        // Response contains info about uploaded files
        this.snackBar.open('Upload successful', 'Close', { duration: 3000 });
        this.reloadData();
      },
      error: (error) => {
        this.snackBar.dismiss();
        const errorMessage = isSingleFile
          ? `Failed to upload ${singleFileName}`
          : `Failed to upload ${fileArray.length} files`;
        this.snackBar.open(errorMessage, 'Close', { duration: 5000 });
      },
      complete: () => {
        this.snackBar.dismiss();

        const successMessage = isSingleFile
          ? `${singleFileName} uploaded successfully`
          : `${fileArray.length} files uploaded successfully`;
        this.snackBar.open(successMessage, 'Close', { duration: 3000 });

        // Reload folder and open metadata panel for single file upload
        if (isSingleFile && singleFileName) {
          // Reload the folder first
          this.documentApi.listFolder(this.currentFolder?.id, this.pageIndex + 1, this.pageSize).subscribe({
            next: (response) => {
              this.populateFolderContents(response);

              // Find the uploaded file by name
              const uploadedItem = this.items.find(item => item.name === singleFileName);
              if (uploadedItem) {
                // Open metadata panel after a short delay for smooth UX
                setTimeout(() => {
                  this.openMetadataPanel(uploadedItem.id);
                }, 300);
              }
            },
            error: () => {
              this.loadFolder(this.currentFolder);
            }
          });
        } else {
          // For multiple files, just reload the folder
          this.loadFolder(this.currentFolder);
        }
      }
    });
  }

  openMetadataPanel(documentId: string) {
    this.selectedDocumentForMetadata = documentId;
    this.metadataPanelOpen = true;
  }

  closeMetadataPanel() {
    this.metadataPanelOpen = false;
    this.selectedDocumentForMetadata = undefined;
  }

  onMetadataSaved() {
    // Optionally reload the folder to reflect metadata changes
    this.loadFolder(this.currentFolder);
  }

  onViewProperties(item: FileItem) {
    this.openMetadataPanel(item.id);
  }
}