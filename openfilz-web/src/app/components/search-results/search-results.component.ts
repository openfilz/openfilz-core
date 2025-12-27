import { Component, OnInit, inject } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';

import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatIconModule } from '@angular/material/icon';

import { SearchService } from '../../services/search.service';
import { DocumentApiService } from '../../services/document-api.service';
import { FileIconService } from '../../services/file-icon.service';

import { FileListComponent } from '../file-list/file-list.component';
import { FileGridComponent } from '../file-grid/file-grid.component';
import { ToolbarComponent } from '../toolbar/toolbar.component';
import { MetadataPanelComponent } from '../metadata-panel/metadata-panel.component';
import { FileOperationsComponent } from '../base/file-operations.component';
import { DocumentSearchInfo, DocumentType, FileItem } from '../../models/document.models';

import { UserPreferencesService } from '../../services/user-preferences.service';

@Component({
  selector: 'app-search-results',
  standalone: true,
  imports: [
    FileListComponent,
    FileGridComponent,
    ToolbarComponent,
    MetadataPanelComponent,
    MatProgressSpinnerModule,
    MatDialogModule,
    MatSnackBarModule,
    MatIconModule
],
  templateUrl: './search-results.component.html',
  styleUrls: ['./search-results.component.css']
})
export class SearchResultsComponent extends FileOperationsComponent implements OnInit {
  searchQuery = '';
  metadataPanelOpen: boolean = false;
  selectedDocumentForMetadata?: string;

  private route = inject(ActivatedRoute);
  private searchService = inject(SearchService);
  private fileIconService = inject(FileIconService);

  constructor() {
    super();
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

  openMetadataPanel(documentId: string) {
    this.selectedDocumentForMetadata = documentId;
    this.metadataPanelOpen = true;
  }

  closeMetadataPanel() {
    this.metadataPanelOpen = false;
    this.selectedDocumentForMetadata = undefined;
  }

  onMetadataSaved() {
    this.reloadData();
  }

  onViewProperties(item: FileItem) {
    this.openMetadataPanel(item.id);
  }

  onToggleFavorite(item: FileItem) {
    const action = item.favorite ? 'remove from' : 'add to';
    this.documentApi.toggleFavorite(item.id).subscribe({
      next: () => {
        item.favorite = !item.favorite;
        this.snackBar.open(`Successfully ${action === 'add to' ? 'added to' : 'removed from'} favorites`, 'Close', { duration: 3000 });
      },
      error: () => {
        this.snackBar.open(`Failed to ${action} favorites`, 'Close', { duration: 3000 });
      }
    });
  }
}
