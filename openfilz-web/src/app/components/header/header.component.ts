import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {Component, EventEmitter, Input, Output} from "@angular/core";

@Component({
  selector: 'app-header',
  standalone: true,
  templateUrl: './header.component.html',
  styleUrls: ['./header.component.css'],
  imports: [
    CommonModule,
    FormsModule
  ],
})
export class HeaderComponent {
  @Input() hasSelection = false;
  @Output() search = new EventEmitter<string>();

  searchQuery = '';

  onSearch() {
    this.search.emit(this.searchQuery);
  }
}