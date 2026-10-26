import {describe, expect, it} from 'vitest';
import {Book, ReadStatus} from '../../features/book/model/book.model';
import {findSeriesOfBook, groupSeries} from './series-grouping';

// The same cases as SeriesGroupingTest.java.
const book = (id: number, seriesName: string, seriesNumber: number | null, seriesGoodreadsId: string | null,
              seriesAsin: string | null, authors: string[], seriesGroup: string | null = null): Book => ({
  id,
  bookType: 'EPUB',
  libraryId: 1,
  libraryName: 'Library',
  readStatus: ReadStatus.UNREAD,
  shelves: [],
  metadata: {bookId: id, title: `Book ${id}`, seriesName, seriesNumber, seriesGoodreadsId, seriesAsin, seriesGroup, authors},
} as Book);

const ids = (books: Book[]) => books.map(b => b.id);

describe('groupSeries', () => {
  it('splits same-name series by different authors and names them by author', () => {
    const groups = groupSeries([
      book(8233, 'Harem University', 1, null, 'B0D9DNS7D3', ['Rex Sterling']),
      book(8231, 'Harem University', 2, null, 'B0D9DNS7D3', ['Rex Sterling']),
      book(8230, 'Harem University', null, null, null, ['Rex Sterling']),
      book(8107, 'Harem University', 1, null, 'B0D3KDJCS2', ['Dirk Knight']),
      book(8109, 'Harem University', null, null, null, ['Dirk Knight']),
    ]);

    expect(groups.map(g => g.displayName)).toEqual(['Harem University (Dirk Knight)', 'Harem University (Rex Sterling)']);
    expect(ids(groups[0].books)).toEqual([8107, 8109]);
    expect(groups[0].key).toBe('amzn:B0D3KDJCS2');
    expect(ids(groups[1].books)).toEqual([8233, 8231, 8230]);
    expect(groups[1].key).toBe('amzn:B0D9DNS7D3');
  });

  it('splits by author even without IDs', () => {
    const groups = groupSeries([
      book(1, 'Harem University', 1, null, null, ['Rex Sterling']),
      book(2, 'Harem University', 1, null, null, ['Dirk Knight']),
    ]);

    expect(groups.map(g => g.key)).toEqual(['name:harem university|rex sterling', 'name:harem university|dirk knight']);
  });

  it('keeps guest authors in the series through authors and IDs', () => {
    const groups = groupSeries([
      book(8165, 'Jack Reacher', 1, null, null, ['Lee Child']),
      book(1, 'Jack Reacher', 25, '40549', 'B0775W2V36', ['Andrew Child', 'Lee Child']),
      book(2, 'Jack Reacher', 26.5, '40549', null, ['Andrew Child']),
      book(3, 'Jack Reacher', 29.1, null, null, ['Andrew Child']),
    ]);

    expect(groups).toHaveLength(1);
    expect(groups[0].displayName).toBe('Jack Reacher');
    expect(groups[0].key).toBe('gr:40549');
    expect(ids(groups[0].books)).toEqual([8165, 1, 2, 3]);
  });

  it('keeps a full handover with a shared series ID together', () => {
    expect(groupSeries([
      book(1, 'Jason Bourne', 1, '40596', null, ['Robert Ludlum']),
      book(2, 'Jason Bourne', 4, '40596', null, ['Eric Van Lustbader']),
    ])).toHaveLength(1);
  });

  it("keeps one author's two series with different IDs apart", () => {
    const groups = groupSeries([
      book(1, 'Hidden', 1, null, 'B000000001', ['Same Author']),
      book(2, 'Hidden', 1, null, 'B000000002', ['Same Author']),
    ]);

    expect(groups.map(g => g.displayName)).toEqual(['Hidden (Same Author)', 'Hidden (Same Author) 2']);
  });

  it('joins Goodreads-only and ASIN-only books of one author', () => {
    const groups = groupSeries([
      book(1, 'Alex Cross', 1, '40543', null, ['James Patterson']),
      book(2, 'Alex Cross', 15, null, 'B07SV54YQQ', ['James Patterson']),
    ]);

    expect(groups).toHaveLength(1);
    expect(groups[0].key).toBe('gr:40543');
  });

  it('lets a manual group override the rules', () => {
    const groups = groupSeries([
      book(1, 'Hidden', 1, null, null, ['Same Author']),
      book(2, 'Hidden', 2, null, null, ['Same Author']),
      book(3, 'Hidden', null, null, null, ['Same Author'], 'abc123'),
    ]);

    expect(groups).toHaveLength(2);
    const manual = groups.find(g => g.key === 'manual:abc123')!;
    expect(manual.manual).toBe(true);
    expect(ids(manual.books)).toEqual([3]);
  });

  it('matches names ignoring case and spacing', () => {
    const groups = groupSeries([
      book(1, 'Alex Cross', 1, null, null, ['James Patterson']),
      book(2, ' alex  cross', 2, null, null, ['James Patterson']),
    ]);

    expect(groups).toHaveLength(1);
    expect(groups[0].name).toBe('Alex Cross');
  });
});

describe('findSeriesOfBook', () => {
  it("finds only the book's own series among same-name ones", () => {
    const books = [
      book(1, 'Harem University', 1, null, null, ['Rex Sterling']),
      book(2, 'Harem University', 1, null, null, ['Dirk Knight']),
      book(3, 'Harem University', 2, null, null, ['Dirk Knight']),
    ];

    expect(ids(findSeriesOfBook(books, 3)!.books)).toEqual([2, 3]);
    expect(findSeriesOfBook(books, 99)).toBeNull();
  });
});
