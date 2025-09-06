import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatDialogRef, MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { FormsModule } from '@angular/forms';

export interface SettingsDialogData {
  itemsPerPage: number;
}

@Component({
  selector: 'app-settings-dialog',
  standalone: true,
  imports: [
    CommonModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatSelectModule,
    FormsModule
  ],
  templateUrl: './settings-dialog.component.html',
  styleUrls: ['./settings-dialog.component.css']
})
export class SettingsDialogComponent {
  selectedItemsPerPage: number;
  itemsPerPageOptions: number[] = [10, 20, 50, 70, 100];

  constructor(
    public dialogRef: MatDialogRef<SettingsDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: SettingsDialogData
  ) {
    this.selectedItemsPerPage = data.itemsPerPage;
  }

  onCancel(): void {
    this.dialogRef.close();
  }

  onSave(): void {
    localStorage.setItem('itemsPerPage', this.selectedItemsPerPage.toString());
    this.dialogRef.close(this.selectedItemsPerPage);
  }
}
