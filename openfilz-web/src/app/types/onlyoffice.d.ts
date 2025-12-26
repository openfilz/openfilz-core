/**
 * TypeScript declarations for OnlyOffice DocumentServer API.
 * @see https://api.onlyoffice.com/editors/config/
 */

declare namespace DocsAPI {
    /**
     * Creates a new OnlyOffice document editor instance.
     */
    class DocEditor {
        constructor(placeholderId: string, config: EditorConfig);

        /**
         * Destroys the editor instance and removes it from the DOM.
         */
        destroyEditor(): void;

        /**
         * Downloads the document in the specified format.
         */
        downloadAs(format: string): void;

        /**
         * Requests the editor to close.
         */
        requestClose(): void;

        /**
         * Refreshes the document history.
         */
        refreshHistory(historyData: unknown): void;

        /**
         * Sets the action link for document sharing.
         */
        setActionLink(link: string): void;

        /**
         * Sets the favorite icon state.
         */
        setFavorite(favorite: boolean): void;

        /**
         * Sets the list of users for mentions.
         */
        setUsers(users: { users: UserInfo[] }): void;
    }

    /**
     * Main configuration object for the OnlyOffice editor.
     */
    interface EditorConfig {
        /** Document configuration */
        document: DocumentConfig;
        /** Type of document: "word", "cell", or "slide" */
        documentType: 'word' | 'cell' | 'slide';
        /** Editor configuration */
        editorConfig?: EditorSettings;
        /** JWT token for authentication */
        token?: string;
        /** Editor type */
        type?: 'desktop' | 'mobile' | 'embedded';
        /** Editor height */
        height?: string;
        /** Editor width */
        width?: string;
        /** Event handlers */
        events?: EditorEvents;
    }

    /**
     * Document configuration.
     */
    interface DocumentConfig {
        /** File extension (e.g., "docx", "xlsx") */
        fileType: string;
        /** Unique document key for caching */
        key: string;
        /** Document title */
        title: string;
        /** URL to fetch the document */
        url: string;
        /** Document permissions */
        permissions?: Permissions;
        /** Document info */
        info?: DocumentInfo;
    }

    /**
     * Document permissions.
     */
    interface Permissions {
        /** Allow downloading the document */
        download?: boolean;
        /** Allow editing the document */
        edit?: boolean;
        /** Allow printing the document */
        print?: boolean;
        /** Allow reviewing changes */
        review?: boolean;
        /** Allow commenting */
        comment?: boolean;
        /** Allow copying content */
        copy?: boolean;
        /** Allow filling forms */
        fillForms?: boolean;
        /** Allow modifying content controls */
        modifyContentControl?: boolean;
        /** Allow modifying filter */
        modifyFilter?: boolean;
    }

    /**
     * Document info for display.
     */
    interface DocumentInfo {
        /** Document owner */
        owner?: string;
        /** Folder path */
        folder?: string;
        /** Upload date */
        uploaded?: string;
        /** Document sharing settings */
        sharingSettings?: SharingSettings[];
    }

    /**
     * Sharing settings for a user.
     */
    interface SharingSettings {
        /** Permission level */
        permissions: string;
        /** User info */
        user: string;
    }

    /**
     * Editor settings configuration.
     */
    interface EditorSettings {
        /** Callback URL for save events */
        callbackUrl?: string;
        /** Editor language */
        lang?: string;
        /** Editor mode */
        mode?: 'view' | 'edit';
        /** Current user info */
        user?: UserInfo;
        /** Customization options */
        customization?: Customization;
        /** Recent files */
        recent?: RecentFile[];
        /** Co-editing mode */
        coEditing?: CoEditingConfig;
    }

    /**
     * User information.
     */
    interface UserInfo {
        /** User ID */
        id: string;
        /** Display name */
        name: string;
        /** User group (optional) */
        group?: string;
    }

    /**
     * Customization options for the editor UI.
     */
    interface Customization {
        /** Enable autosave */
        autosave?: boolean;
        /** Enable chat */
        chat?: boolean;
        /** Enable comments */
        comments?: boolean;
        /** Enable force save button */
        forcesave?: boolean;
        /** Compact header mode */
        compactHeader?: boolean;
        /** Compact toolbar mode */
        compactToolbar?: boolean;
        /** Hide right menu */
        hideRightMenu?: boolean;
        /** Hide rulers */
        hideRulers?: boolean;
        /** Integration mode */
        integrationMode?: 'embed';
        /** Toolbar tabs visibility */
        toolbarNoTabs?: boolean;
        /** Logo configuration */
        logo?: LogoConfig;
        /** Customer configuration */
        customer?: CustomerConfig;
        /** Features configuration */
        features?: FeaturesConfig;
        /** Goback configuration */
        goback?: GobackConfig;
    }

    /**
     * Logo configuration.
     */
    interface LogoConfig {
        /** Logo image URL */
        image?: string;
        /** Logo image URL for dark theme */
        imageDark?: string;
        /** Logo click URL */
        url?: string;
    }

    /**
     * Customer branding configuration.
     */
    interface CustomerConfig {
        /** Customer address */
        address?: string;
        /** Customer info */
        info?: string;
        /** Customer logo URL */
        logo?: string;
        /** Customer logo URL for dark theme */
        logoDark?: string;
        /** Customer email */
        mail?: string;
        /** Customer name */
        name?: string;
        /** Customer phone */
        phone?: string;
        /** Customer website */
        www?: string;
    }

    /**
     * Feature flags configuration.
     */
    interface FeaturesConfig {
        /** Enable spell check */
        spellcheck?: SpellcheckConfig;
    }

    /**
     * Spell check configuration.
     */
    interface SpellcheckConfig {
        /** Enable or disable spell check */
        mode?: boolean;
    }

    /**
     * Go back button configuration.
     */
    interface GobackConfig {
        /** Go back URL */
        url?: string;
        /** Open in new tab */
        blank?: boolean;
        /** Go back button text */
        text?: string;
    }

    /**
     * Recent file entry.
     */
    interface RecentFile {
        /** File folder */
        folder: string;
        /** File title */
        title: string;
        /** File URL */
        url: string;
    }

    /**
     * Co-editing configuration.
     */
    interface CoEditingConfig {
        /** Co-editing mode: "fast" or "strict" */
        mode?: 'fast' | 'strict';
        /** Change tracking mode */
        change?: boolean;
    }

    /**
     * Editor event handlers.
     */
    interface EditorEvents {
        /** Called when the application is ready */
        onAppReady?: () => void;
        /** Called when document state changes (modified/saved) */
        onDocumentStateChange?: (event: DocumentStateChangeEvent) => void;
        /** Called when an error occurs */
        onError?: (event: ErrorEvent) => void;
        /** Called when the user requests to close the editor */
        onRequestClose?: () => void;
        /** Called when a warning occurs */
        onWarning?: (event: WarningEvent) => void;
        /** Called when document is ready */
        onDocumentReady?: () => void;
        /** Called when download is ready */
        onDownloadAs?: (event: DownloadAsEvent) => void;
        /** Called for collaborative editing info */
        onInfo?: (event: InfoEvent) => void;
        /** Called when requesting history */
        onRequestHistory?: () => void;
        /** Called when requesting history data */
        onRequestHistoryData?: (event: RequestHistoryDataEvent) => void;
        /** Called when requesting to insert image */
        onRequestInsertImage?: (event: RequestInsertImageEvent) => void;
        /** Called when requesting users for mentions */
        onRequestUsers?: () => void;
    }

    /**
     * Document state change event.
     */
    interface DocumentStateChangeEvent {
        /** True if document has unsaved changes */
        data: boolean;
    }

    /**
     * Error event.
     */
    interface ErrorEvent {
        /** Error data */
        data: {
            /** Error code */
            errorCode: number;
            /** Error description */
            errorDescription: string;
        };
    }

    /**
     * Warning event.
     */
    interface WarningEvent {
        /** Warning data */
        data: {
            /** Warning code */
            warningCode: number;
            /** Warning description */
            warningDescription: string;
        };
    }

    /**
     * Download as event.
     */
    interface DownloadAsEvent {
        /** Download data */
        data: {
            /** File type */
            fileType: string;
            /** Download URL */
            url: string;
        };
    }

    /**
     * Info event for collaborative editing.
     */
    interface InfoEvent {
        /** Info data */
        data: {
            /** Operation mode */
            mode: string;
            /** Connected users */
            users?: string[];
        };
    }

    /**
     * Request history data event.
     */
    interface RequestHistoryDataEvent {
        /** History data */
        data: number;
    }

    /**
     * Request insert image event.
     */
    interface RequestInsertImageEvent {
        /** Image data */
        data: {
            /** Image insertion mode */
            c: string;
        };
    }
}

