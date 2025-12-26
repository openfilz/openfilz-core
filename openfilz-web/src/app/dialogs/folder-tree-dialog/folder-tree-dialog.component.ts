import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { TranslatePipe } from '@ngx-translate/core';

import { DocumentApiService } from '../../services/document-api.service';

export interface FolderTreeDialogData {
  title: string;
  titleParams?: any;
  actionType: 'move' | 'copy';
  currentFolderId?: string;
  excludeIds?: string[];
}

interface FolderItem {
  id: string;
  name: string;
}

interface BreadcrumbItem {
  id?: string;
  name: string;
}

@Component({
  selector: 'app-folder-tree-dialog',
  standalone: true,
  templateUrl: './folder-tree-dialog.component.html',
  styleUrls: ['./folder-tree-dialog.component.css'],
  imports: [
    CommonModule,
    MatDialogModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatPaginatorModule,
    TranslatePipe
  ]
})
export class FolderTreeDialogComponent implements OnInit {
  folders: FolderItem[] = [];
  breadcrumbs: BreadcrumbItem[] = [{ name: 'dialogs.folderTree.root' }];
  currentFolderId?: string;
  loading = true;

  totalItems = 0;
  pageSize = 10;
  pageIndex = 0;
  pageSizeOptions: number[] = [10, 20, 50, 70, 100];

  private dialogRef = inject(MatDialogRef<FolderTreeDialogComponent>);
  readonly data = inject<FolderTreeDialogData>(MAT_DIALOG_DATA);
  private documentApi = inject(DocumentApiService);

  get direction(): 'ltr' | 'rtl' {
    return (document.documentElement.dir as 'ltr' | 'rtl') || 'ltr';
  }

  constructor() { }

  ngOnInit() {
    const storedPageSize = localStorage.getItem('folderDialogItemsPerPage');
    if (storedPageSize) {
      this.pageSize = parseInt(storedPageSize, 10);
    }
    this.loadFolders();
  }

  loadFolders(folderId?: string, resetPagination: boolean = true) {
    this.loading = true;
    this.currentFolderId = folderId;

    if (resetPagination) {
      this.pageIndex = 0;
    }

    this.documentApi.listFolderAndCount(folderId, this.pageIndex + 1, this.pageSize).subscribe({
      next: (response) => {
        const allItems = response.listFolder;
        this.totalItems = response.count;

        this.folders = allItems
          .filter(item => item.type === 'FOLDER')
          .filter(item => !this.data.excludeIds?.includes(item.id))
          .map(item => ({
            id: item.id,
            name: item.name
          }));
        this.loading = false;
      },
      error: () => {
        this.loading = false;
      }
    });
  }

  onFolderDoubleClick(folder: FolderItem) {
    this.breadcrumbs.push({
      id: folder.id,
      name: folder.name
    });
    this.loadFolders(folder.id, true);
  }

  onBreadcrumbClick(index: number) {
    this.breadcrumbs = this.breadcrumbs.slice(0, index + 1);
    const targetBreadcrumb = this.breadcrumbs[index];
    this.loadFolders(targetBreadcrumb.id, true);
  }

  onPageChange(event: PageEvent) {
    this.pageIndex = event.pageIndex;
    this.pageSize = event.pageSize;
    if (event.pageSize) {
      localStorage.setItem('folderDialogItemsPerPage', String(event.pageSize));
    }
    this.loadFolders(this.currentFolderId, false);
  }

  getActionButtonText(): string {
    return this.data.actionType === 'move' ? 'dialogs.folderTree.moveToHere' : 'dialogs.folderTree.copyToHere';
  }

  getActionIcon(): string {
    return this.data.actionType === 'move' ? 'drive_file_move' : 'content_copy';
  }

  getDialogIcon(): string {
    return this.data.actionType === 'move' ? 'drive_file_move' : 'content_copy';
  }

  getDialogSubtitle(): string {
    return this.data.actionType === 'move'
      ? 'dialogs.folderTree.moveSubtitle'
      : 'dialogs.folderTree.copySubtitle';
  }

  onAction() {
    this.dialogRef.close(this.currentFolderId || null);
  }

  onCancel() {
    this.dialogRef.close();
  }
}
