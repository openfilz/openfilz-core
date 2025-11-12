import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {Component, EventEmitter, Input, OnDestroy, OnInit, Output} from "@angular/core";
import {Subject, Subscription} from "rxjs";
import {SearchService} from "../../services/search.service";
import {debounceTime, distinctUntilChanged, switchMap} from "rxjs/operators";
import {Suggestion} from "../../models/document.models";
import {DocumentApiService} from "../../services/document-api.service";
import {environment} from "../../../environments/environment";

@Component({
  selector: 'app-header',
  standalone: true,
  templateUrl: './header.component.html',
  styleUrls: ['./header.component.css'],
    imports: [
        CommonModule,
        FormsModule
    ],
})
export class HeaderComponent implements OnInit, OnDestroy {
  @Input() hasSelection = false;
  @Output() search = new EventEmitter<string>();

    searchQuery: string = '';
    suggestions: Suggestion[] = [];


    // A Subject is a special type of Observable that allows values to be multicasted to many Observers.
    // We will push the user's search query into this subject on every keystroke.
    private searchSubject = new Subject<string>();

    private searchSubscription!: Subscription;

    constructor(private searchService: SearchService, private apiService: DocumentApiService) { }

    ngOnInit(): void {
        // We set up the subscription in ngOnInit
        this.searchSubscription = this.searchSubject.pipe(
            // 1. Wait for 300ms of silence after each keystroke
            debounceTime(300),

            // 2. Ignore new term if same as previous term (e.g., arrow keys)
            distinctUntilChanged(),

            // 3. Switch to a new search observable each time the term changes.
            // This also automatically cancels previous in-flight HTTP requests.
            switchMap(query => this.searchService.getSuggestions(query))
        ).subscribe(suggestions => {
            this.suggestions = suggestions;
        });
    }

    /**
     * This method is called on every keystroke from the input field.
     * It pushes the current search query into our RxJS stream.
     */
    onSearchInput(): void {
        this.searchSubject.next(this.searchQuery);
    }

    /**
     * This is the function for the FINAL search action, triggered by hitting Enter
     *
     */
    onSearch(): void {
        console.log(`Performing a full search for: "${this.searchQuery}"`);

        this.search.emit(this.searchQuery);

        // Here you would typically navigate to a search results page:
        // this.router.navigate(['/search'], { queryParams: { q: this.searchQuery } });

        // Clear suggestions after a search is committed
        this.suggestions = [];
    }

    /**
     * A helper function to handle when a user clicks a suggestion.
     */
    selectSuggestion(docId: string): void {
        console.log(`Selected Suggestion: ${docId}`);
    }

    ngOnDestroy(): void {
        // It's crucial to unsubscribe to prevent memory leaks when the component is destroyed.
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
        event.stopPropagation(); // VERY IMPORTANT: Prevents the li's click event from firing

        console.log(`Downloading document with ID: ${suggestion.id}`);
        // Here, you would call a service to initiate the download.
        this.apiService.downloadDocument(suggestion.id).subscribe({
            next: (blob) => {
                const url = window.URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                if(suggestion.ext == null) {
                    a.download = suggestion.s + ".zip";
                } else if(suggestion.ext.length > 0) {
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

        // You could also create a dynamic link:
        // const link = document.createElement('a');
        // link.href = `/api/v1/documents/${suggestion.id}/download`;
        // link.click();

        this.suggestions = []; // Clear suggestions after action
        
    }

    protected onOpen(suggestion: Suggestion, event: MouseEvent) {
        event.stopPropagation(); // VERY IMPORTANT: Prevents the li's click event from firing

        console.log(`Opening document with ID: ${suggestion.id}`);
        // Here, you would navigate to the document's detail page.
        // Example: this.router.navigate(['/documents', suggestion.id]);

        this.suggestions = []; // Clear suggestions after action
    }
}