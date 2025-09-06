import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import {DocumentType, ElementInfo, Root} from '../../models/document.models';

@Component({
  selector: 'app-breadcrumb',
  standalone: true,
  templateUrl: './breadcrumb.component.html',
  styleUrls: ['./breadcrumb.component.css'],
  imports: [CommonModule, MatButtonModule, MatIconModule],
})
export class BreadcrumbComponent {
  @Input() breadcrumbs: ElementInfo[] = [];
  @Output() navigate = new EventEmitter<ElementInfo>();

  onNavigate(item?: ElementInfo) {
    this.navigate.emit(item != null ? item : Root.INSTANCE);
  }
}