import { Component } from '@angular/core';
import { MatProgressBarModule } from '@angular/material/progress-bar';

@Component({
  selector: 'app-download-snackbar',
  templateUrl: './download-snackbar.component.html',
  standalone: true,
  imports: [MatProgressBarModule]
})
export class DownloadSnackbarComponent {}
