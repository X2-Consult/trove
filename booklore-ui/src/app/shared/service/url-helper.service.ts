import {inject, Injectable} from '@angular/core';
import {API_CONFIG} from '../../core/config/api-config';
import {AuthService} from './auth.service';
import {BookService} from '../../features/book/service/book.service';
import {CoverGeneratorComponent} from '../components/cover-generator/cover-generator.component';
import {Router} from '@angular/router';
import {Book,BookType} from '../../features/book/model/book.model';
import {BookStateService} from '../../features/book/service/book-state.service';
import {groupSeries} from '../util/series-grouping';

@Injectable({
  providedIn: 'root'
})
export class UrlHelperService {
  private readonly baseUrl = API_CONFIG.BASE_URL;
  private readonly mediaBaseUrl = `${this.baseUrl}/api/v1/media`;
  private authService = inject(AuthService);
  private bookService = inject(BookService);
  private router = inject(Router);
  private bookStateService = inject(BookStateService);
  private seriesKeys: { books: Book[] | null; keys: Map<number, string> } = {books: null, keys: new Map()};

  private getToken(): string | null {
    return this.authService.getInternalAccessToken();
  }

  /** Cover and thumbnail URLs: no token once the media cookie is set, so they stay cacheable. */
  private appendImageAuth(url: string): string {
    return this.authService.isMediaCookieReady() ? url : this.appendToken(url);
  }

  private appendToken(url: string): string {
    const token = this.getToken();
    return token ? `${url}${url.includes('?') ? '&' : '?'}token=${token}` : url;
  }

  getThumbnailUrl(bookId: number, coverUpdatedOn?: string): string {
    if (!coverUpdatedOn) {
      const book = this.bookService.getBookByIdFromState(bookId);
      if (book && book.metadata) {
        const coverGenerator = new CoverGeneratorComponent();
        coverGenerator.title = book.metadata.title || '';
        coverGenerator.author = (book.metadata.authors || []).join(', ');
        return coverGenerator.generateCover();
      }
    }
    let url = `${this.mediaBaseUrl}/book/${bookId}/thumbnail`;
    if (coverUpdatedOn) {
      url += `?${coverUpdatedOn}`;
    }
    return this.appendImageAuth(url);
  }

  getDirectThumbnailUrl(bookId: number, coverUpdatedOn?: string): string {
    let url = `${this.mediaBaseUrl}/book/${bookId}/thumbnail`;
    if (coverUpdatedOn) {
      url += `?${coverUpdatedOn}`;
    }
    return this.appendImageAuth(url);
  }

  getCoverUrl(bookId: number, coverUpdatedOn?: string): string {
    if (!coverUpdatedOn) {
      const book = this.bookService.getBookByIdFromState(bookId);
      if (book && book.metadata) {
        const coverGenerator = new CoverGeneratorComponent();
        coverGenerator.title = book.metadata.title || '';
        coverGenerator.author = (book.metadata.authors || []).join(', ');
        return coverGenerator.generateCover();
      }
    }
    let url = `${this.mediaBaseUrl}/book/${bookId}/cover`;
    if (coverUpdatedOn) {
      url += `?${coverUpdatedOn}`;
    }
    return this.appendImageAuth(url);
  }

  getBackupCoverUrl(bookId: number): string {
    const url = `${this.mediaBaseUrl}/book/${bookId}/backup-cover`;
    return this.appendImageAuth(url);
  }

  getAudiobookCoverUrl(bookId: number, audiobookCoverUpdatedOn?: string): string {
    if (!audiobookCoverUpdatedOn) {
      const book = this.bookService.getBookByIdFromState(bookId);
      if (book && book.metadata) {
        const coverGenerator = new CoverGeneratorComponent();
        coverGenerator.title = book.metadata.title || '';
        coverGenerator.author = (book.metadata.authors || []).join(', ');
        coverGenerator.isSquare = true;
        return coverGenerator.generateCover();
      }
    }
    let url = `${this.mediaBaseUrl}/book/${bookId}/audiobook-cover`;
    if (audiobookCoverUpdatedOn) {
      url += `?${audiobookCoverUpdatedOn}`;
    }
    return this.appendImageAuth(url);
  }

  getAudiobookThumbnailUrl(bookId: number, audiobookCoverUpdatedOn?: string): string {
    if (!audiobookCoverUpdatedOn) {
      const book = this.bookService.getBookByIdFromState(bookId);
      if (book && book.metadata) {
        const coverGenerator = new CoverGeneratorComponent();
        coverGenerator.title = book.metadata.title || '';
        coverGenerator.author = (book.metadata.authors || []).join(', ');
        coverGenerator.isSquare = true;
        return coverGenerator.generateCover();
      }
    }
    let url = `${this.mediaBaseUrl}/book/${bookId}/audiobook-thumbnail`;
    if (audiobookCoverUpdatedOn) {
      url += `?${audiobookCoverUpdatedOn}`;
    }
    return this.appendImageAuth(url);
  }

  getBookdropCoverUrl(bookdropId: number): string {
    const url = `${this.mediaBaseUrl}/bookdrop/${bookdropId}/cover`;
    return this.appendToken(url);
  }

  getBookUrl(book: Book) {
    return this.router.createUrlTree(['/book', book.id], {
      queryParams: {tab: 'view'}
    });
  }

  getBookPrimaryReadingUrl(book: Book) {
    const bookType: BookType | undefined = book.primaryFile?.bookType;

    let baseUrl: string | null = null;

    switch (bookType) {
      case 'PDF':
        baseUrl = 'pdf-reader';
        break;

      case 'EPUB':
      case 'FB2':
      case 'MOBI':
      case 'AZW3':
        baseUrl = 'ebook-reader';
        break;

      case 'CBX':
        baseUrl = 'cbx-reader';
        break;

      case 'AUDIOBOOK':
        baseUrl = 'audiobook-player';
        break;
    }

    if (!baseUrl) {
      console.error('Unsupported book type:', bookType);
      //Go to book URL as a fall-back
      return this.getBookUrl(book);
    }

    return this.router.createUrlTree([`/${baseUrl}/book/${book.id}`], undefined);
  }

  /** The series key of a book, which tells same-name series apart (series-grouping.ts). */
  seriesKeyOf(bookId: number | undefined): string | null {
    const books = this.bookStateService.getCurrentBookState().books;
    if (!books || bookId == null) return null;
    if (this.seriesKeys.books !== books) {
      const keys = new Map<number, string>();
      for (const group of groupSeries(books)) {
        group.books.forEach(b => keys.set(b.id, group.key));
      }
      this.seriesKeys = {books, keys};
    }
    return this.seriesKeys.keys.get(bookId) ?? null;
  }

  /** The page of the series a book is in. */
  seriesUrl(book: Book) {
    const key = this.seriesKeyOf(book.id);
    return this.router.createUrlTree(['/series', encodeURIComponent(book.metadata?.seriesName ?? '')],
      key ? {queryParams: {key}} : undefined);
  }

  filterBooksBy(filterKey: string, filterValue: string) {
    if (filterKey === 'series') {
      return this.router.createUrlTree(['/series', encodeURIComponent(filterValue)])
    }

    return this.router.createUrlTree(['/all-books'], {
      queryParams: {
        view: 'grid',
        sort: 'title',
        direction: 'asc',
        sidebar: true,
        filter: `${filterKey}:${encodeURIComponent(filterValue)}`
      }
    });
  }
}
