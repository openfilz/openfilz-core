import { Injectable } from '@angular/core';

@Injectable({
  providedIn: 'root'
})
export class FileIconService {
  getFileIcon(fileName: string, type: 'FILE' | 'FOLDER'): string {
    if (type === 'FOLDER') {
      return 'folder';
    }

    const extension = this.getFileExtension(fileName).toLowerCase();
    
    const iconMap: { [key: string]: string } = {
      // Documents
      'pdf': 'picture_as_pdf',
      'doc': 'description',
      'docx': 'description',
      'txt': 'description',
      'rtf': 'description',
      
      // Spreadsheets
      'xls': 'grid_on',
      'xlsx': 'grid_on',
      'csv': 'grid_on',
      
      // Presentations
      'ppt': 'slideshow',
      'pptx': 'slideshow',
      
      // Images
      'jpg': 'image',
      'jpeg': 'image',
      'png': 'image',
      'gif': 'image',
      'bmp': 'image',
      'svg': 'image',
      'webp': 'image',
      
      // Videos
      'mp4': 'movie',
      'avi': 'movie',
      'mov': 'movie',
      'wmv': 'movie',
      'flv': 'movie',
      'webm': 'movie',
      
      // Audio
      'mp3': 'audiotrack',
      'wav': 'audiotrack',
      'flac': 'audiotrack',
      'ogg': 'audiotrack',
      'm4a': 'audiotrack',
      
      // Archives
      'zip': 'archive',
      'rar': 'archive',
      '7z': 'archive',
      'tar': 'archive',
      'gz': 'archive',
      
      // Code
      'html': 'code',
      'css': 'code',
      'js': 'code',
      'ts': 'code',
      'json': 'code',
      'xml': 'code',
      'sql': 'code',
      'py': 'code',
      'java': 'code',
      'cpp': 'code',
      'c': 'code',
      'php': 'code',
      'rb': 'code',
      'go': 'code',
      'rs': 'code',
    };

    return iconMap[extension] || 'insert_drive_file';
  }

  private getFileExtension(fileName: string): string {
    const lastDotIndex = fileName.lastIndexOf('.');
    return lastDotIndex !== -1 ? fileName.substring(lastDotIndex + 1) : '';
  }

  getFileSize(bytes: number): string {
    if (bytes === 0) return '0 B';
    
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(1024));
    
    return Math.round(bytes / Math.pow(1024, i) * 100) / 100 + ' ' + sizes[i];
  }
}