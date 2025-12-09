import { Component, inject, OnInit, OnDestroy, ViewChild, ElementRef, AfterViewInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatDialogRef, MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DomSanitizer, SafeResourceUrl, SafeHtml } from '@angular/platform-browser';

import { DocumentApiService } from '../../services/document-api.service';
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
}

type ViewerMode = 'pdf' | 'image' | 'text' | 'office' | 'unsupported';

@Component({
  selector: 'app-file-viewer-dialog',
  standalone: true,
  templateUrl: './file-viewer-dialog.component.html',
  styleUrls: ['./file-viewer-dialog.component.css'],
  imports: [
    CommonModule,
    MatDialogModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatToolbarModule,
    MatTooltipModule
  ],
})
export class FileViewerDialogComponent implements OnInit, AfterViewInit, OnDestroy {
  @ViewChild('pdfCanvas', { static: false }) pdfCanvas?: ElementRef<HTMLCanvasElement>;
  @ViewChild('imageContainer', { static: false }) imageContainer?: ElementRef<HTMLDivElement>;

  loading: boolean = true;
  error?: string;

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
  private snackBar = inject(MatSnackBar);
  private sanitizer = inject(DomSanitizer);

  constructor() {
    // Configure PDF.js worker
    pdfjsLib.GlobalWorkerOptions.workerSrc =
      `https://cdnjs.cloudflare.com/ajax/libs/pdf.js/4.10.38/pdf.worker.min.mjs`;
  }

  ngOnInit() {
    this.determineViewerMode();
    this.loadDocument();
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
    // Office documents
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
            this.error = 'This file type is not supported for preview';
        }
      },
      error: (err) => {
        this.error = 'Failed to load document';
        this.loading = false;
        this.snackBar.open(this.error, 'Close', { duration: 3000 });
      }
    });
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
      this.error = 'Failed to load PDF';
      this.loading = false;
      this.snackBar.open(this.error, 'Close', { duration: 3000 });
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
      this.error = 'Failed to load text file';
      this.loading = false;
      this.snackBar.open(this.error, 'Close', { duration: 3000 });
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
        this.error = 'Office document type not supported for preview';
        this.loading = false;
      }
    } catch (err) {
      this.error = 'Failed to load office document';
      this.loading = false;
      this.snackBar.open(this.error, 'Close', { duration: 3000 });
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
    }
  }

  print() {
    window.print();
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
