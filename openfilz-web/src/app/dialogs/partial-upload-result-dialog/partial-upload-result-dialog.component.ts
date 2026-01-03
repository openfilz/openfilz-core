import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatExpansionModule } from '@angular/material/expansion';
import { TranslatePipe } from '@ngx-translate/core';
import { PartialUploadResult, UploadErrorGroup, UploadResponse } from '../../models/document.models';

export interface PartialUploadResultDialogData {
  result: PartialUploadResult;
}

@Component({
  selector: 'app-partial-upload-result-dialog',
  standalone: true,
  templateUrl: './partial-upload-result-dialog.component.html',
  styleUrls: ['./partial-upload-result-dialog.component.css'],
  imports: [
    CommonModule,
    MatDialogModule,
    MatButtonModule,
    MatIconModule,
    MatExpansionModule,
    TranslatePipe
  ]
})
export class PartialUploadResultDialogComponent {
  readonly dialogRef = inject(MatDialogRef<PartialUploadResultDialogComponent>);
  readonly data = inject<PartialUploadResultDialogData>(MAT_DIALOG_DATA);

  expandedErrorType: string | null = null;

  get result(): PartialUploadResult {
    return this.data.result;
  }

  get successCount(): number {
    return this.result.successCount;
  }

  get errorCount(): number {
    return this.result.errorCount;
  }

  get errorGroups(): UploadErrorGroup[] {
    return this.result.errorGroups;
  }

  getErrorTypeLabel(errorType: string): string {
    // Map backend error types to user-friendly labels
    const labels: { [key: string]: string } = {
      'DocumentNotFound': 'dialogs.partialUpload.errorTypes.documentNotFound',
      'DuplicateName': 'dialogs.partialUpload.errorTypes.duplicateName',
      'OperationForbidden': 'dialogs.partialUpload.errorTypes.operationForbidden',
      'Unknown': 'dialogs.partialUpload.errorTypes.unknown'
    };
    return labels[errorType] || errorType;
  }

  getErrorTypeIcon(errorType: string): string {
    const icons: { [key: string]: string } = {
      'DocumentNotFound': 'folder_off',
      'DuplicateName': 'file_copy',
      'OperationForbidden': 'block',
      'Unknown': 'error'
    };
    return icons[errorType] || 'error';
  }

  toggleErrorDetails(errorType: string): void {
    if (this.expandedErrorType === errorType) {
      this.expandedErrorType = null;
    } else {
      this.expandedErrorType = errorType;
    }
  }

  isExpanded(errorType: string): boolean {
    return this.expandedErrorType === errorType;
  }

  onClose(): void {
    this.dialogRef.close();
  }
}
