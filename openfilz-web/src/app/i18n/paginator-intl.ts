import { inject, Injectable } from '@angular/core';
import { MatPaginatorIntl } from '@angular/material/paginator';
import { TranslateService } from '@ngx-translate/core';

@Injectable()
export class PaginatorIntlService extends MatPaginatorIntl {
    private translate = inject(TranslateService);

    override itemsPerPageLabel = '';
    override nextPageLabel = '';
    override previousPageLabel = '';
    override firstPageLabel = '';
    override lastPageLabel = '';

    constructor() {
        super();
        this.setupLabels();
        this.translate.onLangChange.subscribe(() => {
            this.setupLabels();
        });
    }

    private setupLabels() {
        this.itemsPerPageLabel = this.translate.instant('toolbar.itemsPerPage');
        this.nextPageLabel = this.translate.instant('toolbar.nextPage');
        this.previousPageLabel = this.translate.instant('toolbar.previousPage');
        this.firstPageLabel = this.translate.instant('toolbar.firstPage');
        this.lastPageLabel = this.translate.instant('toolbar.lastPage');
        this.changes.next();
    }

    override getRangeLabel = (page: number, pageSize: number, length: number): string => {
        if (length === 0 || pageSize === 0) {
            return `0 ${this.translate.instant('toolbar.of')} ${length}`;
        }
        length = Math.max(length, 0);
        const startIndex = page * pageSize;
        const endIndex = startIndex < length ? Math.min(startIndex + pageSize, length) : startIndex + pageSize;
        return `${startIndex + 1} - ${endIndex} ${this.translate.instant('toolbar.of')} ${length}`;
    };
}

export function providePaginatorIntl() {
    return {
        provide: MatPaginatorIntl,
        useClass: PaginatorIntlService
    };
}
