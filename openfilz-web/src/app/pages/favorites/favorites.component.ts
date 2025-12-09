import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { DocumentApiService } from '../../services/document-api.service';
import { FileGridComponent } from '../../components/file-grid/file-grid.component';
import { FileListComponent } from '../../components/file-list/file-list.component';
import { ToolbarComponent } from '../../components/toolbar/toolbar.component';
import { ElementInfo, FileItem, ListFolderAndCountResponse, SearchFilters } from '../../models/document.models';
import { FileIconService } from '../../services/file-icon.service';
import { BreadcrumbService } from '../../services/breadcrumb.service';
import { AppConfig } from '../../config/app.config';
import { FileOperationsComponent } from "../../components/base/file-operations.component";
import { ActivatedRoute, Router } from "@angular/router";
import { SearchService } from "../../services/search.service";
import { MatDialog } from "@angular/material/dialog";

import { UserPreferencesService } from '../../services/user-preferences.service';

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
export class FavoritesComponent extends FileOperationsComponent implements OnInit {

  // Click delay handling
  private clickTimeout: any = null;
  private readonly CLICK_DELAY = 250; // milliseconds
  currentFilters?: SearchFilters;

  private route = inject(ActivatedRoute);
  private searchService = inject(SearchService);
  private fileIconService = inject(FileIconService);

  constructor() {
    super();
  }

  override ngOnInit() {
    this.searchService.filters$.subscribe(filters => {
      this.currentFilters = filters;
      this.loadFavorites();
    });
  }

  override loadItems() {
    this.loadFavorites();
  }

  override reloadData() {
    this.loadFavorites();
  }



  loadFavorites() {
    this.loading = true;
    this.documentApi.listFavoritesAndCount(this.pageIndex + 1, this.pageSize, this.currentFilters, this.sortBy, this.sortOrder).subscribe({
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

  private populateFolderContents(response: ElementInfo[]) {
    this.items = response.map(item => ({
      ...item,
      selected: false,
      icon: this.fileIconService.getFileIcon(item.name, item.type)
    }));
    this.loading = false;
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

}
