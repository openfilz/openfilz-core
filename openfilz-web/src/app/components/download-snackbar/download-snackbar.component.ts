import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { TranslatePipe } from '@ngx-translate/core';

@Component({
  selector: 'app-download-snackbar',
  templateUrl: './download-snackbar.component.html',
  standalone: true,
  imports: [CommonModule, MatProgressBarModule, TranslatePipe]
})
export class DownloadSnackbarComponent { }
