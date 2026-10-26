import {Book} from '../../features/book/model/book.model';

/**
 * Which books with the same series name are one series. Within a name:
 *  1. books put together by hand (seriesGroup, from Split / Merge) are one series, as set;
 *  2. books sharing a Goodreads series ID or an Amazon series ASIN are one series, whoever wrote them;
 *  3. groups sharing an author join, unless both hold IDs from the same source and those differ -
 *     so Lee Child's and Andrew Child's Reacher books stay together, Rex Sterling's and Dirk Knight's
 *     "Harem University" stay apart, and one author's two differently-ID'd series aren't merged.
 * Mirrors SeriesGrouping.java, which OPDS and /api/v1/series use, keys included; keep the two in step.
 */
export interface SeriesGroup {
  /** Stays the same while the series does: gr:<id>, amzn:<asin>, manual:<group> or name:<name>|<author>. */
  key: string;
  name: string;
  /** The name, plus the lead author where two series share it. */
  displayName: string;
  /** In series-number order. */
  books: Book[];
  /** True if the series was put together by hand. */
  manual: boolean;
}

const NO_AUTHOR = '\u0000';

export function normalizeSeriesName(value: string | null | undefined): string {
  return (value ?? '').trim().replace(/\s+/g, ' ').toLowerCase();
}

class Unit {
  books: Book[] = [];
  goodreadsIds = new Set<string>();
  asins = new Set<string>();
  authors = new Set<string>();

  constructor(readonly manualGroup: string | null) {
  }

  add(book: Book): void {
    this.books.push(book);
    const m = book.metadata;
    if (m?.seriesGoodreadsId?.trim()) this.goodreadsIds.add(m.seriesGoodreadsId.trim());
    if (m?.seriesAsin?.trim()) this.asins.add(m.seriesAsin.trim().toUpperCase());
    authorKeys(book).forEach(a => this.authors.add(a));
  }

  absorb(other: Unit): void {
    other.books.forEach(b => this.add(b));
  }

  get minBookId(): number {
    return Math.min(...this.books.map(b => b.id));
  }

  conflictsWith(other: Unit): boolean {
    return (this.goodreadsIds.size > 0 && other.goodreadsIds.size > 0 && disjoint(this.goodreadsIds, other.goodreadsIds))
      || (this.asins.size > 0 && other.asins.size > 0 && disjoint(this.asins, other.asins));
  }

  sharesAuthorWith(other: Unit): boolean {
    return !disjoint(this.authors, other.authors);
  }
}

export function groupSeries(books: Book[]): SeriesGroup[] {
  const byName = new Map<string, Book[]>();
  for (const book of books) {
    const name = normalizeSeriesName(book.metadata?.seriesName);
    if (!name) continue;
    if (!byName.has(name)) byName.set(name, []);
    byName.get(name)!.push(book);
  }
  const groups: SeriesGroup[] = [];
  for (const [name, named] of byName) {
    groups.push(...groupName(name, named));
  }
  return groups;
}

/** The series {@code bookId} is in, among {@code books}. */
export function findSeriesOfBook(books: Book[], bookId: number): SeriesGroup | null {
  const book = books.find(b => b.id === bookId);
  const name = normalizeSeriesName(book?.metadata?.seriesName);
  if (!book || !name) return null;
  const named = books.filter(b => normalizeSeriesName(b.metadata?.seriesName) === name);
  return groupName(name, named).find(g => g.books.some(b => b.id === bookId)) ?? null;
}

function groupName(normalizedName: string, books: Book[]): SeriesGroup[] {
  const manual = new Map<string, Unit>();
  const auto: Book[] = [];
  for (const book of books) {
    const group = book.metadata?.seriesGroup?.trim();
    if (group) {
      if (!manual.has(group)) manual.set(group, new Unit(group));
      manual.get(group)!.add(book);
    } else {
      auto.push(book);
    }
  }

  const all = [...mergeByAuthor(unitsByIds(auto)), ...manual.values()]
    .sort((a, b) => a.minBookId - b.minBookId);

  const displayCounts = new Map<string, number>();
  return all.map(unit => {
    const sorted = [...unit.books].sort((a, b) =>
      (a.metadata?.seriesNumber ?? Number.MAX_VALUE) - (b.metadata?.seriesNumber ?? Number.MAX_VALUE) || a.id - b.id);
    const name = sorted[0].metadata!.seriesName!.trim();
    let displayName = all.length > 1 ? `${name} (${leadAuthor(sorted)})` : name;
    const seen = (displayCounts.get(displayName) ?? 0) + 1;
    displayCounts.set(displayName, seen);
    if (seen > 1) displayName = `${displayName} ${seen}`;
    return {key: key(unit, normalizedName), name, displayName, books: sorted, manual: unit.manualGroup !== null};
  });
}

/** Books joined through shared series IDs: a book with both IDs links the books holding either. */
function unitsByIds(books: Book[]): Unit[] {
  const parent = books.map((_, i) => i);
  const find = (i: number): number => {
    while (parent[i] !== i) {
      parent[i] = parent[parent[i]];
      i = parent[i];
    }
    return i;
  };
  const firstWithId = new Map<string, number>();
  books.forEach((book, i) => {
    const m = book.metadata;
    const ids = [
      m?.seriesGoodreadsId?.trim() ? `gr:${m.seriesGoodreadsId.trim()}` : '',
      m?.seriesAsin?.trim() ? `amzn:${m.seriesAsin.trim().toUpperCase()}` : '',
    ];
    for (const id of ids) {
      if (!id) continue;
      const other = firstWithId.get(id);
      if (other === undefined) firstWithId.set(id, i);
      else parent[find(i)] = find(other);
    }
  });
  const byRoot = new Map<number, Unit>();
  books.forEach((book, i) => {
    const root = find(i);
    if (!byRoot.has(root)) byRoot.set(root, new Unit(null));
    byRoot.get(root)!.add(book);
  });
  return [...byRoot.values()];
}

/** Joins groups through shared authors, biggest first, never across conflicting IDs. */
function mergeByAuthor(units: Unit[]): Unit[] {
  const pending = [...units].sort((a, b) => b.books.length - a.books.length || a.minBookId - b.minBookId);
  const result: Unit[] = [];
  while (pending.length) {
    const current = pending.shift()!;
    let grew = true;
    while (grew) {
      grew = false;
      for (let i = 0; i < pending.length; i++) {
        if (current.sharesAuthorWith(pending[i]) && !current.conflictsWith(pending[i])) {
          current.absorb(pending[i]);
          pending.splice(i--, 1);
          grew = true;
        }
      }
    }
    result.push(current);
  }
  return result;
}

function key(unit: Unit, normalizedName: string): string {
  if (unit.manualGroup) return `manual:${unit.manualGroup}`;
  if (unit.goodreadsIds.size) return `gr:${[...unit.goodreadsIds].sort()[0]}`;
  if (unit.asins.size) return `amzn:${[...unit.asins].sort()[0]}`;
  const author = [...unit.authors].sort()[0] ?? NO_AUTHOR;
  return `name:${normalizedName}|${author === NO_AUTHOR ? '' : author}`;
}

/** The author on most of the series' books, alphabetically first on a tie. */
function leadAuthor(books: Book[]): string {
  const counts = new Map<string, number>();
  for (const book of books) {
    for (const author of book.metadata?.authors ?? []) {
      const name = author?.trim();
      if (name) counts.set(name, (counts.get(name) ?? 0) + 1);
    }
  }
  const sorted = [...counts.entries()].sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0));
  return sorted[0]?.[0] ?? 'Unknown author';
}

function authorKeys(book: Book): string[] {
  const keys = (book.metadata?.authors ?? []).filter(a => a?.trim()).map(a => normalizeSeriesName(a));
  return keys.length ? keys : [NO_AUTHOR];
}

function disjoint(a: Set<string>, b: Set<string>): boolean {
  for (const x of a) if (b.has(x)) return false;
  return true;
}
