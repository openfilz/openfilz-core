import { Component, EventEmitter, Input, OnInit, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { DocumentType } from '../../models/document.models';

export interface SearchFilters {
  type?: DocumentType;
  dateModified?: string;
  owner?: string;
  fileType?: string;
}

@Component({
  selector: 'app-search-filters',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './search-filters.component.html',
  styleUrls: ['./search-filters.component.css']
})
export class SearchFiltersComponent implements OnInit {
  @Input() initialFilters?: SearchFilters;
  @Output() filtersChanged = new EventEmitter<SearchFilters>();
  @Output() close = new EventEmitter<void>();

  filters: SearchFilters = {
    type: undefined,
    dateModified: 'any',
    owner: '',
    fileType: 'any'
  };

  ngOnInit() {
    if (this.initialFilters) {
      this.filters = { ...this.initialFilters };
    }
  }

  get showFileTypeFilter(): boolean {
    return this.filters.type !== DocumentType.FOLDER;
  }

  documentTypes = [
    { label: 'All', value: undefined },
    { label: 'Folders', value: DocumentType.FOLDER },
    { label: 'Files', value: DocumentType.FILE }
  ];

  dateOptions = [
    { label: 'Any time', value: 'any' },
    { label: 'Today', value: 'today' },
    { label: 'Last 7 days', value: 'last7' },
    { label: 'Last 30 days', value: 'last30' }
  ];

  fileTypeOptions = [
    { label: 'Any', value: 'any' },
    { label: 'PDFs', value: 'application/pdf' },
    { label: 'Images', value: 'image/' },
    { label: 'Documents', value: 'application/msword' }, // Simplified
    { label: 'Spreadsheets', value: 'application/vnd.ms-excel' } // Simplified
  ];

  applyFilters() {
    this.filtersChanged.emit(this.filters);
    this.close.emit();
  }

  clearFilters() {
    this.filters = {
      type: undefined,
      dateModified: 'any',
      owner: '',
      fileType: 'any'
    };
    this.filtersChanged.emit(this.filters);
  }
}
