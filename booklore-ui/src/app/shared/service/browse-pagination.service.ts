import {inject, Injectable, signal} from '@angular/core';
import {filter} from 'rxjs/operators';
import {BrowsePagination, UserService} from '../../features/settings/user-management/user.service';

export const PAGE_SIZE_OPTIONS = [24, 48, 96, 192];
const DEFAULT_PAGE_SIZE = 48;

/**
 * The user's choice between one continuous scrolling list of books (the default) and numbered
 * pages, saved as the browsePagination user setting. Used by the library and series pages.
 */
@Injectable({providedIn: 'root'})
export class BrowsePaginationService {
  private readonly userService = inject(UserService);
  private readonly setting = signal<BrowsePagination>({mode: 'scroll', pageSize: DEFAULT_PAGE_SIZE});

  constructor() {
    this.userService.userState$.pipe(filter(state => !!state?.user)).subscribe(state => {
      const saved = state.user!.userSettings?.browsePagination;
      this.setting.set({
        mode: saved?.mode === 'pages' ? 'pages' : 'scroll',
        pageSize: saved?.pageSize && PAGE_SIZE_OPTIONS.includes(saved.pageSize) ? saved.pageSize : DEFAULT_PAGE_SIZE
      });
    });
  }

  isPaged(): boolean {
    return this.setting().mode === 'pages';
  }

  pageSize(): number {
    return this.setting().pageSize;
  }

  setMode(mode: 'scroll' | 'pages'): void {
    this.save({...this.setting(), mode});
  }

  setPageSize(pageSize: number): void {
    if (PAGE_SIZE_OPTIONS.includes(pageSize) && pageSize !== this.pageSize()) {
      this.save({...this.setting(), pageSize});
    }
  }

  /** The books on a page (0-based); the whole list when not paging. */
  slice<T>(items: T[], page: number): T[] {
    if (!this.isPaged()) {
      return items;
    }
    const start = this.clampPage(page, items.length) * this.pageSize();
    return items.slice(start, start + this.pageSize());
  }

  /** A page that exists for this many items, so a shrinking list never leaves you on an empty page. */
  clampPage(page: number, totalItems: number): number {
    const lastPage = Math.max(0, Math.ceil(totalItems / this.pageSize()) - 1);
    return Math.min(Math.max(0, page), lastPage);
  }

  private save(value: BrowsePagination): void {
    this.setting.set(value);
    const user = this.userService.userStateSubject.value.user;
    if (user) {
      this.userService.updateUserSetting(user.id, 'browsePagination', value);
    }
  }
}
