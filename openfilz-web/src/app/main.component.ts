import {Component, OnInit} from '@angular/core';
import {CommonModule} from '@angular/common';
import {MatDialog, MatDialogModule} from '@angular/material/dialog';
import {MatSnackBar, MatSnackBarModule, MatSnackBarRef} from '@angular/material/snack-bar';
import {MatProgressSpinnerModule} from '@angular/material/progress-spinner';
import {MatIcon} from "@angular/material/icon";
import {MatPaginatorModule, PageEvent} from "@angular/material/paginator";

import {SidebarComponent} from './components/sidebar/sidebar.component';
import {HeaderComponent} from './components/header/header.component';
import {FileGridComponent} from './components/file-grid/file-grid.component';
import {FileListComponent} from './components/file-list/file-list.component';
import {CreateFolderDialogComponent} from './dialogs/create-folder-dialog/create-folder-dialog.component';
import {RenameDialogComponent, RenameDialogData} from './dialogs/rename-dialog/rename-dialog.component';

import {DocumentApiService} from './services/document-api.service';
import {FileIconService} from './services/file-icon.service';

import {
  CreateFolderRequest,
  DeleteRequest,
  DocumentType,
  ElementInfo,
  FileItem, ListFolderAndCountResponse,
  RenameRequest,
  Root
} from './models/document.models';

import {DragDropDirective} from "./directives/drag-drop.directive";
import {DownloadSnackbarComponent} from "./components/download-snackbar/download-snackbar.component";
import {DownloadProgressComponent} from "./components/download-progress/download-progress.component";

@Component({
  selector: 'app-main',
  standalone: true,
  templateUrl: './main.component.html',
  styleUrls: ['./main.component.css'],
  imports: [
    CommonModule,
    MatDialogModule,
    MatSnackBarModule,
    MatProgressSpinnerModule,
    SidebarComponent,
    HeaderComponent,
    FileGridComponent,
    FileListComponent,
    MatIcon,
    DragDropDirective,
    MatPaginatorModule,
    DownloadProgressComponent
  ],
})
export class MainComponent implements OnInit {
  static itemsPerPage = 'itemsPerPage';
  viewMode: 'grid' | 'list' = 'grid';
  loading = false;
  isDownloading = false;
  showUploadZone = false;
  fileOver: boolean = false;

  private snackBarRef: MatSnackBarRef<DownloadSnackbarComponent> | null = null;

  items: FileItem[] = [];
  breadcrumbs: ElementInfo[] = [];
  currentFolder?: FileItem;
  currentSection = 'home';

  totalItems = 0;
  pageSize = 70;
  pageIndex = 0;

  constructor(
    private documentApi: DocumentApiService,
    private fileIconService: FileIconService,
    private dialog: MatDialog,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit() {

    const storedItemsPerPage = localStorage.getItem(MainComponent.itemsPerPage);
    if (storedItemsPerPage) {
      this.pageSize = parseInt(storedItemsPerPage, 10);
    }
    this.loadFolder();
  }

  get hasSelectedItems(): boolean {
    return this.items.some(item => item.selected);
  }

  get selectedItems(): FileItem[] {
    return this.items.filter(item => item.selected);
  }

  onSidebarNavigation(section: string) {
    this.currentSection = section;
    if (section === 'home') {
      this.loadFolder();
    } else {
      // Handle other sections (starred, recent, trash)
      this.items = [];
      this.breadcrumbs = [];
      this.showUploadZone = false;
    }
  }

  loadFolder(folder?: FileItem) {
    this.loading = true;
    this.currentFolder = folder;
    this.documentApi.listFolderAndCount(this.currentFolder?.id, 1, this.pageSize).subscribe({
      next: (listAndCount: ListFolderAndCountResponse) => {
        this.totalItems = listAndCount.count;
        this.pageIndex = 0;
        this.populateFolderContents(listAndCount.listFolder);
      },
      error: (error) => {
        //console.error('Failed to count items:', error);
        this.snackBar.open('Failed to load folder contents', 'Close', { duration: 3000 });
        this.loading = false;
      }
    });
  }

  loadItems() {
    this.loading = true;
    this.documentApi.listFolder(this.currentFolder?.id, this.pageIndex + 1, this.pageSize).subscribe({
      next: (response: ElementInfo[]) => {
        //console.log("loadItems " + response);
        this.populateFolderContents(response);
      },
      error: (error) => {
        //console.error('Failed to load folder:', error);
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
    this.updateBreadcrumbs(this.currentFolder);
  }

  onPageChange(event: PageEvent) {
    this.pageIndex = event.pageIndex;
    this.pageSize = event.pageSize;
    if(event.pageSize) {
      localStorage.setItem(MainComponent.itemsPerPage, String(event.pageSize));
    }
    this.loadItems();
  }

  updateBreadcrumbs(folder?: FileItem) {
    // In a real app, you'd track the full path
    if (folder) {
      const breadCrumbItem: ElementInfo = {
        id: folder.id,
        name: folder.name,
        type: DocumentType.FOLDER
      };
      const i = this.breadcrumbs.findIndex(value => value.id == breadCrumbItem.id);
      if(i > -1) {
        this.breadcrumbs = this.breadcrumbs.slice(0, i+1);
      } else {
        this.breadcrumbs = this.breadcrumbs.concat(breadCrumbItem);
      }
    } else {
      this.breadcrumbs = [];
    }
  }

  onViewModeChange(mode: 'grid' | 'list') {
    this.viewMode = mode;
  }

  onCreateFolder() {
    const dialogRef = this.dialog.open(CreateFolderDialogComponent, {
      width: '400px',
      data: {}
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
            //console.error('Failed to create folder:', error);
            this.snackBar.open('Failed to create folder', 'Close', { duration: 3000 });
          }
        });
      }
    });
  }

  onUploadFiles() {
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

  onFilesSelected(files: FileList) {
    this.handleFileUpload(files);
  }

  private handleFileUpload(files: FileList) {
    this.documentApi.uploadMultipleDocuments(Array.from(files), this.currentFolder?.id).subscribe({
      next: (item) => {
        //console.log("Successfully uploaded", item);
      },
      error: (error) => {
        //console.error(`Failed to upload files:`, error);
        this.snackBar.open(`Failed to upload files`, 'Close', { duration: 3000 });
      },
      complete: () => {
        this.snackBar.open(`Files uploaded successfully`, 'Close', { duration: 3000 });
        this.loadFolder(this.currentFolder);
      }
    });
  }

  onFilesDropped(files: FileList) {
    this.handleFileUpload(files);
  }

  onFileOverChange(isOver: boolean) {
    this.fileOver = isOver;
  }

  onItemClick(item: FileItem) {
    // Toggle selection
    item.selected = !item.selected;
  }

  onItemDoubleClick(item: FileItem) {
    if (item.type === 'FOLDER') {
      this.loadFolder(item);
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
            //console.error('Failed to rename item:', error);
            this.snackBar.open('Failed to rename item', 'Close', { duration: 3000 });
          }
        });
      }
    });
  }

  onDownloadItem(item: FileItem) {
    this.isDownloading = true;
    this.snackBarRef = this.snackBar.openFromComponent(DownloadSnackbarComponent);
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
        if (this.snackBarRef) {
          this.snackBarRef.dismiss();
        }
      },
      error: (error) => {
        //console.error('Failed to download file:', error);
        this.snackBar.open('Failed to download file', 'Close', { duration: 3000 });
        this.isDownloading = false;
        if (this.snackBarRef) {
          this.snackBarRef.dismiss();
        }
      }
    });
  }

  onMoveItem(item: FileItem) {
    // TODO: Implement move functionality with folder selection dialog
    this.snackBar.open('Move functionality coming soon', 'Close', { duration: 3000 });
  }

  onCopyItem(item: FileItem) {
    // TODO: Implement copy functionality with folder selection dialog
    this.snackBar.open('Copy functionality coming soon', 'Close', { duration: 3000 });
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
      console.log('isDownloading:', this.isDownloading);
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
          console.log('isDownloading:', this.isDownloading);
        },
        error: (error) => {
          //console.error('Failed to download files:', error);
          this.snackBar.open('Failed to download files', 'Close', { duration: 3000 });
          this.isDownloading = false;
          console.log('isDownloading:', this.isDownloading);
        }
      });
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
          //console.error('Failed to delete items:', error);
          this.snackBar.open('Failed to delete items', 'Close', { duration: 3000 });
        }
      });
    });
  }

  onNavigate(item: ElementInfo) {
    if (item === Root.INSTANCE) {
      this.loadFolder();
    } else if(item.id != null) {
      this.loadFolder({id: item.id, name: item.name, type: DocumentType.FOLDER});
    }
  }

  onSearch(query: string) {
    if (query.trim()) {
      // TODO: Implement search functionality
      this.snackBar.open('Search functionality coming soon', 'Close', { duration: 3000 });
    }
  }
}