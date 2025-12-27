import { Component, Input, Output, EventEmitter, OnInit, OnChanges, SimpleChanges, inject } from '@angular/core';

import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { TranslateService, TranslatePipe } from '@ngx-translate/core';

export interface MetadataKeyValue {
  key: string;
  value: string;
  error?: string;
}

@Component({
  selector: 'app-metadata-editor',
  standalone: true,
  templateUrl: './metadata-editor.component.html',
  styleUrls: ['./metadata-editor.component.css'],
  imports: [
    FormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatTooltipModule,
    TranslatePipe
],
})
export class MetadataEditorComponent implements OnInit, OnChanges {
  @Input() metadata: { [key: string]: any } = {};
  @Input() readonly: boolean = false;
  @Input() showTitle: boolean = true;
  @Output() metadataChange = new EventEmitter<{ [key: string]: any }>();
  @Output() valid = new EventEmitter<boolean>();

  metadataEntries: MetadataKeyValue[] = [];
  private isEditing = false;
  private initialMetadataJson = '';
  private translate = inject(TranslateService);

  ngOnInit() {
    this.loadMetadata();
    this.initialMetadataJson = JSON.stringify(this.metadata || {});
  }

  ngOnChanges(changes: SimpleChanges) {
    // Only reload if metadata changed externally (not from our own emit)
    // and we're not currently editing
    if (changes['metadata'] && !this.isEditing) {
      const newJson = JSON.stringify(this.metadata || {});
      if (newJson !== this.initialMetadataJson) {
        this.loadMetadata();
        this.initialMetadataJson = newJson;
      }
    }
    // Always reload when readonly changes (e.g., entering/exiting edit mode)
    if (changes['readonly'] && !changes['readonly'].firstChange) {
      this.loadMetadata();
      this.initialMetadataJson = JSON.stringify(this.metadata || {});
      this.isEditing = false;
    }
  }

  private loadMetadata() {
    if (this.metadata && Object.keys(this.metadata).length > 0) {
      this.metadataEntries = Object.entries(this.metadata).map(([key, value]) => ({
        key,
        value: String(value),
        error: ''
      }));
    } else {
      this.metadataEntries = [];
    }

    // Ensure at least one empty entry for new metadata
    if (this.metadataEntries.length === 0 && !this.readonly) {
      this.addMetadataEntry();
    }
  }

  addMetadataEntry() {
    this.metadataEntries.push({ key: '', value: '', error: '' });
  }

  removeMetadataEntry(index: number) {
    this.metadataEntries.splice(index, 1);
    this.validateAndEmit();
  }

  onMetadataChange() {
    this.isEditing = true;
    this.validateAndEmit();
  }

  private validateAndEmit() {
    let isValid = true;
    const result: { [key: string]: any } = {};

    // Clear previous errors
    this.metadataEntries.forEach(entry => entry.error = '');

    // Validate each entry
    for (let i = 0; i < this.metadataEntries.length; i++) {
      const entry = this.metadataEntries[i];

      // Skip empty entries
      if (!entry.key && !entry.value) {
        continue;
      }

      // Validate key
      if (!entry.key) {
        entry.error = this.translate.instant('metadataEditor.errors.keyRequired');
        isValid = false;
        continue;
      }

      // Check for duplicate keys
      const duplicates = this.metadataEntries.filter((e, idx) =>
        idx !== i && e.key && e.key.toLowerCase() === entry.key.toLowerCase()
      );
      if (duplicates.length > 0) {
        entry.error = this.translate.instant('metadataEditor.errors.duplicateKey');
        isValid = false;
        continue;
      }

      // Validate key format (alphanumeric, underscore, hyphen)
      if (!/^[a-zA-Z0-9_-]+$/.test(entry.key)) {
        entry.error = this.translate.instant('metadataEditor.errors.invalidKeyFormat');
        isValid = false;
        continue;
      }

      // Add to result if valid
      if (entry.key && entry.value) {
        result[entry.key] = entry.value;
      }
    }

    this.valid.emit(isValid);
    this.metadataChange.emit(result);
  }

  getMetadata(): { [key: string]: any } {
    const result: { [key: string]: any } = {};
    this.metadataEntries.forEach(entry => {
      if (entry.key && entry.value) {
        result[entry.key] = entry.value;
      }
    });
    return result;
  }

  hasMetadata(): boolean {
    return this.metadataEntries.some(entry => entry.key && entry.value);
  }

  clearAll() {
    this.metadataEntries = [{ key: '', value: '', error: '' }];
    this.validateAndEmit();
  }
}
