import { AfterViewInit, Component, ElementRef, ViewChild, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from "@angular/material/icon";
import { TranslatePipe } from '@ngx-translate/core';

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
    FormsModule,
    MatIconModule,
    TranslatePipe
  ],
})
export class RenameDialogComponent implements AfterViewInit {
  newName: string;

  @ViewChild('nameInput') nameInput!: ElementRef;

  readonly dialogRef = inject(MatDialogRef<RenameDialogComponent>);
  readonly data = inject<RenameDialogData>(MAT_DIALOG_DATA);

  constructor() {
    this.newName = this.data.name;
  }

  ngAfterViewInit() {
    setTimeout(() => {
      this.nameInput?.nativeElement?.focus();
    }, 100);
  }

  onRename() {
    if (this.newName?.trim() && this.newName.trim() !== this.data.name) {
      this.dialogRef.close(this.newName.trim());
    }
  }

  onCancel() {
    this.dialogRef.close();
  }
}