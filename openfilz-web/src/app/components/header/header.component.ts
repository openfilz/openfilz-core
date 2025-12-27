import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Component, ElementRef, HostListener, Input, OnDestroy, OnInit, Output, EventEmitter, inject } from "@angular/core";
import { Router } from "@angular/router";
import { Subject, Subscription } from "rxjs";
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { SearchService } from "../../services/search.service";
import { debounceTime, distinctUntilChanged, switchMap } from "rxjs/operators";
import { Suggestion, SearchFilters } from "../../models/document.models";
import { DocumentApiService } from "../../services/document-api.service";
import { SearchFiltersComponent } from "../search-filters/search-filters.component";
import { TranslateService, TranslatePipe } from "@ngx-translate/core";
import { MatMenuModule } from '@angular/material/menu';
import { MatButtonModule } from '@angular/material/button';

@Component({
  selector: 'app-header',
  standalone: true,
  imports: [CommonModule, FormsModule, SearchFiltersComponent, MatIconModule, MatTooltipModule, TranslatePipe, MatMenuModule, MatButtonModule],
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
  private translate = inject(TranslateService);

  // Language selector
  availableLanguages = [
    { code: 'ar', name: 'العربية', flag: '🇸🇦' },
    { code: 'de', name: 'Deutsch', flag: '🇩🇪' },
    { code: 'en', name: 'English', flag: '🇬🇧' },
    { code: 'es', name: 'Español', flag: '🇪🇸' },
    { code: 'fr', name: 'Français', flag: '🇫🇷' },
    { code: 'it', name: 'Italiano', flag: '🇮🇹' },
    { code: 'nl', name: 'Nederlands', flag: '🇳🇱' },
    { code: 'pt', name: 'Português', flag: '🇵🇹' }
  ];
  currentLanguage = this.availableLanguages.find(l => l.code === 'en') || this.availableLanguages[2];

  @Input() hasSelection: boolean = false;
  @Input() set userData(value: any) {
    if (value) {
      const data = value.userData || value;
      this.calculateInitials(data);
    }
  }

  @Output() mobileMenuToggle = new EventEmitter<void>();
  constructor() { }

  ngOnInit(): void {
    // Initialize language from localStorage or browser default
    this.initializeLanguage();

    this.searchSubscription = this.searchSubject.pipe(
      debounceTime(300),
      distinctUntilChanged(),
      switchMap(query => this.searchService.getSuggestions(query))
    ).subscribe(suggestions => {
      this.suggestions = suggestions;
    });
  }

  private initializeLanguage(): void {
    const savedLang = localStorage.getItem('preferredLanguage');
    const browserLang = this.translate.getBrowserLang();
    const supportedLangs = ['en', 'fr', 'de', 'ar', 'es', 'pt', 'it', 'nl'];
    const defaultLang = savedLang || (browserLang && supportedLangs.includes(browserLang) ? browserLang : 'en');

    this.currentLanguage = this.availableLanguages.find(l => l.code === defaultLang) || this.availableLanguages[0];
    this.translate.use(this.currentLanguage.code);
    this.updateDocumentDirection(this.currentLanguage.code);
  }

  switchLanguage(lang: { code: string; name: string; flag: string }): void {
    this.currentLanguage = lang;
    this.translate.use(lang.code);
    localStorage.setItem('preferredLanguage', lang.code);
    this.updateDocumentDirection(lang.code);
  }

  private updateDocumentDirection(langCode: string): void {
    const dir = langCode === 'ar' ? 'rtl' : 'ltr';
    document.documentElement.setAttribute('dir', dir);
    document.documentElement.setAttribute('lang', langCode);
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

  selectSuggestion(suggestion: Suggestion): void {
    // Clear suggestions and search query
    this.suggestions = [];
    this.searchQuery = '';

    // ext is undefined/null for folders, string (possibly empty) for files
    const isFolder = suggestion.ext == null;

    if (isFolder) {
      // Navigate to folder with folderId param
      this.router.navigate(['/my-folder'], {
        queryParams: { folderId: suggestion.id }
      });
    } else {
      // Navigate to file's parent folder with target file info
      this.router.navigate(['/my-folder'], {
        queryParams: { targetFileId: suggestion.id }
      });
    }
  }

  ngOnDestroy(): void {
    if (this.searchSubscription) {
      this.searchSubscription.unsubscribe();
    }
  }

  getIconForExtension(ext: string | undefined): string {
    if (ext == null) {
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
    event.stopPropagation();

    // Clear suggestions and search query
    this.suggestions = [];
    this.searchQuery = '';

    // ext is undefined/null for folders, string (possibly empty) for files
    const isFolder = suggestion.ext == null;

    if (isFolder) {
      // Open folder in file explorer (same as clicking folder icon/name)
      this.router.navigate(['/my-folder'], { queryParams: { folderId: suggestion.id } });
    } else {
      // Navigate to file's parent folder, focus the file, and open file viewer
      this.router.navigate(['/my-folder'], {
        queryParams: { targetFileId: suggestion.id, openViewer: 'true' }
      });
    }
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