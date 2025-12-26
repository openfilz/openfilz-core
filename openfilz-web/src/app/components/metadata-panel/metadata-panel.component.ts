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
import { TranslateService, TranslatePipe } from '@ngx-translate/core';
import { FileIconService } from '../../services/file-icon.service';

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
    MetadataEditorComponent,
    TranslatePipe
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

  // MIME type to translation key mapping
  private readonly MIME_TYPE_KEYS: { [key: string]: string } = {
    'application/pdf': 'mimeTypes.pdf',
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document': 'mimeTypes.word',
    'application/msword': 'mimeTypes.wordLegacy',
    'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': 'mimeTypes.excel',
    'application/vnd.ms-excel': 'mimeTypes.excelLegacy',
    'application/vnd.openxmlformats-officedocument.presentationml.presentation': 'mimeTypes.powerpoint',
    'application/vnd.ms-powerpoint': 'mimeTypes.powerpointLegacy',
    'text/plain': 'mimeTypes.text',
    'text/html': 'mimeTypes.html',
    'text/css': 'mimeTypes.css',
    'text/javascript': 'mimeTypes.js',
    'application/json': 'mimeTypes.json',
    'application/xml': 'mimeTypes.xml',
    'text/xml': 'mimeTypes.xml',
    'image/png': 'mimeTypes.png',
    'image/jpeg': 'mimeTypes.jpg',
    'image/gif': 'mimeTypes.gif',
    'image/webp': 'mimeTypes.webp',
    'image/svg+xml': 'mimeTypes.svg',
    'image/bmp': 'mimeTypes.bmp',
    'image/tiff': 'mimeTypes.tiff',
    'audio/mpeg': 'mimeTypes.mp3',
    'audio/wav': 'mimeTypes.wav',
    'audio/ogg': 'mimeTypes.ogg',
    'video/mp4': 'mimeTypes.mp4',
    'video/webm': 'mimeTypes.webm',
    'video/quicktime': 'mimeTypes.mov',
    'application/zip': 'mimeTypes.zip',
    'application/x-rar-compressed': 'mimeTypes.rar',
    'application/x-7z-compressed': 'mimeTypes.7z',
    'application/gzip': 'mimeTypes.gz',
    'application/x-tar': 'mimeTypes.tar'
  };

  // File extension to translation key mapping (fallback when contentType is unavailable)
  private readonly EXTENSION_KEYS: { [key: string]: string } = {
    'pdf': 'mimeTypes.pdf',
    'doc': 'mimeTypes.wordLegacy',
    'docx': 'mimeTypes.word',
    'xls': 'mimeTypes.excelLegacy',
    'xlsx': 'mimeTypes.excel',
    'ppt': 'mimeTypes.powerpointLegacy',
    'pptx': 'mimeTypes.powerpoint',
    'txt': 'mimeTypes.text',
    'html': 'mimeTypes.html',
    'htm': 'mimeTypes.html',
    'css': 'mimeTypes.css',
    'js': 'mimeTypes.js',
    'ts': 'mimeTypes.js',
    'json': 'mimeTypes.json',
    'xml': 'mimeTypes.xml',
    'png': 'mimeTypes.png',
    'jpg': 'mimeTypes.jpg',
    'jpeg': 'mimeTypes.jpg',
    'gif': 'mimeTypes.gif',
    'webp': 'mimeTypes.webp',
    'svg': 'mimeTypes.svg',
    'bmp': 'mimeTypes.bmp',
    'tiff': 'mimeTypes.tiff',
    'tif': 'mimeTypes.tiff',
    'mp3': 'mimeTypes.mp3',
    'wav': 'mimeTypes.wav',
    'ogg': 'mimeTypes.ogg',
    'mp4': 'mimeTypes.mp4',
    'webm': 'mimeTypes.webm',
    'mov': 'mimeTypes.mov',
    'avi': 'mimeTypes.mp4',
    'zip': 'mimeTypes.zip',
    'rar': 'mimeTypes.rar',
    '7z': 'mimeTypes.7z',
    'gz': 'mimeTypes.gz',
    'tar': 'mimeTypes.tar'
  };

  private documentApi = inject(DocumentApiService);
  private fileIconService = inject(FileIconService);
  private snackBar = inject(MatSnackBar);
  private dialog = inject(MatDialog);
  private translate = inject(TranslateService);

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
        this.error = 'metadataPanel.errorLoading';
        this.loading = false;
        this.snackBar.open(this.translate.instant(this.error), this.translate.instant('common.close'), { duration: 3000 });
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

  getFriendlyTypeKey(): { key: string, params?: any } {
    if (!this.documentInfo) {
      return { key: 'mimeTypes.unknown' };
    }
    if (this.documentInfo.type === 'FOLDER') {
      return { key: 'common.folder' };
    }

    const contentType = this.documentInfo.contentType;
    if (contentType) {
      if (this.MIME_TYPE_KEYS[contentType]) {
        return { key: this.MIME_TYPE_KEYS[contentType] };
      }
      // Try to parse the MIME type
      const parts = contentType.split('/');
      const category = parts[0];
      const subtype = parts[1];
      if (subtype) {
        const subtypeLower = subtype.toLowerCase();
        if (subtypeLower.includes('pdf')) return { key: 'mimeTypes.pdf' };
        if (subtypeLower.includes('word')) return { key: 'mimeTypes.word' };
        if (subtypeLower.includes('excel') || subtypeLower.includes('spreadsheet')) return { key: 'mimeTypes.excel' };
        if (subtypeLower.includes('powerpoint') || subtypeLower.includes('presentation')) return { key: 'mimeTypes.powerpoint' };
        const friendlySubtype = subtypeLower.replace(/^x-/, '').replace(/-/g, ' ').split(' ').map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(' ');

        switch (category) {
          case 'image': return { key: 'mimeTypes.imageType', params: { type: friendlySubtype } };
          case 'video': return { key: 'mimeTypes.videoType', params: { type: friendlySubtype } };
          case 'audio': return { key: 'mimeTypes.audioType', params: { type: friendlySubtype } };
          case 'text': return { key: 'common.fileWithType', params: { type: friendlySubtype } };
          default: return { key: 'common.fileWithType', params: { type: friendlySubtype } };
        }
      }
    }

    // Fallback: try to get type from file extension
    if (this.documentInfo.name) {
      const extension = this.fileIconService.getFileExtension(this.documentInfo.name);
      if (extension && (this.EXTENSION_KEYS as any)[extension]) {
        return { key: (this.EXTENSION_KEYS as any)[extension] };
      }
    }

    return { key: 'common.file' };
  }

  /**
   * Copy SHA256 hash to clipboard
   */
  copySha256ToClipboard() {
    const hash = this.sha256Hash;
    if (hash) {
      navigator.clipboard.writeText(hash).then(() => {
        this.snackBar.open(this.translate.instant('metadataPanel.hashCopied'), this.translate.instant('common.close'), { duration: 2000 });
      }).catch(() => {
        this.snackBar.open(this.translate.instant('metadataPanel.copyFailed'), this.translate.instant('common.close'), { duration: 2000 });
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
        this.snackBar.open(this.translate.instant('metadataPanel.saveSuccess'), this.translate.instant('common.close'), { duration: 3000 });
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
        this.snackBar.open(this.translate.instant('metadataPanel.saveFailed'), this.translate.instant('common.close'), { duration: 3000 });
        this.saving = false;
      }
    });
  }

  formatFileSize(bytes?: number): string {
    if (bytes === undefined || bytes === 0) return '0 B';
    return this.fileIconService.getFileSize(bytes);
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
        title: this.translate.instant('metadataPanel.unsavedChangesTitle'),
        message: this.translate.instant('metadataPanel.unsavedChangesMessage'),
        details: this.translate.instant('metadataPanel.unsavedChangesDetails'),
        type: 'warning',
        confirmText: this.translate.instant('metadataPanel.discardChanges'),
        cancelText: this.translate.instant('metadataPanel.keepEditing'),
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

  getAuditActionKey(action: string): string {
    return `audit.actions.${action}`;
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

  formatAuditDateKey(timestamp: string): { key: string, params?: any } {
    const date = new Date(timestamp);
    const now = new Date();
    const diffMs = now.getTime() - date.getTime();
    const diffMins = Math.floor(diffMs / 60000);
    const diffHours = Math.floor(diffMs / 3600000);
    const diffDays = Math.floor(diffMs / 86400000);

    if (diffMins < 1) return { key: 'audit.time.justNow' };
    if (diffMins < 60) return { key: diffMins === 1 ? 'audit.time.minAgo' : 'audit.time.minsAgo', params: { count: diffMins } };
    if (diffHours < 24) return { key: diffHours === 1 ? 'audit.time.hourAgo' : 'audit.time.hoursAgo', params: { count: diffHours } };
    if (diffDays < 7) return { key: diffDays === 1 ? 'audit.time.dayAgo' : 'audit.time.daysAgo', params: { count: diffDays } };

    return { key: date.toLocaleDateString() + ' ' + date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) };
  }
}
