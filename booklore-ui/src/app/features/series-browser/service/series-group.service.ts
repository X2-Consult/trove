import {inject, Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Observable, tap} from 'rxjs';
import {map} from 'rxjs/operators';
import {API_CONFIG} from '../../../core/config/api-config';
import {BookStateService} from '../../book/service/book-state.service';

/**
 * Split / Merge by hand for same-name series the series IDs and authors can't tell apart
 * (series-grouping.ts). Each call updates the books in the loaded state, so every view regroups.
 */
@Injectable({providedIn: 'root'})
export class SeriesGroupService {

  private readonly url = `${API_CONFIG.BASE_URL}/api/v1/series`;
  private http = inject(HttpClient);
  private bookStateService = inject(BookStateService);

  /** Makes these books, which share a series name, one series of their own. Returns its key. */
  groupTogether(bookIds: number[]): Observable<string> {
    return this.http.post<{ seriesGroup: string }>(`${this.url}/group`, {bookIds}).pipe(
      tap(res => this.patch(bookIds, res.seriesGroup)),
      map(res => `manual:${res.seriesGroup}`)
    );
  }

  /** Lets these books' series be worked out from their IDs and authors again. */
  ungroup(bookIds: number[]): Observable<void> {
    return this.http.post<void>(`${this.url}/ungroup`, {bookIds}).pipe(
      tap(() => this.patch(bookIds, null))
    );
  }

  private patch(bookIds: number[], seriesGroup: string | null): void {
    const state = this.bookStateService.getCurrentBookState();
    if (!state.books) return;
    const ids = new Set(bookIds);
    this.bookStateService.updateBookState({
      ...state,
      books: state.books.map(b => ids.has(b.id) && b.metadata ? {...b, metadata: {...b.metadata, seriesGroup}} : b)
    });
  }
}
