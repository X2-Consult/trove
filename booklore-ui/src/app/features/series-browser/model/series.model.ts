import {Book, ReadStatus} from '../../book/model/book.model';

export interface SeriesSummary {
  /** Identifies the series among same-name ones; see series-grouping.ts. */
  key: string;
  /** The books' series name. */
  name: string;
  /** What to show: the name, plus the author where two series share it. */
  seriesName: string;
  books: Book[];
  authors: string[];
  categories: string[];
  bookCount: number;
  readCount: number;
  progress: number;
  seriesStatus: ReadStatus;
  nextUnread: Book | null;
  lastReadTime: string | null;
  coverBooks: Book[];
  addedOn: string | null;
}
