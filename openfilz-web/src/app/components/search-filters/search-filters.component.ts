import { Component, EventEmitter, Input, OnInit, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { DocumentType, SearchFilters } from '../../models/document.models';

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
    fileType: 'any',
    metadata: []
  };

  metadataFilters: { key: string; value: string }[] = [];

  ngOnInit() {
    if (this.initialFilters) {
      this.filters = { ...this.initialFilters };
      if (this.initialFilters.metadata) {
        this.metadataFilters = [...this.initialFilters.metadata];
      }
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

  addMetadataFilter() {
    this.metadataFilters.push({ key: '', value: '' });
  }

  removeMetadataFilter(index: number) {
    this.metadataFilters.splice(index, 1);
  }

  applyFilters() {
    // Filter out empty keys
    const validMetadata = this.metadataFilters.filter(m => m.key.trim() !== '');
    this.filters.metadata = validMetadata;
    this.filtersChanged.emit(this.filters);
    this.close.emit();
  }

  clearFilters() {
    this.filters = {
      type: undefined,
      dateModified: 'any',
      owner: '',
      fileType: 'any',
      metadata: []
    };
    this.metadataFilters = [];
    this.filtersChanged.emit(this.filters);
  }
}
