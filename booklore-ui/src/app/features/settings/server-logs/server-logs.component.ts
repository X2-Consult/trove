import {Component, inject, OnDestroy, OnInit} from '@angular/core';
import {DatePipe} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {Select} from 'primeng/select';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {interval, Subject, Subscription} from 'rxjs';
import {debounceTime} from 'rxjs/operators';
import {ServerLogEntry, ServerLogFile, ServerLogService} from './server-log.service';

interface Option<T> {
  label: string;
  value: T;
}

/** Trove's own log, so admins can follow tasks and errors without a shell on the server. */
@Component({
  selector: 'app-server-logs',
  standalone: true,
  imports: [FormsModule, Select, TranslocoDirective, DatePipe],
  templateUrl: './server-logs.component.html',
  styleUrl: './server-logs.component.scss'
})
export class ServerLogsComponent implements OnInit, OnDestroy {
  private readonly serverLogService = inject(ServerLogService);
  private readonly t = inject(TranslocoService);

  entries: ServerLogEntry[] = [];
  matched = 0;
  truncated = false;
  loading = false;
  unavailable = false;
  expanded = new Set<number>();

  fileOptions: Option<string | null>[] = [];
  selectedFile: string | null = null;
  levelOptions: Option<string | null>[] = [];
  selectedLevel: string | null = 'INFO';
  limitOptions: Option<number>[] = [200, 500, 1000, 2000, 5000].map(n => ({label: String(n), value: n}));
  limit = 500;
  query = '';
  autoRefresh = false;

  readonly quickFilters = ['WARN', 'FIND_EBOOK_ADS', 'REMOVE_EBOOK_ADS', 'FIND_EPUB_COVER_PROBLEMS', 'FIX_EPUB_COVERS', 'FETCH_MISSING_REVIEWS'];

  private readonly queryChanges = new Subject<string>();
  private subscriptions: Subscription[] = [];
  private autoRefreshSub?: Subscription;

  ngOnInit(): void {
    this.levelOptions = [
      {label: this.t.translate('settingsServerLogs.levels.all'), value: null},
      {label: this.t.translate('settingsServerLogs.levels.info'), value: 'INFO'},
      {label: this.t.translate('settingsServerLogs.levels.warn'), value: 'WARN'},
      {label: this.t.translate('settingsServerLogs.levels.error'), value: 'ERROR'}
    ];
    this.subscriptions.push(this.queryChanges.pipe(debounceTime(400)).subscribe(() => this.load()));
    this.loadFiles();
    this.load();
  }

  ngOnDestroy(): void {
    this.subscriptions.forEach(s => s.unsubscribe());
    this.autoRefreshSub?.unsubscribe();
  }

  onQueryChange(value: string): void {
    this.query = value;
    this.queryChanges.next(value);
  }

  applyQuickFilter(filter: string): void {
    if (filter === 'WARN') {
      this.selectedLevel = this.selectedLevel === 'WARN' ? 'INFO' : 'WARN';
    } else {
      this.query = this.query === filter ? '' : filter;
    }
    this.load();
  }

  isQuickFilterActive(filter: string): boolean {
    return filter === 'WARN' ? this.selectedLevel === 'WARN' : this.query === filter;
  }

  load(): void {
    this.loading = true;
    this.serverLogService.read(this.selectedFile, this.selectedLevel, this.query.trim() || null, this.limit).subscribe({
      next: page => {
        this.entries = page.entries;
        this.matched = page.matched;
        this.truncated = page.truncated;
        this.unavailable = false;
        this.expanded.clear();
        this.loading = false;
      },
      error: () => {
        this.entries = [];
        this.unavailable = true;
        this.loading = false;
      }
    });
  }

  loadFiles(): void {
    this.serverLogService.getFiles().subscribe({
      next: files => this.fileOptions = files.map(f => ({label: this.describeFile(f), value: f.current ? null : f.name})),
      error: () => this.fileOptions = []
    });
  }

  toggleAutoRefresh(): void {
    this.autoRefresh = !this.autoRefresh;
    this.autoRefreshSub?.unsubscribe();
    if (this.autoRefresh) {
      this.autoRefreshSub = interval(5000).subscribe(() => this.load());
    }
  }

  toggle(index: number): void {
    if (this.expanded.has(index)) {
      this.expanded.delete(index);
    } else {
      this.expanded.add(index);
    }
  }

  download(): void {
    this.serverLogService.download(this.selectedFile).subscribe(blob => {
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = this.selectedFile ?? 'trove.log';
      link.click();
      URL.revokeObjectURL(url);
    });
  }

  levelClass(entry: ServerLogEntry): string {
    return 'level-' + entry.level.toLowerCase();
  }

  private describeFile(file: ServerLogFile): string {
    const size = file.sizeBytes >= 1024 * 1024
      ? (file.sizeBytes / (1024 * 1024)).toFixed(1) + ' MB'
      : Math.max(1, Math.round(file.sizeBytes / 1024)) + ' KB';
    return file.current
      ? this.t.translate('settingsServerLogs.currentFile', {size})
      : `${file.name} (${size})`;
  }
}
