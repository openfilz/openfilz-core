import {Component, Inject, OnInit} from '@angular/core';
import {CommonModule} from '@angular/common';
import {MAT_DIALOG_DATA, MatDialogModule, MatDialogRef} from '@angular/material/dialog';
import {MatButtonModule} from '@angular/material/button';
import {MatIconModule} from '@angular/material/icon';
import {MatProgressSpinnerModule} from '@angular/material/progress-spinner';
import {MatPaginatorModule, PageEvent} from '@angular/material/paginator';

import {DocumentApiService} from '../../services/document-api.service';
import {ElementInfo} from '../../models/document.models';

export interface FolderTreeDialogData {
  title: string;
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
    MatPaginatorModule
  ]
})
export class FolderTreeDialogComponent implements OnInit {
  folders: FolderItem[] = [];
  breadcrumbs: BreadcrumbItem[] = [{name: 'Root'}];
  currentFolderId?: string;
  loading = true;

  totalItems = 0;
  pageSize = 10;
  pageIndex = 0;
  pageSizeOptions: number[] = [10, 20, 50, 70, 100];

  constructor(
    private dialogRef: MatDialogRef<FolderTreeDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: FolderTreeDialogData,
    private documentApi: DocumentApiService
  ) {}

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
    return this.data.actionType === 'move' ? 'Move to here' : 'Copy to here';
  }

  onAction() {
    this.dialogRef.close(this.currentFolderId || null);
  }

  onCancel() {
    this.dialogRef.close();
  }
}
