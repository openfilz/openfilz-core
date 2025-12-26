import { AfterViewInit, Component, ElementRef, ViewChild, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { FormsModule } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';

@Component({
  selector: 'app-create-folder-dialog',
  standalone: true,
  templateUrl: './create-folder-dialog.component.html',
  styleUrls: ['./create-folder-dialog.component.css'],
  imports: [
    CommonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    FormsModule,
    TranslatePipe
  ],
})
export class CreateFolderDialogComponent implements AfterViewInit {
  folderName = '';

  @ViewChild('nameInput') nameInput!: ElementRef;

  readonly dialogRef = inject(MatDialogRef<CreateFolderDialogComponent>);
  readonly data = inject(MAT_DIALOG_DATA);

  constructor() { }

  ngAfterViewInit() {
    // Auto-focus the input field
    setTimeout(() => {
      this.nameInput?.nativeElement?.focus();
    }, 100);
  }

  onCreate() {
    if (this.folderName?.trim()) {
      this.dialogRef.close(this.folderName.trim());
    }
  }

  onCancel() {
    this.dialogRef.close();
  }
}