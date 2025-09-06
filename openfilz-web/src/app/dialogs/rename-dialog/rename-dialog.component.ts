import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { FormsModule } from '@angular/forms';

export interface RenameDialogData {
  name: string;
  type: 'FILE' | 'FOLDER';
}

@Component({
  selector: 'app-rename-dialog',
  standalone: true,
  templateUrl: './rename-dialog.component.html',
  styleUrls: ['./rename-dialog.component.css'],
  imports: [
    CommonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    FormsModule
  ],
})
export class RenameDialogComponent {
  newName: string;

  constructor(
    public dialogRef: MatDialogRef<RenameDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: RenameDialogData
  ) {
    this.newName = data.name;
  }

  onRename() {
    if (this.newName?.trim() && this.newName.trim() !== this.data.name) {
      this.dialogRef.close(this.newName.trim());
    }
  }
}