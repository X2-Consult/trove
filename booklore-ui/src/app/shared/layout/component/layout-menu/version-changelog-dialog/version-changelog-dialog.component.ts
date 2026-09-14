import {Component, inject, OnInit} from '@angular/core';
import {ReleaseNote, VersionService} from '../../../../service/version.service';

import {marked} from 'marked';
import DOMPurify from 'dompurify';
import {DatePipe} from '@angular/common';
import {DynamicDialogRef} from 'primeng/dynamicdialog';
import {Button} from 'primeng/button';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {ConfirmationService, MessageService} from 'primeng/api';
import {SelfUpdateStatus, SystemUpdateService} from '../../../../service/system-update.service';
import {UserService} from '../../../../../features/settings/user-management/user.service';

@Component({
  selector: 'app-version-changelog-dialog',
  standalone: true,
  imports: [
    DatePipe,
    Button,
    TranslocoDirective
  ],
  templateUrl: './version-changelog-dialog.component.html',
  styleUrl: './version-changelog-dialog.component.scss'
})
export class VersionChangelogDialogComponent implements OnInit {

  private versionService = inject(VersionService);
  private systemUpdateService = inject(SystemUpdateService);
  private userService = inject(UserService);
  private confirmationService = inject(ConfirmationService);
  private messageService = inject(MessageService);
  private t = inject(TranslocoService);
  dialogRef = inject(DynamicDialogRef);

  changelog: ReleaseNote[] = [];
  loading = true;

  updateStatus: SelfUpdateStatus | null = null;
  starting = false;

  ngOnInit(): void {
    this.versionService.getChangelog().subscribe({
      next: (data) => {
        this.changelog = data;
        this.loading = false;
      },
      error: () => {
        this.loading = false;
      }
    });

    if (this.userService.getCurrentUser()?.permissions?.admin) {
      this.systemUpdateService.getUpdateStatus().subscribe({
        next: (status) => this.updateStatus = status,
        error: () => this.updateStatus = null
      });
    }
  }

  get canUpdate(): boolean {
    return !!this.updateStatus?.selfUpdateSupported
      && !!this.updateStatus?.updateAvailable
      && !this.updateStatus?.inProgress
      && !this.starting;
  }

  confirmUpdate(): void {
    this.confirmationService.confirm({
      header: this.t.translate('layout.changelog.updateConfirmHeader'),
      message: this.t.translate('layout.changelog.updateConfirmMessage'),
      icon: 'pi pi-exclamation-triangle',
      accept: () => this.startUpdate()
    });
  }

  private startUpdate(): void {
    if (!this.updateStatus) return;
    this.starting = true;
    const previousVersion = this.updateStatus.currentVersion;
    this.systemUpdateService.triggerUpdate(previousVersion).subscribe({
      next: () => {
        this.dialogRef.close();
      },
      error: (err) => {
        this.starting = false;
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('layout.changelog.updateFailedSummary'),
          detail: err?.error?.message || this.t.translate('layout.changelog.updateFailedDetail')
        });
      }
    });
  }

  markdownToHtml(markdown: string): string {
    let html = marked.parse(markdown ?? '', {async: false, gfm: true});
    html = html.replace(/<h2\b([^>]*)>/g, '<h3$1>').replace(/<\/h2>/g, '</h3>');
    return DOMPurify.sanitize(html);
  }
}
