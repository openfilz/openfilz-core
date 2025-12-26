import { Component, ElementRef, OnInit, ViewChild, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { DragDropDirective } from '../../directives/drag-drop.directive';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { DocumentApiService } from '../../services/document-api.service';
import { FileIconService } from '../../services/file-icon.service';
import { DocumentType, ElementInfo, FileItem } from '../../models/document.models';

export interface DashboardFileItem extends FileItem {
  owner: string;
  lastModified: string;
}

export interface RecentFile {
  id: string;
  name: string;
  type: string;
  size: number;
  lastModified?: string;
  updatedAt: string;
  icon: string;
}

export interface FileTypeDistribution {
  type: string;
  count: number;
  percentage: number;
  color: string;
}

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [
    CommonModule,
    MatIconModule,
    MatButtonModule,
    MatTableModule,
    MatPaginatorModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
    DragDropDirective,
    TranslatePipe
  ],
  templateUrl: './dashboard.component.html',
  styleUrls: ['./dashboard.component.css']
})
export class DashboardComponent implements OnInit {
  private documentApi = inject(DocumentApiService);
  private fileIconService = inject(FileIconService);
  private snackBar = inject(MatSnackBar);
  private translate = inject(TranslateService);

  recentlyEditedFiles: RecentFile[] = [];
  allFiles: DashboardFileItem[] = [];
  displayedColumns: string[] = ['name', 'size', 'owner', 'lastModified'];
  pageSize = 10;
  pageIndex = 0;
  totalItems = 0;
  fileOver = false;
  fileTypeDistribution: FileTypeDistribution[] = [];

  // Configurable limit for recent files
  recentFilesLimit = 5;

  // Storage stats
  storageUsed = 0;
  storageTotal = 0;
  documentsSize = 0;
  imagesSize = 0;
  videosSize = 0;
  audioSize = 0;
  otherSize = 0;

  // File counts
  totalFiles = 0;
  totalFolders = 0;

  // Loading states
  isLoadingDashboard = false;
  isLoadingRecentFiles = false;
  dashboardError: string | null = null;

  get storagePercentage(): number {
    if (this.storageTotal === 0) return 0;
    return Math.round((this.storageUsed / this.storageTotal) * 100);
  }

  @ViewChild('fileInput') fileInput!: ElementRef;

  constructor() { }

  ngOnInit() {
    this.loadDashboardData();
  }

  loadDashboardData() {
    this.isLoadingDashboard = true;
    this.dashboardError = null;

    // Load dashboard statistics
    this.documentApi.getDashboardStatistics().subscribe({
      next: (stats) => {
        // Update storage stats
        this.storageUsed = stats.storage.totalStorageUsed;
        this.storageTotal = stats.storage.totalStorageAvailable || 0;

        // Update storage breakdown
        stats.storage.fileTypeBreakdown.forEach(breakdown => {
          switch (breakdown.type) {
            case 'documents':
              this.documentsSize = breakdown.totalSize || 0;
              break;
            case 'images':
              this.imagesSize = breakdown.totalSize || 0;
              break;
            case 'videos':
              this.videosSize = breakdown.totalSize || 0;
              break;
            case 'audio':
              this.audioSize = breakdown.totalSize || 0;
              break;
            case 'others':
              this.otherSize = breakdown.totalSize || 0;
              break;
          }
        });

        // Update file type distribution
        const totalFilesCount = stats.fileTypeCounts.reduce((sum, ft) => sum + (ft.count || 0), 0);
        this.fileTypeDistribution = stats.fileTypeCounts.map(ft => ({
          type: ft.type.toLowerCase(), // Keep lowercase for translation keys
          count: ft.count || 0,
          percentage: totalFilesCount > 0 ? Math.round(((ft.count || 0) / totalFilesCount) * 100) : 0,
          color: this.getTypeColor(ft.type.toLowerCase())
        }));

        // Update file and folder counts
        this.totalFiles = stats.totalFiles || 0;
        this.totalFolders = stats.totalFolders || 0;

        this.isLoadingDashboard = false;
      },
      error: (error) => {
        console.error('Error loading dashboard statistics:', error);
        this.dashboardError = this.translate.instant('dashboard.error');
        this.isLoadingDashboard = false;
        this.snackBar.open(this.translate.instant('dashboard.error'), this.translate.instant('common.close'), { duration: 3000 });
      }
    });

    // Load recently edited files
    this.loadRecentlyEditedFiles();
  }

  loadRecentlyEditedFiles() {
    this.isLoadingRecentFiles = true;

    this.documentApi.getRecentlyEditedFiles(this.recentFilesLimit).subscribe({
      next: (files) => {
        this.recentlyEditedFiles = files.map(file => ({
          id: file.id,
          name: file.name,
          type: this.getFileTypeCategory(file.contentType || ''),
          size: file.size || 0,
          updatedAt: file.updatedAt || '',
          icon: this.getIconForContentType(file.contentType || '')
        }));
        this.isLoadingRecentFiles = false;
      },
      error: (error) => {
        console.error('Error loading recently edited files:', error);
        this.isLoadingRecentFiles = false;
        this.snackBar.open(this.translate.instant('dashboard.loadingRecentError'), this.translate.instant('common.close'), { duration: 3000 });
      }
    });
  }

  private capitalizeFirst(str: string): string {
    return str.charAt(0).toUpperCase() + str.slice(1);
  }

  private getFileTypeCategory(contentType: string): string {
    if (contentType.startsWith('image/')) return 'image';
    if (contentType.startsWith('video/')) return 'video';
    if (contentType.startsWith('audio/')) return 'music';
    if (contentType.includes('zip') || contentType.includes('archive')) return 'archive';
    if (contentType.startsWith('application/')) return 'document';
    return 'file';
  }

  private getIconForContentType(contentType: string): string {
    if (contentType.startsWith('image/')) return 'image';
    if (contentType.startsWith('video/')) return 'video';
    if (contentType.startsWith('audio/')) return 'audiotrack';
    if (contentType.includes('zip') || contentType.includes('archive')) return 'archive';
    return 'description';
  }

  formatRelativeTime(dateString: string): string {
    if (!dateString) return 'Unknown';

    const now = new Date();
    const date = new Date(dateString);
    const diffMs = now.getTime() - date.getTime();
    const diffMins = Math.floor(diffMs / 60000);
    const diffHours = Math.floor(diffMs / 3600000);
    const diffDays = Math.floor(diffMs / 86400000);

    if (diffMins < 1) return this.translate.instant('audit.time.justNow');
    if (diffMins < 60) return this.translate.instant('audit.time.minAgo', { count: diffMins });
    if (diffHours < 24) return this.translate.instant('audit.time.hourAgo', { count: diffHours });
    if (diffDays < 7) return this.translate.instant('audit.time.dayAgo', { count: diffDays });

    // Fallback to localized date
    return date.toLocaleDateString(this.translate.currentLang || 'en-US', { month: 'short', day: 'numeric' });
  }


  getFileExtension(filename: string): string {
    const lastDotIndex = filename.lastIndexOf('.');
    return lastDotIndex === -1 ? '' : filename.substring(lastDotIndex);
  }

  getTypeColor(type: string): string {
    const colorMap: { [key: string]: string } = {
      'documents': '#667eea',
      'images': '#f093fb',
      'videos': '#4facfe',
      'music': '#f5576c',
      'audio': '#f5576c', // Handle both music and audio
      'archives': '#00f2fe',
      'others': '#764ba2',
      'other': '#764ba2'
    };
    return colorMap[type.toLowerCase()] || '#999999';
  }

  onPageChange(event: PageEvent) {
    this.pageIndex = event.pageIndex;
    this.pageSize = event.pageSize;
    // Dashboard doesn't have pagination anymore since we're showing aggregated stats
  }

  onFilesDropped(files: FileList) {
    // Dashboard no longer handles uploads - redirect to file explorer
    this.snackBar.open(this.translate.instant('dashboard.uploadInfo'), this.translate.instant('common.close'), { duration: 3000 });
  }

  onFileOverChange(isOver: boolean) {
    this.fileOver = isOver;
  }

  getFileInitial(name: string): string {
    if (!name) return '?';
    return name.charAt(0).toUpperCase();
  }

  formatFileSize(bytes?: number): string {
    if (!bytes || bytes === 0) return '0 Bytes';
    const k = 1024;
    const sizes = ['Bytes', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  }

  getGradientBackground(fileType: string): string {
    switch (fileType) {
      case 'image':
        return 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)';
      case 'document':
        return 'linear-gradient(135deg, #f093fb 0%, #f5576c 100%)';
      case 'archive':
        return 'linear-gradient(135deg, #4facfe 0%, #00f2fe 100%)';
      default:
        return 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)';
    }
  }

  getFileIconClass(fileType: string): string {
    switch (fileType) {
      case 'image':
        return 'image';
      case 'document':
        return 'description';
      case 'archive':
        return 'folder_zip';
      default:
        return 'insert_drive_file';
    }
  }

  getFileCategory(fileType: string): string {
    switch (fileType) {
      case 'image':
        return this.translate.instant('dashboard.images');
      case 'document':
        return this.translate.instant('dashboard.documents');
      case 'archive':
        return this.translate.instant('mimeTypes.zip');
      default:
        return this.translate.instant('mimeTypes.file');
    }
  }

  private formatDate(dateString: string): string {
    // Format the date to match the design (e.g., "Jun 12")
    const date = new Date(dateString);
    return date.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
  }

  // Circular progress methods for storage indicator
  getCircleDashArray(): string {
    const radius = 38;
    const circumference = 2 * Math.PI * radius;
    return `${circumference} ${circumference}`;
  }

  getCircleDashOffset(): number {
    const radius = 38;
    const circumference = 2 * Math.PI * radius;
    const offset = circumference - (this.storagePercentage / 100) * circumference;
    return offset;
  }
}