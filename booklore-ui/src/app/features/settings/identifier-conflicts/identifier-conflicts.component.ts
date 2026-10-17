import {Component, inject, OnDestroy, OnInit} from '@angular/core';
import {RouterLink} from '@angular/router';
import {Button} from 'primeng/button';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {ConfirmationService, MessageService} from 'primeng/api';
import {interval, Subscription, switchMap} from 'rxjs';
import {
  IdentifierConflictGroup,
  IdentifierConflictsService,
  RepairBookAction,
  RepairPlanGroup,
  RepairPlanStatus
} from './identifier-conflicts.service';
import {TaskHelperService} from '../task-management/task-helper.service';
import {MetadataRefreshType} from '../../metadata/model/request/metadata-refresh-type.enum';
import {TagColor, TagComponent} from '../../../shared/components/tag/tag.component';

const STATUS_COLORS: Record<string, TagColor> = {
  RESOLVED: 'green',
  NO_OWNER: 'amber',
  AMBIGUOUS: 'orange',
  LOOKUP_FAILED: 'red',
};

@Component({
  selector: 'app-identifier-conflicts',
  standalone: true,
  imports: [RouterLink, Button, TranslocoDirective, TagComponent],
  templateUrl: './identifier-conflicts.component.html',
  styleUrl: './identifier-conflicts.component.scss'
})
export class IdentifierConflictsComponent implements OnInit, OnDestroy {
  private readonly service = inject(IdentifierConflictsService);
  private readonly taskHelper = inject(TaskHelperService);
  private readonly confirmationService = inject(ConfirmationService);
  private readonly messageService = inject(MessageService);
  private readonly t = inject(TranslocoService);

  groups: IdentifierConflictGroup[] = [];
  loading = true;
  planStatus: RepairPlanStatus | null = null;
  applying = false;
  private planByKey = new Map<string, RepairPlanGroup>();
  private pollSub?: Subscription;

  ngOnInit(): void {
    this.loadConflicts();
    this.service.getRepairPlan().subscribe(status => this.onPlanStatus(status));
  }

  ngOnDestroy(): void {
    this.pollSub?.unsubscribe();
  }

  loadConflicts(): void {
    this.loading = true;
    this.service.getConflicts().subscribe({
      next: groups => {
        this.groups = groups;
        this.loading = false;
      },
      error: () => this.loading = false
    });
  }

  startDryRun(): void {
    this.service.startRepairPlan().subscribe(status => this.onPlanStatus(status));
  }

  private onPlanStatus(status: RepairPlanStatus): void {
    this.planStatus = status;
    this.planByKey.clear();
    status.plan?.groups.forEach(g => this.planByKey.set(this.key(g.libraryId, g.identifier), g));
    if (status.running && !this.pollSub) {
      this.pollSub = interval(3000).pipe(switchMap(() => this.service.getRepairPlan())).subscribe(next => {
        if (!next.running) {
          this.pollSub?.unsubscribe();
          this.pollSub = undefined;
        }
        this.onPlanStatus(next);
      });
    }
  }

  private key(libraryId: number, identifier: string): string {
    return `${libraryId}|${identifier}`;
  }

  planFor(group: IdentifierConflictGroup): RepairPlanGroup | undefined {
    return this.planByKey.get(this.key(group.libraryId, group.identifier));
  }

  actionFor(group: IdentifierConflictGroup, bookId: number): RepairBookAction | undefined {
    return this.planFor(group)?.books.find(b => b.bookId === bookId)?.action;
  }

  statusColor(status: string): TagColor {
    return STATUS_COLORS[status] ?? 'gray';
  }

  get plannedClears(): {bookId: number; identifier: string}[] {
    const clears: {bookId: number; identifier: string}[] = [];
    this.planStatus?.plan?.groups.forEach(g => g.books
      .filter(b => b.action === 'CLEAR')
      .forEach(b => clears.push({bookId: b.bookId, identifier: g.identifier})));
    return clears;
  }

  get plannedBookCount(): number {
    return new Set(this.plannedClears.map(c => c.bookId)).size;
  }

  applyPlan(): void {
    const clears = this.plannedClears;
    if (clears.length === 0) return;
    this.confirmationService.confirm({
      header: this.t.translate('settingsIdentifierConflicts.confirmHeader'),
      message: this.t.translate('settingsIdentifierConflicts.confirmMessage', {
        identifiers: clears.length,
        books: this.plannedBookCount
      }),
      icon: 'pi pi-exclamation-triangle',
      accept: () => this.runRepair(clears)
    });
  }

  private runRepair(clears: {bookId: number; identifier: string}[]): void {
    this.applying = true;
    this.service.repair(clears).subscribe({
      next: bookIds => {
        this.applying = false;
        this.planStatus = null;
        this.planByKey.clear();
        this.messageService.add({
          severity: 'success',
          summary: this.t.translate('settingsIdentifierConflicts.repairedSummary'),
          detail: this.t.translate('settingsIdentifierConflicts.repairedDetail', {books: bookIds.length})
        });
        if (bookIds.length > 0) {
          this.taskHelper.refreshMetadataTask({refreshType: MetadataRefreshType.BOOKS, bookIds}).subscribe();
        }
        this.loadConflicts();
      },
      error: () => {
        this.applying = false;
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('settingsIdentifierConflicts.repairFailed')
        });
      }
    });
  }
}
