import { Component, EventEmitter, Input, Output } from '@angular/core';

import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { ElementInfo, Root } from '../../models/document.models';
import { TranslatePipe } from '@ngx-translate/core';

@Component({
  selector: 'app-breadcrumb',
  standalone: true,
  templateUrl: './breadcrumb.component.html',
  styleUrls: ['./breadcrumb.component.css'],
  imports: [MatButtonModule, MatIconModule, TranslatePipe],
})
export class BreadcrumbComponent {
  @Input() breadcrumbs: ElementInfo[] = [];
  @Output() navigate = new EventEmitter<ElementInfo>();

  onNavigate(item?: ElementInfo) {
    this.navigate.emit(item != null ? item : Root.INSTANCE);
  }

  onNavigateToHome() {
    this.navigate.emit(Root.INSTANCE);
  }
}