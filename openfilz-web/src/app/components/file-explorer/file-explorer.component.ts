import { Component, ElementRef, OnInit, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { take } from 'rxjs';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatIcon } from "@angular/material/icon";

import { FileGridComponent } from '../file-grid/file-grid.component';
import { FileListComponent } from '../file-list/file-list.component';
import { ToolbarComponent } from '../toolbar/toolbar.component';
import { CreateFolderDialogComponent } from '../../dialogs/create-folder-dialog/create-folder-dialog.component';
import { FileOperationsComponent } from '../base/file-operations.component';

import { DocumentApiService } from '../../services/document-api.service';
import { FileIconService } from '../../services/file-icon.service';
import { BreadcrumbService } from '../../services/breadcrumb.service';

import {
    CreateFolderRequest,
    ElementInfo,
    FileItem,
    ListFolderAndCountResponse,
    DocumentType
} from '../../models/document.models';

import { DragDropDirective } from "../../directives/drag-drop.directive";
import { DownloadProgressComponent } from "../download-progress/download-progress.component";

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
export class FileExplorerComponent extends FileOperationsComponent implements OnInit {
  static itemsPerPage = 'itemsPerPage';
  showUploadZone = false;
  fileOver: boolean = false;

  breadcrumbTrail: FileItem[] = []; // Track full path
  currentFolder?: FileItem;

  pageSize = 70;
  pageIndex = 0;

  @ViewChild('fileInput') fileInput!: ElementRef;

  constructor(
    private fileIconService: FileIconService,
    private breadcrumbService: BreadcrumbService,
    private route: ActivatedRoute,
    private router: Router,
    documentApi: DocumentApiService,
    dialog: MatDialog,
    snackBar: MatSnackBar
  ) {
    super(documentApi, dialog, snackBar);
  }

  override ngOnInit() {
    super.ngOnInit();
    const storedItemsPerPage = localStorage.getItem(FileExplorerComponent.itemsPerPage);
    if (storedItemsPerPage) {
      this.pageSize = parseInt(storedItemsPerPage, 10);
    }

    this.route.queryParams.pipe(take(1)).subscribe(params => {
      const folderId = params['folderId'];
      if (folderId) {
        this.loadFolderById(folderId);
      } else {
        this.loadFolder();
      }
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
    this.pageIndex = 0;
    this.loadItems();
  }

  onFileOverChange(isOver: boolean) {
    this.fileOver = isOver;
  }

  onItemDoubleClick(item: FileItem) {
    if (item.type === 'FOLDER') {
      this.loadFolder(item, false, true);
    } else {
      this.onDownloadItem(item);
    }
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
      next: (item) => {},
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