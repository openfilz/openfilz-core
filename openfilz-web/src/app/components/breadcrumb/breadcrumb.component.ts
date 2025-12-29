import { Component, EventEmitter, Input, Output } from '@angular/core';

import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { ElementInfo, Root } from '../../models/document.models';
import { TranslatePipe } from '@ngx-translate/core';
import { FolderDropZoneDirective } from '../../directives/folder-drop-zone.directive';
import { DropEvent } from '../../services/drag-drop.service';

@Component({
  selector: 'app-breadcrumb',
  standalone: true,
  templateUrl: './breadcrumb.component.html',
  styleUrls: ['./breadcrumb.component.css'],
  imports: [MatButtonModule, MatIconModule, TranslatePipe, FolderDropZoneDirective],
})
export class BreadcrumbComponent {
  @Input() breadcrumbs: ElementInfo[] = [];
  @Input() currentFolderId: string | null = null;
  @Output() navigate = new EventEmitter<ElementInfo>();
  @Output() itemsDropped = new EventEmitter<DropEvent>();

  onNavigate(item?: ElementInfo) {
    this.navigate.emit(item != null ? item : Root.INSTANCE);
  }

  onNavigateToHome() {
    this.navigate.emit(Root.INSTANCE);
  }

  onItemsDropped(event: DropEvent) {
    this.itemsDropped.emit(event);
  }
}