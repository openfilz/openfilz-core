import { Component } from '@angular/core';

import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { TranslatePipe } from '@ngx-translate/core';

@Component({
  selector: 'app-download-progress',
  standalone: true,
  imports: [MatProgressBarModule, MatCardModule, MatIconModule, TranslatePipe],
  templateUrl: './download-progress.component.html',
  styleUrls: ['./download-progress.component.css']
})
export class DownloadProgressComponent {
}
