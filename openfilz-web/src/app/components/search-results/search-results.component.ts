import {Component, OnInit} from '@angular/core';
import {ActivatedRoute, Router} from '@angular/router';
import {CommonModule} from '@angular/common';
import {MatProgressSpinnerModule} from '@angular/material/progress-spinner';
import {MatDialog, MatDialogModule} from '@angular/material/dialog';
import {MatSnackBar, MatSnackBarModule} from '@angular/material/snack-bar';

import {SearchService} from '../../services/search.service';
import {DocumentApiService} from '../../services/document-api.service';
import {FileIconService} from '../../services/file-icon.service';

import {FileListComponent} from '../file-list/file-list.component';
import {FileGridComponent} from '../file-grid/file-grid.component';
import {ToolbarComponent} from '../toolbar/toolbar.component';
import {FileOperationsComponent} from '../base/file-operations.component';
import {DocumentSearchInfo, DocumentType, FileItem} from '../../models/document.models';

import { UserPreferencesService } from '../../services/user-preferences.service';

@Component({
  selector: 'app-search-results',
  standalone: true,
  imports: [
    CommonModule,
    FileListComponent,
    FileGridComponent,
    ToolbarComponent,
    MatProgressSpinnerModule,
    MatDialogModule,
    MatSnackBarModule
  ],
  templateUrl: './search-results.component.html',
  styleUrls: ['./search-results.component.css']
})
export class SearchResultsComponent extends FileOperationsComponent implements OnInit {
  searchQuery = '';

  constructor(
    private route: ActivatedRoute,
    private searchService: SearchService,
    private fileIconService: FileIconService,
    router: Router,
    documentApi: DocumentApiService,
    dialog: MatDialog,
    snackBar: MatSnackBar,
    userPreferencesService: UserPreferencesService
  ) {
    super(router, documentApi, dialog, snackBar, userPreferencesService);
  }

  override ngOnInit(): void {
    this.route.queryParams.subscribe(params => {
      this.searchQuery = params['q'];
      if (this.searchQuery) {
        this.reloadData();
      }
    });

    this.searchService.filters$.subscribe(() => {
      if (this.searchQuery) {
        this.reloadData();
      }
    });

    this.searchService.sort$.subscribe(sort => {
      this.sortBy = sort.sortBy;
      this.sortOrder = sort.sortOrder;
      if (this.searchQuery) {
        this.reloadData();
      }
    });
  }

  override onSortChange(event: { sortBy: string, sortOrder: 'ASC' | 'DESC' }): void {
    this.searchService.updateSort(event.sortBy, event.sortOrder);
  }

  override loadItems() {
      this.reloadData();
  }

  override reloadData(): void {
    this.loading = true;
    this.searchService.searchDocuments(this.searchQuery).subscribe({
      next: (result) => {
        this.totalItems = result.totalHits;
        this.items = result.documents.map(doc => this.transformToFileItem(doc));
        this.loading = false;
      },
      error: (err) => {
        console.error('Search failed', err);
        this.loading = false;
      }
    });
  }

  private transformToFileItem(doc: DocumentSearchInfo): FileItem {
    const fileType = doc.extension ? DocumentType.FILE : DocumentType.FOLDER;
    return {
      id: doc.id,
      name: doc.name,
      type: fileType,
      size: doc.size,
      icon: this.fileIconService.getFileIcon(doc.name, fileType),
      selected: false
    };
  }
}
