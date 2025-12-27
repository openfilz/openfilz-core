import { Component, inject } from '@angular/core';

import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { TranslatePipe } from '@ngx-translate/core';

export type ConfirmDialogType = 'danger' | 'warning' | 'info';

export interface ConfirmDialogData {
  title: string;
  message: string;
  messageParams?: { [key: string]: any };
  details?: string;
  type?: ConfirmDialogType;
  confirmText?: string;
  cancelText?: string;
  icon?: string;
}

@Component({
  selector: 'app-confirm-dialog',
  standalone: true,
  templateUrl: './confirm-dialog.component.html',
  styleUrls: ['./confirm-dialog.component.css'],
  imports: [
    MatDialogModule,
    MatButtonModule,
    MatIconModule,
    TranslatePipe
],
})
export class ConfirmDialogComponent {
  readonly dialogRef = inject(MatDialogRef<ConfirmDialogComponent>);
  readonly data = inject<ConfirmDialogData>(MAT_DIALOG_DATA);

  get dialogType(): ConfirmDialogType {
    return this.data.type || 'warning';
  }

  get icon(): string {
    if (this.data.icon) return this.data.icon;
    switch (this.dialogType) {
      case 'danger': return 'delete_forever';
      case 'warning': return 'warning';
      case 'info': return 'info';
      default: return 'help';
    }
  }

  get confirmText(): string {
    return this.data.confirmText || 'common.confirm';
  }

  get cancelText(): string {
    return this.data.cancelText || 'common.cancel';
  }

  onConfirm() {
    this.dialogRef.close(true);
  }

  onCancel() {
    this.dialogRef.close(false);
  }
}
