import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Component, ElementRef, HostListener, Input, OnDestroy, OnInit, inject } from "@angular/core";
import { Router } from "@angular/router";
import { Subject, Subscription } from "rxjs";
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { SearchService } from "../../services/search.service";
import { debounceTime, distinctUntilChanged, switchMap } from "rxjs/operators";
import { Suggestion, SearchFilters } from "../../models/document.models";
import { DocumentApiService } from "../../services/document-api.service";
import { SearchFiltersComponent } from "../search-filters/search-filters.component";

@Component({
  selector: 'app-header',
  standalone: true,
  imports: [CommonModule, FormsModule, SearchFiltersComponent, MatIconModule, MatTooltipModule],
  templateUrl: './header.component.html',
  styleUrls: ['./header.component.css']
})
export class HeaderComponent implements OnInit, OnDestroy {
  searchQuery: string = '';
  suggestions: Suggestion[] = [];
  showFilters = false;
  userInitials: string = '';
  currentFilters?: SearchFilters;

  private searchSubject = new Subject<string>();
  private searchSubscription!: Subscription;

  private searchService = inject(SearchService);
  private apiService = inject(DocumentApiService);
  private router = inject(Router);
  private elementRef = inject(ElementRef);

  @Input() hasSelection: boolean = false;
  @Input() set userData(value: any) {
    if (value) {
      const data = value.userData || value;
      this.calculateInitials(data);
    }
  }

  constructor() { }

  ngOnInit(): void {
    this.searchSubscription = this.searchSubject.pipe(
      debounceTime(300),
      distinctUntilChanged(),
      switchMap(query => this.searchService.getSuggestions(query))
    ).subscribe(suggestions => {
      this.suggestions = suggestions;
    });
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent) {
    if (this.showFilters) {
      const clickedInside = this.elementRef.nativeElement.contains(event.target);
      if (!clickedInside) {
        this.showFilters = false;
      }
    }
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

  onMobileMenuToggle() {
    console.log('Header: onMobileMenuToggle called');
    this.mobileMenuToggle.emit();
    console.log('Header: mobileMenuToggle event emitted');
  }

  hasActiveFilters(): boolean {
    if (!this.currentFilters) return false;

    return !!(
      this.currentFilters.type ||
      this.currentFilters.fileType ||
      this.currentFilters.dateModified ||
      this.currentFilters.owner ||
      (this.currentFilters.metadata && this.currentFilters.metadata.length > 0)
    );
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
    if (suggestion.id != null && suggestion.ext == null) {
      this.router.navigate(['/my-folder'], { queryParams: { folderId: suggestion.id } });
    }
    this.suggestions = [];
  }

  private calculateInitials(userData: any) {
    if (!userData) return;
    const name = userData.name || userData.preferred_username || 'User';

    if (userData.given_name && userData.family_name) {
      this.userInitials = (userData.given_name[0] + userData.family_name[0]).toUpperCase();
    } else if (name.includes(' ')) {
      const parts = name.split(' ');
      if (parts.length >= 2) {
        this.userInitials = (parts[0][0] + parts[1][0]).toUpperCase();
      } else {
        this.userInitials = name.substring(0, 2).toUpperCase();
      }
    } else {
      this.userInitials = name.substring(0, 2).toUpperCase();
    }
  }

  navigateToSettings() {
    this.router.navigate(['/settings']);
  }
}