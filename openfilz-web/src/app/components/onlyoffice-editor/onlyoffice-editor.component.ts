import {
    Component,
    Input,
    Output,
    EventEmitter,
    OnInit,
    OnDestroy,
    ElementRef,
    ViewChild,
    inject
} from '@angular/core';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { OnlyOfficeService, OnlyOfficeConfig } from '../../services/onlyoffice.service';

/**
 * Component for embedding OnlyOffice Document Editor.
 * Handles loading the OnlyOffice API, initializing the editor,
 * and managing the editor lifecycle.
 */
@Component({
    selector: 'app-onlyoffice-editor',
    standalone: true,
    imports: [MatProgressSpinnerModule],
    template: `
        <div class="onlyoffice-container">
            @if (loading) {
                <div class="loading-overlay">
                    <mat-spinner diameter="48"></mat-spinner>
                    <span class="loading-text">Loading editor...</span>
                </div>
            }
            @if (error) {
                <div class="error-message">
                    <span class="error-icon">error</span>
                    <span>{{ error }}</span>
                </div>
            }
            <div #editorContainer
                 [id]="editorId"
                 class="editor-frame"
                 [class.hidden]="loading || error">
            </div>
        </div>
    `,
    styles: [`
        .onlyoffice-container {
            width: 100%;
            height: 100%;
            position: relative;
            display: flex;
            flex-direction: column;
        }

        .editor-frame {
            width: 100%;
            height: 100%;
            flex: 1;
        }

        .editor-frame.hidden {
            visibility: hidden;
        }

        .loading-overlay {
            position: absolute;
            top: 0;
            left: 0;
            right: 0;
            bottom: 0;
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            background: rgba(255, 255, 255, 0.9);
            z-index: 10;
        }

        .loading-text {
            margin-top: 16px;
            font-size: 14px;
            color: #666;
        }

        .error-message {
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            height: 100%;
            color: #d32f2f;
            padding: 20px;
            text-align: center;
        }

        .error-icon {
            font-family: 'Material Icons';
            font-size: 48px;
            margin-bottom: 16px;
        }
    `]
})
export class OnlyOfficeEditorComponent implements OnInit, OnDestroy {
    @ViewChild('editorContainer', { static: true }) editorContainer!: ElementRef;

    /** Document ID to edit */
    @Input() documentId!: string;

    /** Whether the user can edit the document */
    @Input() canEdit: boolean = true;

    /** Emitted when the editor is ready */
    @Output() editorReady = new EventEmitter<void>();

    /** Emitted when the document is saved */
    @Output() documentSaved = new EventEmitter<void>();

    /** Emitted when the editor is closed */
    @Output() editorClosed = new EventEmitter<void>();

    /** Emitted when an error occurs */
    @Output() editorError = new EventEmitter<string>();

    /** Emitted when document state changes */
    @Output() documentStateChange = new EventEmitter<boolean>();

    loading = true;
    error?: string;

    private docEditor?: any;
    private onlyOfficeService = inject(OnlyOfficeService);

    /** Unique ID for the editor container element */
    readonly editorId = `onlyoffice-editor-${Math.random().toString(36).substring(2, 9)}`;

    ngOnInit() {
        if (!this.documentId) {
            this.error = 'Document ID is required';
            this.loading = false;
            this.editorError.emit(this.error);
            return;
        }

        this.initializeEditor();
    }

    ngOnDestroy() {
        this.destroyEditor();
    }

    /**
     * Initialize the OnlyOffice editor.
     */
    private initializeEditor() {
        this.loading = true;
        this.error = undefined;

        this.onlyOfficeService.getEditorConfig(this.documentId, this.canEdit).subscribe({
            next: (config) => {
                this.loadScriptAndCreateEditor(config);
            },
            error: (err) => {
                console.error('Failed to get OnlyOffice config:', err);
                this.error = 'Failed to load editor configuration';
                this.loading = false;
                this.editorError.emit(this.error);
            }
        });
    }

    /**
     * Load the OnlyOffice API script and create the editor.
     */
    private loadScriptAndCreateEditor(config: OnlyOfficeConfig) {
        this.onlyOfficeService.loadApiScript(config.apiJsUrl).subscribe({
            next: () => {
                this.createEditor(config);
            },
            error: (err) => {
                console.error('Failed to load OnlyOffice API:', err);
                this.error = 'Failed to load OnlyOffice editor';
                this.loading = false;
                this.editorError.emit(this.error);
            }
        });
    }

    /**
     * Create the OnlyOffice editor instance.
     */
    private createEditor(config: OnlyOfficeConfig) {
        try {
            const editorConfig: DocsAPI.EditorConfig = {
                document: config.config.document,
                documentType: config.config.documentType,
                editorConfig: config.config.editorConfig,
                token: config.token,
                height: '100%',
                width: '100%',
                events: {
                    onAppReady: () => {
                        console.log('OnlyOffice editor ready');
                        this.loading = false;
                        this.editorReady.emit();
                    },
                    onDocumentStateChange: (event: DocsAPI.DocumentStateChangeEvent) => {
                        // event.data is true if document has unsaved changes
                        this.documentStateChange.emit(event.data);
                        if (!event.data) {
                            // Document was just saved
                            this.documentSaved.emit();
                        }
                    },
                    onRequestClose: () => {
                        console.log('OnlyOffice editor close requested');
                        this.editorClosed.emit();
                    },
                    onError: (event: DocsAPI.ErrorEvent) => {
                        console.error('OnlyOffice error:', event.data);
                        this.error = `Editor error: ${event.data.errorDescription || 'Unknown error'}`;
                        this.editorError.emit(this.error);
                    },
                    onWarning: (event: DocsAPI.WarningEvent) => {
                        console.warn('OnlyOffice warning:', event.data);
                    },
                    onDocumentReady: () => {
                        console.log('OnlyOffice document ready');
                    }
                }
            };

            // Create the editor instance
            this.docEditor = new DocsAPI.DocEditor(this.editorId, editorConfig);
            console.log('OnlyOffice editor created for document:', this.documentId);

        } catch (err) {
            console.error('Failed to create OnlyOffice editor:', err);
            this.error = 'Failed to create OnlyOffice editor';
            this.loading = false;
            this.editorError.emit(this.error);
        }
    }

    /**
     * Destroy the editor instance and clean up resources.
     */
    private destroyEditor() {
        if (this.docEditor) {
            try {
                this.docEditor.destroyEditor();
                console.log('OnlyOffice editor destroyed');
            } catch (e) {
                console.warn('Error destroying OnlyOffice editor:', e);
            }
            this.docEditor = undefined;
        }
    }

    /**
     * Request the document to be downloaded in a specific format.
     */
    downloadAs(format: string) {
        if (this.docEditor) {
            this.docEditor.downloadAs(format);
        }
    }

    /**
     * Request to close the editor.
     */
    requestClose() {
        if (this.docEditor) {
            this.docEditor.requestClose();
        }
    }
}
