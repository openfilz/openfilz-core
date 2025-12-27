import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, from, of, throwError } from 'rxjs';
import { map, tap, catchError, switchMap } from 'rxjs/operators';
import { environment } from '../../environments/environment';

/**
 * Response from the OnlyOffice config endpoint.
 */
export interface OnlyOfficeConfig {
    documentServerUrl: string;
    apiJsUrl: string;
    config: OnlyOfficeEditorConfig;
    token: string;
}

/**
 * OnlyOffice editor configuration.
 */
export interface OnlyOfficeEditorConfig {
    document: {
        fileType: string;
        key: string;
        title: string;
        url: string;
        permissions?: {
            download?: boolean;
            edit?: boolean;
            print?: boolean;
            review?: boolean;
            comment?: boolean;
        };
    };
    documentType: 'word' | 'cell' | 'slide';
    editorConfig: {
        callbackUrl?: string;
        lang?: string;
        mode?: 'view' | 'edit';
        user?: {
            id: string;
            name: string;
        };
        customization?: {
            autosave?: boolean;
            chat?: boolean;
            comments?: boolean;
            forcesave?: boolean;
        };
    };
}

/**
 * OnlyOffice status response.
 */
export interface OnlyOfficeStatus {
    enabled: boolean;
    status: string;
}

/**
 * OnlyOffice supported check response.
 */
export interface OnlyOfficeSupportedResponse {
    fileName: string;
    supported: boolean;
}

/**
 * Service for interacting with OnlyOffice DocumentServer.
 * Handles API script loading, editor configuration, and file type checks.
 */
@Injectable({
    providedIn: 'root'
})
export class OnlyOfficeService {
    private http = inject(HttpClient);
    private readonly baseUrl = environment.apiURL;
    private scriptLoaded = false;
    private scriptLoading = false;
    private scriptLoadPromise: Promise<boolean> | null = null;

    /**
     * Supported file extensions for OnlyOffice editing.
     */
    private static readonly SUPPORTED_EXTENSIONS = [
        'docx', 'doc', 'xlsx', 'xls', 'pptx', 'ppt',
        'odt', 'ods', 'odp', 'pdf'
    ];

    /**
     * Get the editor configuration for a document.
     * @param documentId The document ID to edit
     * @param canEdit Whether the user can edit the document
     * @returns Observable with editor configuration
     */
    getEditorConfig(documentId: string, canEdit: boolean = true): Observable<OnlyOfficeConfig> {
        return this.http.get<OnlyOfficeConfig>(
            `${this.baseUrl}/onlyoffice/config/${documentId}?canEdit=${canEdit}`
        );
    }

    /**
     * Check if OnlyOffice is enabled on the server.
     * @returns Observable with status
     */
    getStatus(): Observable<OnlyOfficeStatus> {
        return this.http.get<OnlyOfficeStatus>(`${this.baseUrl}/onlyoffice/status`);
    }

    /**
     * Check if a file is supported by OnlyOffice (server-side check).
     * @param fileName The file name to check
     * @returns Observable with support status
     */
    checkSupported(fileName: string): Observable<OnlyOfficeSupportedResponse> {
        return this.http.get<OnlyOfficeSupportedResponse>(
            `${this.baseUrl}/onlyoffice/supported?fileName=${encodeURIComponent(fileName)}`
        );
    }

    /**
     * Load the OnlyOffice API JavaScript file.
     * @param apiJsUrl The URL to the OnlyOffice API JS file
     * @returns Observable that completes when the script is loaded
     */
    loadApiScript(apiJsUrl: string): Observable<boolean> {
        if (this.scriptLoaded) {
            return of(true);
        }

        if (this.scriptLoading && this.scriptLoadPromise) {
            return from(this.scriptLoadPromise);
        }

        this.scriptLoading = true;
        this.scriptLoadPromise = new Promise<boolean>((resolve, reject) => {
            // Check if script is already in DOM
            const existingScript = document.querySelector(`script[src="${apiJsUrl}"]`);
            if (existingScript) {
                this.scriptLoaded = true;
                this.scriptLoading = false;
                resolve(true);
                return;
            }

            const script = document.createElement('script');
            script.src = apiJsUrl;
            script.async = true;

            script.onload = () => {
                this.scriptLoaded = true;
                this.scriptLoading = false;
                console.log('OnlyOffice API script loaded successfully');
                resolve(true);
            };

            script.onerror = (error) => {
                this.scriptLoading = false;
                this.scriptLoadPromise = null;
                console.error('Failed to load OnlyOffice API script:', error);
                reject(new Error('Failed to load OnlyOffice API script'));
            };

            document.head.appendChild(script);
        });

        return from(this.scriptLoadPromise);
    }

    /**
     * Check if OnlyOffice integration is enabled in the environment.
     * @returns true if OnlyOffice is enabled
     */
    isOnlyOfficeEnabled(): boolean {
        return (environment as any).onlyOffice?.enabled ?? false;
    }


    /**
     * Check if a file extension is supported by OnlyOffice (client-side check).
     * @param fileName The file name to check
     * @returns true if the file type is supported
     */
    isSupportedExtension(fileName: string): boolean {
        if (!fileName) {
            return false;
        }
        const ext = this.getFileExtension(fileName).toLowerCase();
        return OnlyOfficeService.SUPPORTED_EXTENSIONS.includes(ext);
    }

    /**
     * Get the document type for OnlyOffice based on file extension.
     * @param fileName The file name
     * @returns Document type: 'word', 'cell', or 'slide'
     */
    getDocumentType(fileName: string): 'word' | 'cell' | 'slide' {
        const ext = this.getFileExtension(fileName).toLowerCase();

        switch (ext) {
            case 'xls':
            case 'xlsx':
            case 'ods':
            case 'csv':
                return 'cell';
            case 'ppt':
            case 'pptx':
            case 'odp':
                return 'slide';
            default:
                return 'word';
        }
    }

    /**
     * Get the file extension from a file name.
     * @param fileName The file name
     * @returns File extension without the dot
     */
    private getFileExtension(fileName: string): string {
        if (!fileName) {
            return '';
        }
        const lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(lastDot + 1) : '';
    }

    /**
     * Initialize the OnlyOffice editor with configuration.
     * Loads the API script if needed, then creates the editor instance.
     * @param documentId The document ID to edit
     * @param placeholderId The DOM element ID where the editor will be mounted
     * @param canEdit Whether the user can edit the document
     * @returns Observable that emits the editor instance
     */
    initializeEditor(
        documentId: string,
        placeholderId: string,
        canEdit: boolean = true
    ): Observable<{ editor: any; config: OnlyOfficeConfig }> {
        return this.getEditorConfig(documentId, canEdit).pipe(
            switchMap(config => {
                return this.loadApiScript(config.apiJsUrl).pipe(
                    map(() => config)
                );
            }),
            map(config => {
                // Create the editor configuration with all required fields
                const editorConfig: DocsAPI.EditorConfig = {
                    document: config.config.document,
                    documentType: config.config.documentType,
                    editorConfig: config.config.editorConfig,
                    token: config.token,
                    height: '100%',
                    width: '100%'
                };

                // Create the editor instance
                const editor = new DocsAPI.DocEditor(placeholderId, editorConfig);

                return { editor, config };
            }),
            catchError(error => {
                console.error('Failed to initialize OnlyOffice editor:', error);
                return throwError(() => new Error('Failed to initialize OnlyOffice editor'));
            })
        );
    }
}
