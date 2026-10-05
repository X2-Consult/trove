import {Component, EventEmitter, inject, Input, Output} from '@angular/core';
import {Paginator, PaginatorState} from 'primeng/paginator';
import {TranslocoDirective} from '@jsverse/transloco';
import {BrowsePaginationService, PAGE_SIZE_OPTIONS} from '../../service/browse-pagination.service';

/** Page numbers and page size for a book grid in Pages mode (see BrowsePaginationService). */
@Component({
  selector: 'app-book-pager',
  standalone: true,
  imports: [Paginator, TranslocoDirective],
  template: `
    <ng-container *transloco="let t; prefix: 'book.pager'">
      <div class="book-pager">
        <p-paginator
          [first]="page * paging.pageSize()"
          [rows]="paging.pageSize()"
          [totalRecords]="totalRecords"
          [rowsPerPageOptions]="pageSizes"
          [showCurrentPageReport]="true"
          [currentPageReportTemplate]="t('report')"
          [pageLinkSize]="5"
          (onPageChange)="onChange($event)">
        </p-paginator>
      </div>
    </ng-container>
  `,
  styles: [`
    .book-pager {
      display: flex;
      justify-content: center;
      padding: 0.75rem 0 1rem;
    }
  `]
})
export class BookPagerComponent {
  readonly paging = inject(BrowsePaginationService);
  readonly pageSizes = PAGE_SIZE_OPTIONS;

  @Input({required: true}) totalRecords = 0;
  @Input({required: true}) page = 0;
  @Output() pageChange = new EventEmitter<number>();

  onChange(event: PaginatorState): void {
    const rows = event.rows ?? this.paging.pageSize();
    if (rows !== this.paging.pageSize()) {
      // A new page size starts again from the first page.
      this.paging.setPageSize(rows);
      this.pageChange.emit(0);
      return;
    }
    this.pageChange.emit(event.page ?? 0);
  }
}
