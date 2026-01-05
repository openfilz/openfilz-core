import { Component, inject, OnInit, OnDestroy, ViewChild, ElementRef, AfterViewInit } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { MatDialogRef, MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DomSanitizer, SafeResourceUrl, SafeHtml } from '@angular/platform-browser';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';

import { DocumentApiService } from '../../services/document-api.service';
import { OnlyOfficeService } from '../../services/onlyoffice.service';
import { RoleService } from '../../services/role.service';
import { OnlyOfficeEditorComponent } from '../../components/onlyoffice-editor/onlyoffice-editor.component';
import { saveAs } from 'file-saver';

// PDF.js imports
import * as pdfjsLib from 'pdfjs-dist';

// Syntax highlighting
import hljs from 'highlight.js';

// Office document viewers
import * as mammoth from 'mammoth';
import * as XLSX from 'xlsx';

export interface FileViewerDialogData {
  documentId: string;
  fileName: string;
  contentType: string;
  fileSize?: number;
}

type ViewerMode = 'pdf' | 'image' | 'text' | 'office' | 'onlyoffice' | 'unsupported';

/**
 * Maximum file size in bytes for preview (10 MB).
 * Files larger than this will show a warning and require download.
 * This limit does NOT apply to OnlyOffice documents.
 */
const MAX_PREVIEW_FILE_SIZE = 10 * 1024 * 1024; // 10 MB

@Component({
  selector: 'app-file-viewer-dialog',
  standalone: true,
  templateUrl: './file-viewer-dialog.component.html',
  styleUrls: ['./file-viewer-dialog.component.css'],
  imports: [
    DecimalPipe,
    MatDialogModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatToolbarModule,
    MatTooltipModule,
    OnlyOfficeEditorComponent,
    TranslatePipe
  ],
})
export class FileViewerDialogComponent implements OnInit, AfterViewInit, OnDestroy {
  @ViewChild('pdfCanvas', { static: false }) pdfCanvas?: ElementRef<HTMLCanvasElement>;
  @ViewChild('imageContainer', { static: false }) imageContainer?: ElementRef<HTMLDivElement>;

  loading: boolean = true;
  error?: string;
  isFullscreen: boolean = false;
  fileTooLarge: boolean = false;

  viewerMode: ViewerMode = 'unsupported';

  // Common properties
  fileBlob?: Blob;
  fileUrl?: string;

  // Image viewer properties
  imageSrc?: SafeResourceUrl;
  imageZoom: number = 1;
  imageRotation: number = 0;

  // PDF viewer properties
  pdfDocument?: pdfjsLib.PDFDocumentProxy;
  currentPage: number = 1;
  totalPages: number = 0;
  pdfZoom: number = 1;
  pdfRotation: number = 0;

  // Text viewer properties
  textContent?: string;
  highlightedContent?: SafeHtml;

  // Office viewer properties
  officeContent?: SafeHtml;

  readonly dialogRef = inject(MatDialogRef<FileViewerDialogComponent>);
  readonly data = inject<FileViewerDialogData>(MAT_DIALOG_DATA);
  private documentApi = inject(DocumentApiService);
  private onlyOfficeService = inject(OnlyOfficeService);
  private roleService = inject(RoleService);
  private snackBar = inject(MatSnackBar);
  private sanitizer = inject(DomSanitizer);
  private translate = inject(TranslateService);

  constructor() {
    // Configure PDF.js worker
    pdfjsLib.GlobalWorkerOptions.workerSrc =
      `https://cdnjs.cloudflare.com/ajax/libs/pdf.js/4.10.38/pdf.worker.min.mjs`;
  }

  /**
   * Determines if the user can edit documents in OnlyOffice.
   * CONTRIBUTOR role = can edit
   * READER role = view only
   */
  get canEditDocument(): boolean {
    return this.roleService.hasRole('CONTRIBUTOR');
  }

  /**
   * Checks if the user has permission to view files.
   */
  get canViewDocument(): boolean {
    return this.roleService.hasRole('CONTRIBUTOR') || this.roleService.hasRole('READER');
  }

  ngOnInit() {
    // Check if user has permission to view files
    if (!this.canViewDocument) {
      this.error = 'errors.notAllowedToViewFiles';
      this.loading = false;
      this.snackBar.open(
        this.translate.instant('errors.notAllowedToViewFiles'),
        this.translate.instant('common.close'),
        { duration: 5000 }
      );
      return;
    }

    this.determineViewerMode();

    // Check file size limit (skip for OnlyOffice which handles its own loading)
    if (this.viewerMode !== 'onlyoffice' && this.isFileTooLarge()) {
      this.fileTooLarge = true;
      this.loading = false;
      return;
    }

    this.loadDocument();
  }

  /**
   * Checks if the file exceeds the maximum preview size.
   */
  private isFileTooLarge(): boolean {
    return this.data.fileSize !== undefined && this.data.fileSize > MAX_PREVIEW_FILE_SIZE;
  }

  /**
   * Gets the maximum preview file size in MB for display.
   */
  get maxPreviewSizeMB(): number {
    return MAX_PREVIEW_FILE_SIZE / (1024 * 1024);
  }

  ngAfterViewInit() {
    // Canvas will be rendered after view init for PDF
  }

  ngOnDestroy() {
    // Clean up object URLs
    if (this.fileUrl) {
      URL.revokeObjectURL(this.fileUrl);
    }
  }

  private determineViewerMode() {
    const contentType = this.data.contentType?.toLowerCase() || '';
    const fileName = this.data.fileName?.toLowerCase() || '';

    // Check if OnlyOffice is enabled and file is supported
    if (this.onlyOfficeService.isOnlyOfficeEnabled() &&
      this.onlyOfficeService.isSupportedExtension(fileName)) {
      this.viewerMode = 'onlyoffice';
      return;
    }

    // PDF
    if (contentType === 'application/pdf' || fileName.endsWith('.pdf')) {
      this.viewerMode = 'pdf';
    }
    // Images
    else if (contentType.startsWith('image/') ||
      /\.(jpg|jpeg|png|gif|webp|svg|bmp)$/i.test(fileName)) {
      this.viewerMode = 'image';
    }
    // Text/Code files
    else if (contentType.startsWith('text/') ||
      contentType === 'application/json' ||
      contentType === 'application/xml' ||
      /\.(txt|json|xml|html|css|js|ts|java|py|md|yml|yaml|sh|bat|log)$/i.test(fileName)) {
      this.viewerMode = 'text';
    }
    // Office documents (fallback when OnlyOffice is disabled)
    else if (contentType === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' ||
      contentType === 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' ||
      /\.(docx|xlsx)$/i.test(fileName)) {
      this.viewerMode = 'office';
    }
    else {
      this.viewerMode = 'unsupported';
    }
  }

  private loadDocument() {
    // OnlyOffice handles its own loading
    if (this.viewerMode === 'onlyoffice') {
      this.loading = false;
      return;
    }

    this.loading = true;
    this.error = undefined;

    this.documentApi.downloadDocument(this.data.documentId).subscribe({
      next: (blob) => {
        this.fileBlob = blob;
        this.fileUrl = URL.createObjectURL(blob);

        switch (this.viewerMode) {
          case 'pdf':
            this.loadPdf();
            break;
          case 'image':
            this.loadImage();
            break;
          case 'text':
            this.loadText();
            break;
          case 'office':
            this.loadOfficeDocument();
            break;
          default:
            this.loading = false;
            this.error = 'errors.unsupportedType';
        }
      },
      error: (err) => {
        this.error = 'errors.loadFailed';
        this.loading = false;
        this.snackBar.open(this.translate.instant(this.error), this.translate.instant('common.close'), { duration: 3000 });
      }
    });
  }

  // ========== OnlyOffice Event Handlers ==========
  onEditorReady() {
    console.log('OnlyOffice editor is ready');
  }

  onDocumentSaved() {
    console.log('Document saved via OnlyOffice');
    this.snackBar.open(this.translate.instant('metadataPanel.saveSuccess'), this.translate.instant('common.close'), { duration: 2000 });
  }

  onEditorError(error: string) {
    console.error('OnlyOffice editor error:', error);
    this.error = error;
    this.snackBar.open(error, this.translate.instant('common.close'), { duration: 5000 });
  }

  // ========== PDF Viewer ==========
  private async loadPdf() {
    try {
      if (!this.fileUrl) return;

      const loadingTask = pdfjsLib.getDocument(this.fileUrl);
      this.pdfDocument = await loadingTask.promise;
      this.totalPages = this.pdfDocument.numPages;
      this.loading = false;

      // Render first page
      setTimeout(() => this.renderPdfPage(), 100);
    } catch (err) {
      this.error = 'errors.pdfLoadFailed';
      this.loading = false;
      this.snackBar.open(this.translate.instant(this.error), this.translate.instant('common.close'), { duration: 3000 });
    }
  }

  private async renderPdfPage() {
    if (!this.pdfDocument || !this.pdfCanvas) return;

    try {
      const page = await this.pdfDocument.getPage(this.currentPage);
      const viewport = page.getViewport({ scale: this.pdfZoom, rotation: this.pdfRotation });

      const canvas = this.pdfCanvas.nativeElement;
      const context = canvas.getContext('2d');
      if (!context) return;

      canvas.width = viewport.width;
      canvas.height = viewport.height;

      const renderContext = {
        canvasContext: context,
        viewport: viewport
      };

      await page.render(renderContext).promise;
    } catch (err) {
      console.error('Error rendering PDF page:', err);
    }
  }

  nextPage() {
    if (this.currentPage < this.totalPages) {
      this.currentPage++;
      this.renderPdfPage();
    }
  }

  previousPage() {
    if (this.currentPage > 1) {
      this.currentPage--;
      this.renderPdfPage();
    }
  }

  zoomIn() {
    if (this.viewerMode === 'pdf') {
      this.pdfZoom = Math.min(this.pdfZoom + 0.25, 3);
      this.renderPdfPage();
    } else if (this.viewerMode === 'image') {
      this.imageZoom = Math.min(this.imageZoom + 0.25, 3);
    }
  }

  zoomOut() {
    if (this.viewerMode === 'pdf') {
      this.pdfZoom = Math.max(this.pdfZoom - 0.25, 0.5);
      this.renderPdfPage();
    } else if (this.viewerMode === 'image') {
      this.imageZoom = Math.max(this.imageZoom - 0.25, 0.5);
    }
  }

  resetZoom() {
    if (this.viewerMode === 'pdf') {
      this.pdfZoom = 1;
      this.renderPdfPage();
    } else if (this.viewerMode === 'image') {
      this.imageZoom = 1;
    }
  }

  rotate() {
    if (this.viewerMode === 'pdf') {
      this.pdfRotation = (this.pdfRotation + 90) % 360;
      this.renderPdfPage();
    } else if (this.viewerMode === 'image') {
      this.imageRotation = (this.imageRotation + 90) % 360;
    }
  }

  // ========== Image Viewer ==========
  private loadImage() {
    if (this.fileUrl) {
      this.imageSrc = this.sanitizer.bypassSecurityTrustResourceUrl(this.fileUrl);
      this.loading = false;
    }
  }

  get imageTransform(): string {
    return `scale(${this.imageZoom}) rotate(${this.imageRotation}deg)`;
  }

  // ========== Text Viewer ==========
  private async loadText() {
    try {
      if (!this.fileBlob) return;

      const text = await this.fileBlob.text();
      this.textContent = text;

      // Apply syntax highlighting
      const extension = this.getFileExtension(this.data.fileName);
      const language = this.detectLanguage(extension);

      let highlighted: string;
      if (language) {
        highlighted = hljs.highlight(text, { language }).value;
      } else {
        highlighted = hljs.highlightAuto(text).value;
      }

      this.highlightedContent = this.sanitizer.bypassSecurityTrustHtml(
        `<pre><code class="hljs">${highlighted}</code></pre>`
      );

      this.loading = false;
    } catch (err) {
      this.error = 'errors.textLoadFailed';
      this.loading = false;
      this.snackBar.open(this.translate.instant(this.error), this.translate.instant('common.close'), { duration: 3000 });
    }
  }

  private detectLanguage(extension: string): string | null {
    const languageMap: { [key: string]: string } = {
      'js': 'javascript',
      'ts': 'typescript',
      'java': 'java',
      'py': 'python',
      'html': 'html',
      'css': 'css',
      'json': 'json',
      'xml': 'xml',
      'md': 'markdown',
      'yml': 'yaml',
      'yaml': 'yaml',
      'sh': 'bash',
      'bat': 'batch'
    };

    return languageMap[extension] || null;
  }

  private getFileExtension(fileName: string): string {
    const lastDot = fileName.lastIndexOf('.');
    if (lastDot === -1) return '';
    return fileName.substring(lastDot + 1).toLowerCase();
  }

  // ========== Office Document Viewer ==========
  private async loadOfficeDocument() {
    try {
      if (!this.fileBlob) return;

      const extension = this.getFileExtension(this.data.fileName);

      if (extension === 'docx') {
        await this.loadDocx();
      } else if (extension === 'xlsx') {
        await this.loadXlsx();
      } else {
        this.error = 'errors.officeUnsupported';
        this.loading = false;
      }
    } catch (err) {
      this.error = 'errors.officeLoadFailed';
      this.loading = false;
      this.snackBar.open(this.translate.instant(this.error), this.translate.instant('common.close'), { duration: 3000 });
    }
  }

  private async loadDocx() {
    if (!this.fileBlob) return;

    const arrayBuffer = await this.fileBlob.arrayBuffer();
    const result = await mammoth.convertToHtml({ arrayBuffer });

    this.officeContent = this.sanitizer.bypassSecurityTrustHtml(
      `<div class="docx-content">${result.value}</div>`
    );
    this.loading = false;
  }

  private async loadXlsx() {
    if (!this.fileBlob) return;

    const arrayBuffer = await this.fileBlob.arrayBuffer();
    const workbook = XLSX.read(arrayBuffer, { type: 'array' });

    // Convert all sheets to HTML
    let htmlContent = '';
    workbook.SheetNames.forEach((sheetName) => {
      const worksheet = workbook.Sheets[sheetName];
      const html = XLSX.utils.sheet_to_html(worksheet);
      htmlContent += `<h3>${sheetName}</h3>${html}`;
    });

    this.officeContent = this.sanitizer.bypassSecurityTrustHtml(
      `<div class="xlsx-content">${htmlContent}</div>`
    );
    this.loading = false;
  }

  // ========== Actions ==========
  download() {
    if (this.fileBlob) {
      saveAs(this.fileBlob, this.data.fileName);
    } else {
      // For OnlyOffice documents, download directly from the API
      this.documentApi.downloadDocument(this.data.documentId).subscribe({
        next: (blob) => saveAs(blob, this.data.fileName),
        error: () => this.snackBar.open(
          this.translate.instant('errors.downloadFailed'),
          this.translate.instant('common.close'),
          { duration: 3000 }
        )
      });
    }
  }

  print() {
    window.print();
  }

  toggleFullscreen() {
    this.isFullscreen = !this.isFullscreen;
    if (this.isFullscreen) {
      this.dialogRef.updateSize('100vw', '100vh');
      this.dialogRef.addPanelClass('fullscreen-dialog');
    } else {
      this.dialogRef.updateSize('95vw', '95vh');
      this.dialogRef.removePanelClass('fullscreen-dialog');
    }

    // Re-render PDF if in PDF mode to adjust to new size
    if (this.viewerMode === 'pdf') {
      setTimeout(() => this.renderPdfPage(), 100);
    }
  }

  onClose() {
    this.dialogRef.close();
  }

  get currentZoom(): number {
    return this.viewerMode === 'pdf' ? this.pdfZoom : this.imageZoom;
  }

  get showZoomControls(): boolean {
    return this.viewerMode === 'pdf' || this.viewerMode === 'image';
  }

  get showPageNavigation(): boolean {
    return this.viewerMode === 'pdf' && this.totalPages > 1;
  }
}
