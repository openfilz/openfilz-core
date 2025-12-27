import { Component, inject } from '@angular/core';

import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

export interface ConfirmReplaceDialogData {
  fileName: string;
  existingDocumentId: string;
}

export interface ConfirmReplaceDialogResult {
  confirmed: boolean;
  existingDocumentId: string;
}

@Component({
  selector: 'app-confirm-replace-dialog',
  standalone: true,
  templateUrl: './confirm-replace-dialog.component.html',
  styleUrls: ['./confirm-replace-dialog.component.css'],
  imports: [
    MatDialogModule,
    MatButtonModule,
    MatIconModule
],
})
export class ConfirmReplaceDialogComponent {
  readonly dialogRef = inject(MatDialogRef<ConfirmReplaceDialogComponent>);
  readonly data = inject<ConfirmReplaceDialogData>(MAT_DIALOG_DATA);

  onReplace() {
    const result: ConfirmReplaceDialogResult = {
      confirmed: true,
      existingDocumentId: this.data.existingDocumentId
    };
    this.dialogRef.close(result);
  }

  onCancel() {
    this.dialogRef.close(null);
  }
}
