import { Component, Input, Output, EventEmitter, OnInit, OnChanges, ViewChild, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDividerModule } from '@angular/material/divider';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatTabsModule } from '@angular/material/tabs';
import { MatDialog } from '@angular/material/dialog';
import { trigger, state, style, transition, animate } from '@angular/animations';

import { MetadataEditorComponent } from '../metadata-editor/metadata-editor.component';
import { DocumentApiService } from '../../services/document-api.service';
import { AuditLog, DocumentInfo } from '../../models/document.models';
import { ConfirmDialogComponent, ConfirmDialogData } from '../../dialogs/confirm-dialog/confirm-dialog.component';

@Component({
  selector: 'app-metadata-panel',
  standalone: true,
  templateUrl: './metadata-panel.component.html',
  styleUrls: ['./metadata-panel.component.css'],
  imports: [
    CommonModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
    MatDividerModule,
    MatTooltipModule,
    MatTabsModule,
    MetadataEditorComponent
  ],
  animations: [
    trigger('slideInOut', [
      state('in', style({
        transform: 'translateX(0)',
        opacity: 1
      })),
      state('out', style({
        transform: 'translateX(100%)',
        opacity: 0
      })),
      transition('in => out', animate('300ms ease-in-out')),
      transition('out => in', animate('300ms ease-in-out'))
    ])
  ]
})
export class MetadataPanelComponent implements OnInit, OnChanges {
  @ViewChild(MetadataEditorComponent) metadataEditor?: MetadataEditorComponent;

  @Input() documentId?: string;
  @Input() isOpen: boolean = false;
  @Output() closePanel = new EventEmitter<void>();
  @Output() metadataSaved = new EventEmitter<void>();

  documentInfo?: DocumentInfo;
  loading: boolean = false;
  saving: boolean = false;
  error?: string;
  editMode: boolean = false;
  metadataValid: boolean = true;
  currentMetadata: { [key: string]: any } = {};
  originalMetadata: { [key: string]: any } = {};

  // Audit
  auditLogs: AuditLog[] = [];
  auditLoading: boolean = false;
  auditError?: string;
  selectedTabIndex: number = 0;

  // System metadata keys that should not be editable
  private readonly SYSTEM_METADATA_KEYS = ['sha256'];

  // MIME type to friendly name mapping
  private readonly MIME_TYPE_NAMES: { [key: string]: string } = {
    'application/pdf': 'PDF Document',
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document': 'Word Document',
    'application/msword': 'Word Document (Legacy)',
    'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': 'Excel Spreadsheet',
    'application/vnd.ms-excel': 'Excel Spreadsheet (Legacy)',
    'application/vnd.openxmlformats-officedocument.presentationml.presentation': 'PowerPoint Presentation',
    'application/vnd.ms-powerpoint': 'PowerPoint Presentation (Legacy)',
    'text/plain': 'Text File',
    'text/html': 'HTML Document',
    'text/css': 'CSS Stylesheet',
    'text/javascript': 'JavaScript File',
    'application/json': 'JSON File',
    'application/xml': 'XML File',
    'text/xml': 'XML File',
    'image/png': 'PNG Image',
    'image/jpeg': 'JPEG Image',
    'image/gif': 'GIF Image',
    'image/webp': 'WebP Image',
    'image/svg+xml': 'SVG Image',
    'image/bmp': 'BMP Image',
    'image/tiff': 'TIFF Image',
    'audio/mpeg': 'MP3 Audio',
    'audio/wav': 'WAV Audio',
    'audio/ogg': 'OGG Audio',
    'video/mp4': 'MP4 Video',
    'video/webm': 'WebM Video',
    'video/quicktime': 'QuickTime Video',
    'application/zip': 'ZIP Archive',
    'application/x-rar-compressed': 'RAR Archive',
    'application/x-7z-compressed': '7-Zip Archive',
    'application/gzip': 'GZip Archive',
    'application/x-tar': 'TAR Archive'
  };

  // File extension to friendly name mapping (fallback when contentType is unavailable)
  private readonly EXTENSION_NAMES: { [key: string]: string } = {
    'pdf': 'PDF Document',
    'doc': 'Word Document (Legacy)',
    'docx': 'Word Document',
    'xls': 'Excel Spreadsheet (Legacy)',
    'xlsx': 'Excel Spreadsheet',
    'ppt': 'PowerPoint Presentation (Legacy)',
    'pptx': 'PowerPoint Presentation',
    'txt': 'Text File',
    'html': 'HTML Document',
    'htm': 'HTML Document',
    'css': 'CSS Stylesheet',
    'js': 'JavaScript File',
    'ts': 'TypeScript File',
    'json': 'JSON File',
    'xml': 'XML File',
    'png': 'PNG Image',
    'jpg': 'JPEG Image',
    'jpeg': 'JPEG Image',
    'gif': 'GIF Image',
    'webp': 'WebP Image',
    'svg': 'SVG Image',
    'bmp': 'BMP Image',
    'tiff': 'TIFF Image',
    'tif': 'TIFF Image',
    'mp3': 'MP3 Audio',
    'wav': 'WAV Audio',
    'ogg': 'OGG Audio',
    'mp4': 'MP4 Video',
    'webm': 'WebM Video',
    'mov': 'QuickTime Video',
    'avi': 'AVI Video',
    'zip': 'ZIP Archive',
    'rar': 'RAR Archive',
    '7z': '7-Zip Archive',
    'gz': 'GZip Archive',
    'tar': 'TAR Archive'
  };

  private documentApi = inject(DocumentApiService);
  private snackBar = inject(MatSnackBar);
  private dialog = inject(MatDialog);

  constructor() { }

  ngOnInit() {
    if (this.documentId && this.isOpen) {
      this.loadDocumentInfo();
    }
  }

  ngOnChanges() {
    if (this.documentId && this.isOpen && !this.documentInfo) {
      this.loadDocumentInfo();
    } else if (!this.isOpen) {
      // Reset state when panel closes
      this.resetState();
    }
  }

  private resetState() {
    this.editMode = false;
    this.documentInfo = undefined;
    this.currentMetadata = {};
    this.originalMetadata = {};
    this.error = undefined;
    this.auditLogs = [];
    this.auditError = undefined;
    this.selectedTabIndex = 0;
  }

  loadDocumentInfo() {
    if (!this.documentId) return;

    this.loading = true;
    this.error = undefined;

    this.documentApi.getDocumentInfo(this.documentId, true).subscribe({
      next: (info) => {
        this.documentInfo = info;
        // Filter out system metadata keys from editable metadata
        const allMetadata = info.metadata || {};
        this.originalMetadata = this.filterEditableMetadata(allMetadata);
        this.currentMetadata = { ...this.originalMetadata };
        this.loading = false;
      },
      error: (err) => {
        this.error = 'Failed to load document information';
        this.loading = false;
        this.snackBar.open(this.error, 'Close', { duration: 3000 });
      }
    });
  }


  /**
   * Filter out system metadata keys that should not be editable
   */
  private filterEditableMetadata(metadata: { [key: string]: any }): { [key: string]: any } {
    const filtered: { [key: string]: any } = {};
    for (const key of Object.keys(metadata)) {
      if (!this.SYSTEM_METADATA_KEYS.includes(key)) {
        filtered[key] = metadata[key];
      }
    }
    return filtered;
  }

  /**
   * Get SHA256 hash from metadata if it exists
   */
  get sha256Hash(): string | undefined {
    return this.documentInfo?.metadata?.['sha256'];
  }

  /**
   * Get friendly file type name from MIME type or file extension
   */
  getFriendlyTypeName(): string {
    if (!this.documentInfo) {
      return 'Unknown';
    }
    if (this.documentInfo.type === 'FOLDER') {
      return 'Folder';
    }

    // Try to get type from contentType first
    const contentType = this.documentInfo.contentType;
    if (contentType) {
      if (this.MIME_TYPE_NAMES[contentType]) {
        return this.MIME_TYPE_NAMES[contentType];
      }
      // Try to parse the MIME type
      const parts = contentType.split('/');
      const category = parts[0];
      const subtype = parts[1];
      if (subtype) {
        const subtypeLower = subtype.toLowerCase();
        if (subtypeLower.includes('pdf')) return 'PDF Document';
        if (subtypeLower.includes('word')) return 'Word Document';
        if (subtypeLower.includes('excel') || subtypeLower.includes('spreadsheet')) return 'Spreadsheet';
        if (subtypeLower.includes('powerpoint') || subtypeLower.includes('presentation')) return 'Presentation';
        const friendlySubtype = subtypeLower.replace(/^x-/, '').replace(/-/g, ' ').split(' ').map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(' ');
        switch (category) {
          case 'image': return friendlySubtype + ' Image';
          case 'video': return friendlySubtype + ' Video';
          case 'audio': return friendlySubtype + ' Audio';
          case 'text': return friendlySubtype + ' File';
          default: return friendlySubtype + ' File';
        }
      }
    }

    // Fallback: try to get type from file extension
    if (this.documentInfo.name) {
      const extension = this.getFileExtension(this.documentInfo.name);
      if (extension && this.EXTENSION_NAMES[extension]) {
        return this.EXTENSION_NAMES[extension];
      }
    }

    return 'File';
  }

  /**
   * Extract file extension from filename (lowercase, without dot)
   */
  private getFileExtension(filename: string): string | null {
    const lastDot = filename.lastIndexOf('.');
    if (lastDot === -1 || lastDot === filename.length - 1) {
      return null;
    }
    return filename.substring(lastDot + 1).toLowerCase();
  }

  /**
   * Copy SHA256 hash to clipboard
   */
  copySha256ToClipboard() {
    const hash = this.sha256Hash;
    if (hash) {
      navigator.clipboard.writeText(hash).then(() => {
        this.snackBar.open('SHA256 hash copied to clipboard', 'Close', { duration: 2000 });
      }).catch(() => {
        this.snackBar.open('Failed to copy to clipboard', 'Close', { duration: 2000 });
      });
    }
  }

  onMetadataChange(metadata: { [key: string]: any }) {
    this.currentMetadata = metadata;
  }

  onValidChange(valid: boolean) {
    this.metadataValid = valid;
  }

  toggleEditMode() {
    if (this.editMode) {
      // Cancel editing - revert to original
      this.currentMetadata = { ...this.originalMetadata };
      this.editMode = false;
    } else {
      this.editMode = true;
      // Switch to Metadata tab when entering edit mode
      this.selectedTabIndex = 1;
    }
  }

  saveMetadata() {
    if (!this.metadataValid || !this.documentInfo || !this.documentId) {
      return;
    }

    this.saving = true;

    // Get the current metadata from the editor
    const metadataToSave = this.metadataEditor?.getMetadata() || {};

    this.documentApi.updateDocumentMetadata(this.documentId, metadataToSave).subscribe({
      next: () => {
        this.snackBar.open('Metadata saved successfully', 'Close', { duration: 3000 });
        this.originalMetadata = { ...metadataToSave };
        this.currentMetadata = { ...metadataToSave };
        this.editMode = false;
        this.saving = false;

        // Update the document info
        if (this.documentInfo) {
          this.documentInfo.metadata = metadataToSave;
        }

        this.metadataSaved.emit();
      },
      error: (err) => {
        this.snackBar.open('Failed to save metadata', 'Close', { duration: 3000 });
        this.saving = false;
      }
    });
  }

  formatFileSize(bytes?: number): string {
    if (!bytes || bytes === 0) return '0 Bytes';
    const k = 1024;
    const sizes = ['Bytes', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return Math.round((bytes / Math.pow(k, i)) * 100) / 100 + ' ' + sizes[i];
  }

  formatDate(date?: string): string {
    if (!date) return 'N/A';
    return new Date(date).toLocaleString();
  }

  get hasChanges(): boolean {
    return JSON.stringify(this.currentMetadata) !== JSON.stringify(this.originalMetadata);
  }

  get canSave(): boolean {
    return this.editMode && this.metadataValid && this.hasChanges && !this.saving;
  }

  onClose() {
    if (this.editMode && this.hasChanges) {
      const dialogData: ConfirmDialogData = {
        title: 'Unsaved Changes',
        message: 'You have unsaved changes. Are you sure you want to close?',
        details: 'Your changes will be lost if you close without saving.',
        type: 'warning',
        confirmText: 'Discard Changes',
        cancelText: 'Keep Editing',
        icon: 'edit_off'
      };
      const dialogRef = this.dialog.open(ConfirmDialogComponent, {
        width: '450px',
        data: dialogData
      });
      dialogRef.afterClosed().subscribe(confirmed => {
        if (confirmed) {
          this.closePanel.emit();
        }
      });
    } else {
      this.closePanel.emit();
    }
  }

  get animationState(): string {
    return this.isOpen ? 'in' : 'out';
  }

  // Audit methods
  onTabChange(index: number) {
    this.selectedTabIndex = index;
    if (index === 2 && this.auditLogs.length === 0 && !this.auditLoading) {
      this.loadAuditTrail();
    }
  }

  loadAuditTrail() {
    if (!this.documentId) return;

    this.auditLoading = true;
    this.auditError = undefined;

    this.documentApi.getAuditTrail(this.documentId, 'DESC').subscribe({
      next: (logs) => {
        this.auditLogs = logs;
        this.auditLoading = false;
      },
      error: (err) => {
        this.auditError = 'Failed to load audit history';
        this.auditLoading = false;
      }
    });
  }

  getAuditActionLabel(action: string): string {
    const labels: { [key: string]: string } = {
      'COPY_FILE': 'File Copied',
      'COPY_FILE_CHILD': 'Child File Copied',
      'RENAME_FILE': 'File Renamed',
      'RENAME_FOLDER': 'Folder Renamed',
      'COPY_FOLDER': 'Folder Copied',
      'DELETE_FILE': 'File Deleted',
      'DELETE_FILE_CHILD': 'Child File Deleted',
      'DELETE_FOLDER': 'Folder Deleted',
      'CREATE_FOLDER': 'Folder Created',
      'MOVE_FILE': 'File Moved',
      'MOVE_FOLDER': 'Folder Moved',
      'UPLOAD_DOCUMENT': 'Document Uploaded',
      'REPLACE_DOCUMENT_CONTENT': 'Content Replaced',
      'REPLACE_DOCUMENT_METADATA': 'Metadata Replaced',
      'UPDATE_DOCUMENT_METADATA': 'Metadata Updated',
      'DOWNLOAD_DOCUMENT': 'Document Downloaded',
      'DELETE_DOCUMENT_METADATA': 'Metadata Deleted',
      'SHARE_DOCUMENTS': 'Documents Shared',
      'RESTORE_FILE': 'File Restored',
      'RESTORE_FOLDER': 'Folder Restored',
      'PERMANENT_DELETE_FILE': 'File Permanently Deleted',
      'PERMANENT_DELETE_FOLDER': 'Folder Permanently Deleted',
      'EMPTY_RECYCLE_BIN': 'Recycle Bin Emptied'
    };
    return labels[action] || action.replace(/_/g, ' ');
  }

  getAuditActionIcon(action: string): string {
    const icons: { [key: string]: string } = {
      'COPY_FILE': 'file_copy',
      'COPY_FILE_CHILD': 'file_copy',
      'RENAME_FILE': 'edit',
      'RENAME_FOLDER': 'edit',
      'COPY_FOLDER': 'folder_copy',
      'DELETE_FILE': 'delete',
      'DELETE_FILE_CHILD': 'delete',
      'DELETE_FOLDER': 'folder_delete',
      'CREATE_FOLDER': 'create_new_folder',
      'MOVE_FILE': 'drive_file_move',
      'MOVE_FOLDER': 'drive_folder_upload',
      'UPLOAD_DOCUMENT': 'upload_file',
      'REPLACE_DOCUMENT_CONTENT': 'sync',
      'REPLACE_DOCUMENT_METADATA': 'label',
      'UPDATE_DOCUMENT_METADATA': 'label',
      'DOWNLOAD_DOCUMENT': 'download',
      'DELETE_DOCUMENT_METADATA': 'label_off',
      'SHARE_DOCUMENTS': 'share',
      'RESTORE_FILE': 'restore',
      'RESTORE_FOLDER': 'restore',
      'PERMANENT_DELETE_FILE': 'delete_forever',
      'PERMANENT_DELETE_FOLDER': 'delete_forever',
      'EMPTY_RECYCLE_BIN': 'delete_sweep'
    };
    return icons[action] || 'history';
  }

  getAuditActionColor(action: string): string {
    if (action.includes('DELETE') || action.includes('PERMANENT')) return 'warn';
    if (action.includes('UPLOAD') || action.includes('CREATE')) return 'success';
    if (action.includes('RESTORE')) return 'success';
    return 'primary';
  }

  formatAuditDate(timestamp: string): string {
    const date = new Date(timestamp);
    const now = new Date();
    const diffMs = now.getTime() - date.getTime();
    const diffMins = Math.floor(diffMs / 60000);
    const diffHours = Math.floor(diffMs / 3600000);
    const diffDays = Math.floor(diffMs / 86400000);

    if (diffMins < 1) return 'Just now';
    if (diffMins < 60) return `${diffMins} min ago`;
    if (diffHours < 24) return `${diffHours} hours ago`;
    if (diffDays < 7) return `${diffDays} days ago`;

    return date.toLocaleDateString() + ' ' + date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }
}
