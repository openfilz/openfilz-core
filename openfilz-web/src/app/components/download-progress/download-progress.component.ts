import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'app-download-progress',
  standalone: true,
  imports: [CommonModule, MatProgressBarModule, MatCardModule, MatIconModule],
  templateUrl: './download-progress.component.html',
  styleUrls: ['./download-progress.component.css']
})
export class DownloadProgressComponent {
}
