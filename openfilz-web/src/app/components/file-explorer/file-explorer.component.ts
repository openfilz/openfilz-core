import { Component, ElementRef, HostListener, OnDestroy, OnInit, ViewChild, inject } from '@angular/core';
import { Location } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { forkJoin, Subscription } from 'rxjs';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatIcon, MatIconModule } from "@angular/material/icon";
import { MatButtonModule } from "@angular/material/button";
import { TranslatePipe } from "@ngx-translate/core";

import { FileGridComponent } from '../file-grid/file-grid.component';
import { FileListComponent } from '../file-list/file-list.component';
import { ToolbarComponent } from '../toolbar/toolbar.component';
import { MetadataPanelComponent } from '../metadata-panel/metadata-panel.component';
import { CreateFolderDialogComponent } from '../../dialogs/create-folder-dialog/create-folder-dialog.component';
import { RenameDialogComponent, RenameDialogData } from '../../dialogs/rename-dialog/rename-dialog.component';
import { FolderTreeDialogComponent } from '../../dialogs/folder-tree-dialog/folder-tree-dialog.component';
import { FileViewerDialogComponent } from '../../dialogs/file-viewer-dialog/file-viewer-dialog.component';
import { KeyboardShortcutsDialogComponent } from '../../dialogs/keyboard-shortcuts-dialog/keyboard-shortcuts-dialog.component';
import { ConfirmReplaceDialogComponent, ConfirmReplaceDialogData, ConfirmReplaceDialogResult } from '../../dialogs/confirm-replace-dialog/confirm-replace-dialog.component';
import { PartialUploadResultDialogComponent, PartialUploadResultDialogData } from '../../dialogs/partial-upload-result-dialog/partial-upload-result-dialog.component';
import { FileOperationsComponent } from '../base/file-operations.component';

import { DocumentApiService } from '../../services/document-api.service';
import { FileIconService } from '../../services/file-icon.service';
import { BreadcrumbService } from '../../services/breadcrumb.service';
import { SearchService } from '../../services/search.service';
import { UserPreferencesService } from '../../services/user-preferences.service';
import { KeyboardShortcutsService } from '../../services/keyboard-shortcuts.service';

import {
  AncestorInfo,
  CreateFolderRequest,
  DocumentPosition,
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
import { AppConfig } from '../../config/app.config';
import { DropEvent } from '../../services/drag-drop.service';
import { BreadcrumbComponent } from '../breadcrumb/breadcrumb.component';

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
        (sortChange)="onSortChange($event)"
      >

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

      <!-- Breadcrumb with drop zones for drag-and-drop navigation -->
      <div class="breadcrumb-bar">
        <app-breadcrumb
          [breadcrumbs]="breadcrumbTrail"
          [currentFolderId]="currentFolder?.id ?? null"
          (navigate)="onBreadcrumbNavigate($event)"
          (itemsDropped)="onDragDropMove($event)">
        </app-breadcrumb>
      </div>

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
                      <h3>{{ 'fileExplorer.emptyTitle' | translate }}</h3>
                      <p>{{ 'fileExplorer.emptyMessage' | translate }}</p>
                  </div>
              </div>
          }
          @if(viewMode === 'grid' && items.length > 0) {
              <app-file-grid
                      [items]="items"
                      [fileOver]="fileOver"
                      [currentFolderId]="currentFolder?.id ?? null"
                      (itemClick)="onItemClick($event)"
                      (itemDoubleClick)="onItemDoubleClick($event)"
                      (selectionChange)="onSelectionChange($event)"
                      (rename)="onRenameItem($event)"
                      (download)="onDownloadItem($event)"
                      (move)="onMoveItem($event)"
                      (copy)="onCopyItem($event)"
                      (delete)="onDeleteItem($event)"
                      (toggleFavorite)="onToggleFavorite($event)"
                      (viewProperties)="onViewProperties($event)"
                      (itemsDroppedOnFolder)="onDragDropMove($event)">
              </app-file-grid>
          }
          @if(viewMode === 'list' && items.length > 0) {
              <app-file-list
                      [items]="items"
                      [fileOver]="fileOver"
                      [currentFolderId]="currentFolder?.id ?? null"
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
                      (sortChange)="onSortChange($event)"
                      (itemsDroppedOnFolder)="onDragDropMove($event)">
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
    DownloadProgressComponent,
    TranslatePipe,
    BreadcrumbComponent
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

  // Flag to track if navigation is triggered by URL change (browser back/forward)
  private isNavigatingFromUrl = false;
  private queryParamsSubscription?: Subscription;

  @ViewChild('fileInput') fileInput!: ElementRef;

  private shortcutsSubscription?: Subscription;

  private fileIconService = inject(FileIconService);
  private breadcrumbService = inject(BreadcrumbService);
  private route = inject(ActivatedRoute);
  private searchService = inject(SearchService);
  private keyboardShortcuts = inject(KeyboardShortcutsService);
  private location = inject(Location);

  constructor() {
    super();
  }

  /**
   * Updates the URL with the current folder ID.
   * This enables browser back/forward navigation.
   */
  private updateUrlWithFolder(folderId?: string): void {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { folderId: folderId || null },
      queryParamsHandling: 'merge',
      replaceUrl: false  // Creates a new history entry
    });
  }

  ngOnDestroy(): void {
    if (this.queryParamsSubscription) {
      this.queryParamsSubscription.unsubscribe();
    }
    if (this.shortcutsSubscription) {
      this.shortcutsSubscription.unsubscribe();
    }
  }

  @HostListener('window:popstate', ['$event'])
  onBrowserBack(event: PopStateEvent): void {
    // If metadata panel is open when browser back is clicked, close it and stay on current page
    if (this.metadataPanelOpen) {
      this.closeMetadataPanel();
      // Go forward to cancel the back navigation (stay on current page)
      history.go(1);
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

    // Subscribe to query params for browser back/forward navigation
    this.queryParamsSubscription = this.route.queryParams.subscribe(params => {
      const folderId = params['folderId'];
      const targetFileId = params['targetFileId'];
      const openViewer = params['openViewer'] === 'true';

      // Handle targetFileId navigation (from search results, etc.)
      if (targetFileId) {
        this.navigateToFile(targetFileId, openViewer);
        return;
      }

      // Skip if this navigation was triggered by us updating the URL
      if (this.isNavigatingFromUrl) {
        this.isNavigatingFromUrl = false;
        return;
      }

      // Check if folderId changed (browser back/forward)
      const currentFolderId = this.currentFolder?.id;
      if (folderId !== currentFolderId) {
        if (folderId) {
          // Navigate to specific folder
          this.loadFolderById(folderId);
        } else if (currentFolderId) {
          // Going back to root
          this.breadcrumbTrail = [];
          this.loadFolder(undefined, true, false, false);
        } else {
          // Initial load at root
          this.loadFolder(undefined, true, false, false);
        }
      }
    });

    this.breadcrumbService.navigation$.subscribe(folder => {
      // Close metadata panel if open when navigating via breadcrumb
      if (this.metadataPanelOpen) {
        this.closeMetadataPanel();
      }

      if (folder === null) {
        this.breadcrumbTrail = [];
        this.loadFolder(undefined, true, false, true);
      } else {
        const index = this.breadcrumbTrail.findIndex(f => f.id === folder.id);
        if (index !== -1) {
          this.breadcrumbTrail = this.breadcrumbTrail.slice(0, index + 1);
          this.loadFolder(this.breadcrumbTrail[index], true, false, true);
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

    // Fetch folder info and ancestors in parallel
    forkJoin({
      folderInfo: this.documentApi.getDocumentInfo(folderId),
      ancestors: this.documentApi.getDocumentAncestors(folderId)
    }).subscribe({
      next: ({ folderInfo, ancestors }) => {
        // Build breadcrumb trail from ancestors + current folder
        this.breadcrumbTrail = [
          ...ancestors.map(ancestor => ({
            id: ancestor.id,
            name: ancestor.name,
            type: DocumentType.FOLDER,
            selected: false,
            icon: this.fileIconService.getFileIcon(ancestor.name, 'FOLDER')
          } as FileItem)),
          {
            id: folderId,
            name: folderInfo.name,
            type: DocumentType.FOLDER,
            size: folderInfo.size,
            icon: this.fileIconService.getFileIcon(folderInfo.name, 'FOLDER'),
            selected: false
          }
        ];

        // Load folder without updating history (URL already has the folderId)
        this.loadFolder(this.breadcrumbTrail[this.breadcrumbTrail.length - 1], true, false, false);
      },
      error: (err) => {
        this.snackBar.open('Could not load the specified folder.', 'Close', { duration: 3000 });
        this.loading = false;
        this.router.navigate(['/my-folder']);
        this.loadFolder(undefined, false, false, false);
      }
    });
  }

  private navigateToFile(fileId: string, openViewer: boolean = false): void {
    this.loading = true;

    // Fetch file info, ancestors, and position in parallel
    forkJoin({
      documentInfo: this.documentApi.getDocumentInfo(fileId),
      ancestors: this.documentApi.getDocumentAncestors(fileId),
      position: this.documentApi.getDocumentPosition(fileId, this.sortBy, this.sortOrder)
    }).subscribe({
      next: ({ documentInfo, ancestors, position }) => {
        // Build breadcrumb trail from ancestors (this is the path to the file's parent folder)
        this.breadcrumbTrail = ancestors.map(ancestor => ({
          id: ancestor.id,
          name: ancestor.name,
          type: DocumentType.FOLDER,
          selected: false,
          icon: this.fileIconService.getFileIcon(ancestor.name, 'FOLDER')
        } as FileItem));

        // Calculate target page (0-indexed)
        const targetPage = Math.floor(position.position / this.pageSize);
        this.pageIndex = targetPage;

        // Set current folder to the file's parent
        if (position.parentId) {
          const parentFolder = this.breadcrumbTrail[this.breadcrumbTrail.length - 1];
          this.currentFolder = parentFolder;
        } else {
          this.currentFolder = undefined;
        }

        // Update breadcrumbs
        this.updateBreadcrumbs();

        // Set total items
        this.totalItems = position.totalItems;

        // Load the correct page and focus on the file
        this.loadItemsAndFocusFile(fileId, openViewer, documentInfo);

        // Update URL to show the parent folder (clear targetFileId, keep folderId for back navigation)
        // Use replaceUrl to replace the targetFileId entry in history, so back button returns to origin
        this.isNavigatingFromUrl = true;
        this.router.navigate([], {
          relativeTo: this.route,
          queryParams: { folderId: this.currentFolder?.id || null, targetFileId: null, openViewer: null },
          queryParamsHandling: 'merge',
          replaceUrl: true
        });
      },
      error: (err) => {
        this.snackBar.open('Could not navigate to the file.', 'Close', { duration: 3000 });
        this.loading = false;
        this.router.navigate(['/my-folder']);
        this.loadFolder(undefined, false, false, false);
      }
    });
  }

  private loadItemsAndFocusFile(targetFileId: string, openViewer: boolean = false, documentInfo?: any): void {
    this.documentApi.listFolder(
      this.currentFolder?.id,
      this.pageIndex + 1,
      this.pageSize,
      this.currentFilters,
      this.sortBy,
      this.sortOrder
    ).subscribe({
      next: (response: ElementInfo[]) => {
        this.populateFolderContents(response);

        // Find and focus the target file
        const targetIndex = this.items.findIndex(item => item.id === targetFileId);
        if (targetIndex !== -1) {
          // Select the file
          this.items[targetIndex].selected = true;

          // Focus the file item (scroll into view)
          this.focusFileItem(targetFileId);

          // Open file viewer or metadata panel after a short delay
          setTimeout(() => {
            if (openViewer) {
              // Open file viewer dialog
              const item = this.items[targetIndex];
              this.openFileViewer(item);
            } else {
              // Open metadata panel
              this.openMetadataPanel(targetFileId);
            }
          }, 300);
        }
      },
      error: (error) => {
          console.log(error);
        this.snackBar.open('Failed to load folder contents', 'Close', { duration: 3000 });
        this.loading = false;
      }
    });
  }

  private focusFileItem(fileId: string): void {
    // Use setTimeout to ensure DOM is rendered
    setTimeout(() => {
      const element = document.querySelector(`[data-file-id="${fileId}"]`) as HTMLElement;
      const scrollContainer = document.querySelector('.file-explorer-content') as HTMLElement;

      if (element && scrollContainer) {
        // Calculate scroll position to center the element within the container
        const containerRect = scrollContainer.getBoundingClientRect();
        const elementRect = element.getBoundingClientRect();
        const scrollTop = scrollContainer.scrollTop + (elementRect.top - containerRect.top) - (containerRect.height / 2) + (elementRect.height / 2);

        // Scroll only the file-explorer-content container, not the entire page
        scrollContainer.scrollTo({
          top: Math.max(0, scrollTop),
          behavior: 'smooth'
        });

        element.classList.add('highlighted');

        // Remove highlight after animation
        setTimeout(() => {
          element.classList.remove('highlighted');
        }, 2000);
      }
    }, 100);
  }

  loadFolder(folder?: FileItem, fromBreadcrumb: boolean = false, appendBreadCrumb: boolean = false, updateHistory: boolean = true) {
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

    // Update URL for browser history (enables back/forward navigation)
    if (updateHistory) {
      this.isNavigatingFromUrl = true;
      this.updateUrlWithFolder(folder?.id);
    }

    this.documentApi.listFolderAndCount(this.currentFolder?.id, 1, this.pageSize, this.currentFilters, this.sortBy, this.sortOrder).subscribe({
      next: (listAndCount: ListFolderAndCountResponse) => {
        this.totalItems = listAndCount.count;
        this.pageIndex = 0;
        this.populateFolderContents(listAndCount.listFolder);
      },
      error: (error) => {
          console.log(error);
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
          console.log(error);
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
    console.log('navigateBack() called, metadataPanelOpen:', this.metadataPanelOpen);
    // If metadata panel is open, close it first
    if (this.metadataPanelOpen) {
      this.closeMetadataPanel();
      return;
    }
    // Use browser history to go back to the previous location
    // This correctly handles navigation from search results
    this.location.back();
  }

  onBreadcrumbNavigate(item: ElementInfo) {
    // Navigate to folder from breadcrumb click
    if (item.id === '0') {
      // Root folder
      this.navigateToHome();
    } else {
      // Find the index in breadcrumb trail and navigate
      const index = this.breadcrumbTrail.findIndex(b => b.id === item.id);
      if (index >= 0) {
        this.breadcrumbTrail = this.breadcrumbTrail.slice(0, index + 1);
        this.loadFolder(this.breadcrumbTrail[index], true, false, true);
      }
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
        contentType: item.contentType || '',
        fileSize: item.size
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
          next: (response) => {
            this.snackBar.open('Folder created successfully', 'Close', { duration: 3000 });
            this.navigateToNewFolder(response.id);
          },
          error: () => {
            this.snackBar.open('Failed to create folder', 'Close', { duration: 3000 });
          }
        });
      }
    });
  }

  private navigateToNewFolder(folderId: string): void {
    this.loading = true;

    // Get the position of the new folder based on current sorting
    this.documentApi.getDocumentPosition(folderId, this.sortBy, this.sortOrder).subscribe({
      next: (position) => {
        // Calculate target page (0-indexed)
        const targetPage = Math.floor(position.position / this.pageSize);
        this.pageIndex = targetPage;

        // Load items on the target page and focus the new folder
        this.loadItemsAndFocusFolder(folderId);
      },
      error: () => {
        // Fallback: just reload the current folder
        this.loadFolder(this.currentFolder);
      }
    });
  }

  private loadItemsAndFocusFolder(folderId: string): void {
    this.documentApi.listFolder(
      this.currentFolder?.id,
      this.pageIndex + 1,
      this.pageSize,
      this.currentFilters,
      this.sortBy,
      this.sortOrder
    ).subscribe({
      next: (response: ElementInfo[]) => {
        this.populateFolderContents(response);

        // Find and focus the target folder
        const targetIndex = this.items.findIndex(item => item.id === folderId);
        if (targetIndex !== -1) {
          // Select the folder
          this.items[targetIndex].selected = true;

          // Focus the folder item (scroll into view)
          this.focusFileItem(folderId);
        }
      },
      error: (error) => {
          console.log(error);
        this.snackBar.open('Failed to load folder contents', 'Close', { duration: 3000 });
        this.loading = false;
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
        title: 'dialogs.folderTree.moveItem',
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
        title: 'dialogs.folderTree.copyItem',
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
      const isSingle = selectedItems.length === 1;
      const dialogRef = this.dialog.open(FolderTreeDialogComponent, {
        width: '700px',
        data: {
          title: isSingle ? 'dialogs.folderTree.moveItem' : 'dialogs.folderTree.moveItems',
          titleParams: isSingle ? undefined : { count: selectedItems.length },
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
      const isSingle = selectedItems.length === 1;
      const dialogRef = this.dialog.open(FolderTreeDialogComponent, {
        width: '700px',
        data: {
          title: isSingle ? 'dialogs.folderTree.copyItem' : 'dialogs.folderTree.copyItems',
          titleParams: isSingle ? undefined : { count: selectedItems.length },
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

  onDragDropMove(event: DropEvent): void {
    const { items, targetFolderId } = event;

    if (items.length === 0) {
      return;
    }

    // Prevent moving to same location
    const currentFolderId = this.currentFolder?.id ?? null;
    if (targetFolderId === currentFolderId) {
      this.snackBar.open('Items are already in this folder', 'Close', { duration: 3000 });
      return;
    }

    // Use existing bulk move logic
    this.performBulkMoveInternal(items, targetFolderId);
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
      next: (httpResponse) => {
        this.snackBar.dismiss();
        const response = httpResponse.body || [];
        const status = httpResponse.status;

        // HTTP 207 Multi-Status: Partial success
        if (status === 207) {
          const result = this.documentApi.parseUploadResult(response);

          // Find the last successful upload for navigation after dialog closes
          const successfulUploads = response.filter(r => !r.errorType && r.id);
          const lastSuccessId = successfulUploads.length > 0 ? successfulUploads[successfulUploads.length - 1].id! : null;

          // Show dialog and navigate/reload after it closes
          this.showPartialUploadResultDialog(result).afterClosed().subscribe(() => {
            if (lastSuccessId) {
              this.navigateToUploadedFile(lastSuccessId, false);
            } else {
              this.loadFolder(this.currentFolder);
            }
          });
          return;
        }

        // HTTP 200: All successful
        const successMessage = isSingleFile
          ? `${singleFileName} uploaded successfully`
          : `${fileArray.length} files uploaded successfully`;
        this.snackBar.open(successMessage, 'Close', { duration: 3000 });

        // Navigate to the uploaded file
        // For single file: focus and open metadata panel
        // For multiple files: just navigate to the page of the last uploaded file
        if (response.length > 0 && response[response.length - 1].id) {
          this.navigateToUploadedFile(response[response.length - 1].id!, isSingleFile);
        }
      },
      error: (error) => {
        this.snackBar.dismiss();

        // Check for 409 Conflict (duplicate file name)
        if (error.status === 409 && isSingleFile && singleFileName) {
          this.handleDuplicateFileUpload(fileArray[0], singleFileName);
          return;
        }

        // HTTP 404, 409, 403, 500 with body: All uploads failed
        if (error.error && Array.isArray(error.error)) {
          const result = this.documentApi.parseUploadResult(error.error);
          this.showPartialUploadResultDialog(result).afterClosed().subscribe(() => {
            this.loadFolder(this.currentFolder);
          });
          return;
        }

        const errorMessage = isSingleFile
          ? `Failed to upload ${singleFileName}`
          : `Failed to upload ${fileArray.length} files`;
        this.snackBar.open(errorMessage, 'Close', { duration: 5000 });
      }
    });
  }

  private showPartialUploadResultDialog(result: import('../../models/document.models').PartialUploadResult) {
    const dialogData: PartialUploadResultDialogData = {
      result
    };

    return this.dialog.open(PartialUploadResultDialogComponent, {
      width: '550px',
      maxWidth: '95vw',
      data: dialogData,
      autoFocus: false
    });
  }

  /**
   * Navigate to the page where the uploaded file is located.
   * For single file upload: focus on the file and open metadata panel.
   * For multiple files upload: just navigate to the page without focus/panel.
   */
  private navigateToUploadedFile(fileId: string, focusAndOpenPanel: boolean): void {
    this.loading = true;

    // Get the document position to find the correct page
    this.documentApi.getDocumentPosition(fileId, this.sortBy, this.sortOrder).subscribe({
      next: (position) => {
        // Calculate target page (0-indexed)
        const targetPage = Math.floor(position.position / this.pageSize);
        this.pageIndex = targetPage;
        this.totalItems = position.totalItems;

        // Load the page and optionally focus the file
        this.loadItemsAfterUpload(fileId, focusAndOpenPanel);
      },
      error: () => {
        // Fallback: just reload the current folder
        this.loadFolder(this.currentFolder);
      }
    });
  }

  /**
   * Load items after upload and optionally focus on the uploaded file.
   */
  private loadItemsAfterUpload(targetFileId: string, focusAndOpenPanel: boolean): void {
    this.documentApi.listFolder(
      this.currentFolder?.id,
      this.pageIndex + 1,
      this.pageSize,
      this.currentFilters,
      this.sortBy,
      this.sortOrder
    ).subscribe({
      next: (response: ElementInfo[]) => {
        this.populateFolderContents(response);

        if (focusAndOpenPanel) {
          // Find and focus the target file (single file upload)
          const targetIndex = this.items.findIndex(item => item.id === targetFileId);
          if (targetIndex !== -1) {
            // Select the file
            this.items[targetIndex].selected = true;

            // Focus the file item (scroll into view)
            this.focusFileItem(targetFileId);

            // Open metadata panel after a short delay
            setTimeout(() => {
              this.openMetadataPanel(targetFileId);
            }, 300);
          }
        }
        // For multiple files, just load the page without focus/panel
      },
      error: (error) => {
        console.log(error);
        this.snackBar.open('Failed to load folder contents', 'Close', { duration: 3000 });
        this.loading = false;
      }
    });
  }

  private handleDuplicateFileUpload(file: File, fileName: string) {
    // Find the existing document with the same name in current folder
    const existingItem = this.items.find(item => item.name === fileName && item.type === 'FILE');

    if (!existingItem) {
      // Conflict is with a deleted/inactive file - retry upload with allowDuplicateFileNames=true
      this.snackBar.open(`Uploading ${fileName}...`, undefined, { duration: undefined });
      this.documentApi.uploadMultipleDocuments(
        [file],
        this.currentFolder?.id,
        true // allowDuplicateFileNames = true to bypass conflict with deleted file
      ).subscribe({
        next: (httpResponse) => {
          this.snackBar.dismiss();
          const response = httpResponse.body || [];
          this.snackBar.open(`${fileName} uploaded successfully`, 'Close', { duration: 3000 });
          // Navigate to the uploaded file with focus and metadata panel
          if (response.length > 0 && response[response.length - 1].id) {
            this.navigateToUploadedFile(response[response.length - 1].id!, true);
          }
        },
        error: () => {
          this.snackBar.dismiss();
          this.snackBar.open(`Failed to upload ${fileName}`, 'Close', { duration: 5000 });
        }
      });
      return;
    }

    // Open confirmation dialog
    const dialogData: ConfirmReplaceDialogData = {
      fileName: fileName,
      existingDocumentId: existingItem.id
    };

    const dialogRef = this.dialog.open(ConfirmReplaceDialogComponent, {
      width: '450px',
      data: dialogData,
      disableClose: true
    });

    dialogRef.afterClosed().subscribe((result: ConfirmReplaceDialogResult | null) => {
      if (result?.confirmed) {
        // User confirmed - replace the document content
        this.snackBar.open(`Replacing ${fileName}...`, undefined, { duration: undefined });

        this.documentApi.replaceDocumentContent(result.existingDocumentId, file).subscribe({
          next: () => {
            this.snackBar.dismiss();
            this.snackBar.open(`${fileName} replaced successfully`, 'Close', { duration: 3000 });
            // Navigate to the replaced file with focus and metadata panel
            this.navigateToUploadedFile(result.existingDocumentId, true);
          },
          error: (err) => {
            this.snackBar.dismiss();
            this.snackBar.open(`Failed to replace ${fileName}`, 'Close', { duration: 5000 });
          }
        });
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