import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Component, EventEmitter, Input, OnDestroy, OnInit, Output } from "@angular/core";
import { Router } from "@angular/router";
import { Subject, Subscription } from "rxjs";
import { SearchService } from "../../services/search.service";
import { debounceTime, distinctUntilChanged, switchMap } from "rxjs/operators";
import { Suggestion, SearchFilters } from "../../models/document.models";
import { DocumentApiService } from "../../services/document-api.service";
import { SearchFiltersComponent } from "../search-filters/search-filters.component";

@Component({
  selector: 'app-header',
  standalone: true,
  imports: [CommonModule, FormsModule, SearchFiltersComponent],
  templateUrl: './header.component.html',
  styleUrls: ['./header.component.css']
})
export class HeaderComponent implements OnInit, OnDestroy {
  searchQuery: string = '';
  suggestions: Suggestion[] = [];
  showFilters = false;
  @Input() hasSelection: boolean = false;
  currentFilters?: SearchFilters;

  private searchSubject = new Subject<string>();
  private searchSubscription!: Subscription;

  constructor(
    private searchService: SearchService,
    private apiService: DocumentApiService,
    private router: Router
  ) { }

  ngOnInit(): void {
    this.searchSubscription = this.searchSubject.pipe(
      debounceTime(300),
      distinctUntilChanged(),
      switchMap(query => this.searchService.getSuggestions(query))
    ).subscribe(suggestions => {
      this.suggestions = suggestions;
    });
  }

  onSearchInput(): void {
    this.searchSubject.next(this.searchQuery);
  }

  onSearch(): void {
    if (this.searchQuery.trim()) {
      this.router.navigate(['/search'], { queryParams: { q: this.searchQuery } });
      this.suggestions = [];
    }
  }

  toggleFilters() {
    this.showFilters = !this.showFilters;
  }

  onFiltersChanged(filters: SearchFilters) {
    console.log('Filters changed:', filters);
    this.currentFilters = filters;
    this.searchService.updateFilters(filters);
  }

  selectSuggestion(docId: string): void {
    console.log(`Selected Suggestion: ${docId}`);
  }

  ngOnDestroy(): void {
    if (this.searchSubscription) {
      this.searchSubscription.unsubscribe();
    }
  }

  getIconForExtension(ext: string | undefined): string {
    if (ext === null || ext === undefined) {
      return 'fa-solid fa-folder'; // Folder icon
    }

    switch (ext.toLowerCase()) {
      case 'pdf':
        return 'fa-solid fa-file-pdf';
      case 'doc':
      case 'docx':
        return 'fa-solid fa-file-word';
      case 'xls':
      case 'xlsx':
        return 'fa-solid fa-file-excel';
      case 'ppt':
      case 'pptx':
        return 'fa-solid fa-file-powerpoint';
      case 'png':
      case 'jpg':
      case 'jpeg':
      case 'gif':
        return 'fa-solid fa-file-image';
      case 'zip':
      case 'rar':
        return 'fa-solid fa-file-zipper';
      case 'txt':
        return 'fa-solid fa-file-lines';
      default:
        return 'fa-solid fa-file'; // Generic file icon
    }
  }

  protected onDownload(suggestion: Suggestion, event: MouseEvent) {
    event.stopPropagation();

    console.log(`Downloading document with ID: ${suggestion.id}`);
    this.apiService.downloadDocument(suggestion.id).subscribe({
      next: (blob) => {
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        if (suggestion.ext == null) {
          a.download = suggestion.s + ".zip";
        } else if (suggestion.ext.length > 0) {
          a.download = suggestion.s + "." + suggestion.ext;
        } else {
          a.download = suggestion.s;
        }
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        window.URL.revokeObjectURL(url);
      },
      error: (error) => {
        console.debug(error);
      }
    });

    this.suggestions = [];
  }

  protected onOpen(suggestion: Suggestion, event: MouseEvent) {

    //console.log(`Opening document with ID: ${suggestion.id}`);
    if(suggestion.id != null && suggestion.ext == null) {
        this.router.navigate(['/my-folder'], { queryParams: { folderId: suggestion.id } });
    }
    this.suggestions = [];
  }
}