import {inject, Injectable} from '@angular/core';
import {Observable} from 'rxjs';
import {filter, map, shareReplay} from 'rxjs/operators';
import {BookService} from '../../book/service/book.service';
import {Book, ReadStatus} from '../../book/model/book.model';
import {SeriesSummary} from '../model/series.model';
import {groupSeries} from '../../../shared/util/series-grouping';

@Injectable({
  providedIn: 'root'
})
export class SeriesDataService {

  private bookService = inject(BookService);

  allSeries$: Observable<SeriesSummary[]> = this.bookService.bookState$.pipe(
    filter(state => state.loaded && !!state.books),
    map(state => this.buildSeriesSummaries(state.books || [])),
    shareReplay(1)
  );

  private buildSeriesSummaries(books: Book[]): SeriesSummary[] {
    const summaries: SeriesSummary[] = [];

    for (const group of groupSeries(books)) {
      const sorted = group.books;
      const authorSet = new Set<string>();
      const categorySet = new Set<string>();

      for (const book of sorted) {
        book.metadata?.authors?.forEach(a => authorSet.add(a));
        book.metadata?.categories?.forEach(c => categorySet.add(c));
      }

      const readCount = sorted.filter(b => b.readStatus === ReadStatus.READ).length;
      const bookCount = sorted.length;

      const lastReadTime = sorted
        .map(b => b.lastReadTime)
        .filter((t): t is string => !!t)
        .sort((a, b) => new Date(b).getTime() - new Date(a).getTime())[0] || null;

      const addedOn = sorted
        .map(b => b.addedOn)
        .filter((t): t is string => !!t)
        .sort((a, b) => new Date(b).getTime() - new Date(a).getTime())[0] || null;

      const nextUnread = sorted.find(b => b.readStatus !== ReadStatus.READ) || null;

      summaries.push({
        key: group.key,
        name: group.name,
        seriesName: group.displayName,
        books: sorted,
        authors: Array.from(authorSet),
        categories: Array.from(categorySet),
        bookCount,
        readCount,
        progress: bookCount > 0 ? readCount / bookCount : 0,
        seriesStatus: this.computeSeriesStatus(sorted),
        nextUnread,
        lastReadTime,
        coverBooks: sorted.slice(0, 7),
        addedOn
      });
    }

    return summaries;
  }

  private computeSeriesStatus(books: Book[]): ReadStatus {
    if (!books || books.length === 0) return ReadStatus.UNREAD;
    const statuses = books.map(b => (b.readStatus as ReadStatus) ?? ReadStatus.UNREAD);

    if (statuses.includes(ReadStatus.WONT_READ)) return ReadStatus.WONT_READ;
    if (statuses.includes(ReadStatus.ABANDONED)) return ReadStatus.ABANDONED;
    if (statuses.every(s => s === ReadStatus.READ)) return ReadStatus.READ;

    const isAnyReading = statuses.some(
      s => s === ReadStatus.READING || s === ReadStatus.RE_READING || s === ReadStatus.PAUSED
    );
    if (isAnyReading) return ReadStatus.READING;

    const someRead = statuses.some(s => s === ReadStatus.READ);
    if (someRead) return ReadStatus.PARTIALLY_READ;

    if (statuses.every(s => s === ReadStatus.UNREAD)) return ReadStatus.UNREAD;

    return ReadStatus.PARTIALLY_READ;
  }
}
