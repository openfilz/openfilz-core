import { Component, Inject, OnInit, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatDialogRef, MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTabsModule } from '@angular/material/tabs';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatDividerModule } from '@angular/material/divider';
import { MatTooltipModule } from '@angular/material/tooltip';

import { DocumentApiService } from '../../services/document-api.service';
import { DocumentInfo } from '../../models/document.models';
import { MetadataEditorComponent } from '../../components/metadata-editor/metadata-editor.component';

export interface DocumentPropertiesDialogData {
  documentId: string;
  documentName: string;
}

@Component({
  selector: 'app-document-properties-dialog',
  standalone: true,
  templateUrl: './document-properties-dialog.component.html',
  styleUrls: ['./document-properties-dialog.component.css'],
  imports: [
    CommonModule,
    MatDialogModule,
    MatButtonModule,
    MatIconModule,
    MatTabsModule,
    MatProgressSpinnerModule,
    MatDividerModule,
    MatTooltipModule,
    MetadataEditorComponent
  ],
})
export class DocumentPropertiesDialogComponent implements OnInit {
  @ViewChild(MetadataEditorComponent) metadataEditor?: MetadataEditorComponent;

  documentInfo?: DocumentInfo;
  loading: boolean = true;
  saving: boolean = false;
  error?: string;
  editMode: boolean = false;
  metadataValid: boolean = true;
  currentMetadata: { [key: string]: any } = {};
  originalMetadata: { [key: string]: any } = {};
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


  constructor(
    public dialogRef: MatDialogRef<DocumentPropertiesDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: DocumentPropertiesDialogData,
    private documentApi: DocumentApiService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit() {
    this.loadDocumentInfo();
  }

  loadDocumentInfo() {
    this.loading = true;
    this.error = undefined;

    this.documentApi.getDocumentInfo(this.data.documentId, true).subscribe({
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
    }
  }

  saveMetadata() {
    if (!this.metadataValid || !this.documentInfo) {
      return;
    }

    this.saving = true;

    // Get the current metadata from the editor
    const metadataToSave = this.metadataEditor?.getMetadata() || {};

    this.documentApi.updateDocumentMetadata(this.data.documentId, metadataToSave).subscribe({
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
      if (confirm('You have unsaved changes. Are you sure you want to close?')) {
        this.dialogRef.close();
      }
    } else {
      this.dialogRef.close();
    }
  }
}
