import { Component, OnInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { SearchService } from '../../services/search.service';
import {DocumentSearchInfo, DocumentType, FileItem} from '../../models/document.models';
import { CommonModule } from '@angular/common';
import { FileListComponent } from '../file-list/file-list.component';
import { FileGridComponent } from '../file-grid/file-grid.component';
import { ToolbarComponent } from '../toolbar/toolbar.component';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { FileIconService } from '../../services/file-icon.service';

@Component({
  selector: 'app-search-results',
  standalone: true,
  imports: [CommonModule, FileListComponent, FileGridComponent, ToolbarComponent, MatProgressSpinnerModule],
  templateUrl: './search-results.component.html',
  styleUrls: ['./search-results.component.css']
})
export class SearchResultsComponent implements OnInit {
  loading = false;
  items: FileItem[] = [];
  viewMode: 'grid' | 'list' = 'grid';
  totalItems = 0;
  searchQuery = '';

  constructor(
    private route: ActivatedRoute,
    private searchService: SearchService,
    private fileIconService: FileIconService
  ) { }

  ngOnInit(): void {
    this.route.queryParams.subscribe(params => {
      this.searchQuery = params['q'];
      if (this.searchQuery) {
        this.performSearch();
      }
    });
  }

  performSearch(): void {
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
    const fileType = doc.extension == null ? DocumentType.FOLDER : DocumentType.FILE;
    return {
      id: doc.id,
      name: doc.name,
      type: fileType,
      size: doc.size,
      //createdAt: new Date(doc.createdAt),
      modifiedDate: new Date(doc.updatedAt),
      icon: this.fileIconService.getFileIcon(doc.name, fileType),
      selected: false
    };
  }

  onViewModeChange(mode: 'grid' | 'list'): void {
    this.viewMode = mode;
  }
}
