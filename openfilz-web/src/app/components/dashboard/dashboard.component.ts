import { Component, OnInit, ViewChild, ElementRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatSnackBar } from '@angular/material/snack-bar';
import { DragDropDirective } from '../../directives/drag-drop.directive';
import { DocumentApiService } from '../../services/document-api.service';
import { FileIconService } from '../../services/file-icon.service';
import { ElementInfo, DocumentType, FileItem } from '../../models/document.models';

export interface DashboardFileItem extends FileItem {
  owner: string;
  lastModified: string;
}

export interface RecentFile {
  id: string;
  name: string;
  type: string;
  size: number;
  owner: string;
  lastModified: string;
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
    DragDropDirective
  ],
  templateUrl: './dashboard.component.html',
  styleUrls: ['./dashboard.component.css']
})
export class DashboardComponent implements OnInit {
  recentlyEditedFiles: RecentFile[] = [];
  allFiles: DashboardFileItem[] = [];
  displayedColumns: string[] = ['name', 'size', 'owner', 'lastModified'];
  pageSize = 10;
  pageIndex = 0;
  totalItems = 0;
  fileOver = false;
  fileTypeDistribution: FileTypeDistribution[] = [];

  // Storage stats
  storageUsed = 15678901234; // ~14.6 GB
  storageTotal = 53687091200; // 50 GB
  documentsSize = 5234567890;
  imagesSize = 7345678901;
  videosSize = 2098765432;
  otherSize = 1000000011;

  get storagePercentage(): number {
    return Math.round((this.storageUsed / this.storageTotal) * 100);
  }

  @ViewChild('fileInput') fileInput!: ElementRef;

  constructor(
    private documentApi: DocumentApiService,
    private fileIconService: FileIconService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit() {
    this.loadRecentlyEditedFiles();
    this.loadFiles();
    this.calculateFileTypeDistribution();
    // In a real app, load storage stats from API
  }

  loadRecentlyEditedFiles() {
    // For now, we'll generate mock data - in a real app this would come from an API
    this.recentlyEditedFiles = [
      {
        id: '1',
        name: 'Banking Dashboard.png',
        type: 'image',
        size: 2456789,
        owner: 'You',
        lastModified: '2m ago',
        updatedAt: new Date(Date.now() - 2 * 60000).toISOString(),
        icon: 'image'
      },
      {
        id: '2',
        name: 'Team Project.xlsx',
        type: 'document',
        size: 3456789,
        owner: 'You',
        lastModified: '10m ago',
        updatedAt: new Date(Date.now() - 10 * 60000).toISOString(),
        icon: 'document'
      },
      {
        id: '3',
        name: 'Task Briefing.docx',
        type: 'document',
        size: 1234567,
        owner: 'You',
        lastModified: '12m ago',
        updatedAt: new Date(Date.now() - 12 * 60000).toISOString(),
        icon: 'document'
      },
      {
        id: '4',
        name: 'Project Proposal.pdf',
        type: 'document',
        size: 3456789,
        owner: 'You',
        lastModified: '1h ago',
        updatedAt: new Date(Date.now() - 60 * 60000).toISOString(),
        icon: 'document'
      },
      {
        id: '5',
        name: 'Design Assets.zip',
        type: 'archive',
        size: 15678901,
        owner: 'You',
        lastModified: '2h ago',
        updatedAt: new Date(Date.now() - 2 * 60 * 60000).toISOString(),
        icon: 'archive'
      }
    ];
  }

  loadFiles() {
    // Load files only (no folders) with pagination
    this.documentApi.listFolder(undefined, this.pageIndex + 1, this.pageSize).subscribe({
      next: (response: ElementInfo[]) => {
        // Filter to show only files, not folders
        const files = response.filter(item => item.type === DocumentType.FILE);
        
        this.allFiles = files.map(item => ({
          ...item,
          type: DocumentType.FILE,
          selected: false,
          size: 0, // Size is not available in ElementInfo, would need additional API call
          modifiedDate: undefined,
          icon: this.fileIconService.getFileIcon(item.name, DocumentType.FILE),
          // Add properties expected by template
          owner: 'You', // In a real app, this would come from the API
          lastModified: 'Just now' // In a real app, format actual date
        }));
        
        // Set total items - in a real app, this would come from a count API
        this.totalItems = files.length;
        // Calculate file type distribution after loading files
        this.calculateFileTypeDistribution();
      },
      error: (error) => {
        console.error('Error loading files:', error);
        this.snackBar.open('Failed to load files', 'Close', { duration: 3000 });
      }
    });
  }

  calculateFileTypeDistribution() {
    // Define file types and their extensions
    const fileTypes: { [key: string]: string[] } = {
      'Documents': ['.pdf', '.doc', '.docx', '.xls', '.xlsx', '.ppt', '.pptx', '.txt', '.rtf'],
      'Images': ['.jpg', '.jpeg', '.png', '.gif', '.bmp', '.svg', '.webp', '.tiff'],
      'Videos': ['.mp4', '.avi', '.mov', '.wmv', '.flv', '.mkv', '.webm', '.m4v'],
      'Music': ['.mp3', '.wav', '.flac', '.aac', '.ogg', '.m4a', '.wma'],
      'Archives': ['.zip', '.rar', '.7z', '.tar', '.gz', '.bz2', '.xz'],
      'Others': []
    };

    // Count each file type based on extension
    const typeCounts: { [key: string]: number } = {
      'Documents': 0,
      'Images': 0,
      'Videos': 0,
      'Music': 0,
      'Archives': 0,
      'Others': 0
    };

    for (const file of this.allFiles) {
      const fileExtension = this.getFileExtension(file.name).toLowerCase();
      let matched = false;

      for (const [type, extensions] of Object.entries(fileTypes)) {
        if (extensions.includes(fileExtension)) {
          typeCounts[type]++;
          matched = true;
          break;
        }
      }

      if (!matched) {
        typeCounts['Others']++;
      }
    }

    // Calculate percentages and set up the distribution array
    const totalFiles = this.allFiles.length;
    if (totalFiles === 0) {
      // If no files, show some mock data for demonstration
      this.fileTypeDistribution = [
        { type: 'Documents', count: 35, percentage: 35, color: '#667eea' },
        { type: 'Images', count: 25, percentage: 25, color: '#f093fb' },
        { type: 'Videos', count: 20, percentage: 20, color: '#4facfe' },
        { type: 'Music', count: 5, percentage: 5, color: '#f5576c' },
        { type: 'Archives', count: 10, percentage: 10, color: '#00f2fe' },
        { type: 'Others', count: 5, percentage: 5, color: '#764ba2' }
      ];
      return;
    }

    // Calculate percentage for each type
    this.fileTypeDistribution = Object.entries(typeCounts).map(([type, count]) => {
      const percentage = Math.round((count / totalFiles) * 100);
      const color = this.getTypeColor(type);
      return { type, count, percentage, color };
    });
  }

  getFileExtension(filename: string): string {
    const lastDotIndex = filename.lastIndexOf('.');
    return lastDotIndex === -1 ? '' : filename.substring(lastDotIndex);
  }

  getTypeColor(type: string): string {
    const colorMap: { [key: string]: string } = {
      'Documents': '#667eea',
      'Images': '#f093fb',
      'Videos': '#4facfe',
      'Music': '#f5576c',
      'Archives': '#00f2fe',
      'Others': '#764ba2'
    };
    return colorMap[type] || '#999999';
  }

  onPageChange(event: PageEvent) {
    this.pageIndex = event.pageIndex;
    this.pageSize = event.pageSize;
    this.loadFiles();
  }

  onFilesDropped(files: FileList) {
    // Dashboard no longer handles uploads - redirect to file explorer
    this.snackBar.open('Please use the File Explorer to upload files', 'Close', { duration: 3000 });
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
        return 'fas fa-chart-pie';
      case 'document':
        return 'fas fa-file-word'; // or appropriate document icon
      case 'archive':
        return 'fas fa-file-archive';
      default:
        return 'fas fa-file';
    }
  }

  getFileCategory(fileType: string): string {
    switch (fileType) {
      case 'image':
        return 'Image';
      case 'document':
        return 'Document';
      case 'archive':
        return 'Archive';
      default:
        return 'File';
    }
  }

  private formatDate(dateString: string): string {
    // Format the date to match the design (e.g., "Jun 12")
    const date = new Date(dateString);
    return date.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
  }
}