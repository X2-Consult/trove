import {MessageService} from 'primeng/api';
import {TranslocoService} from '@jsverse/transloco';

/** Whether a cover change was saved into the book file, as the cover endpoints report it. */
export interface CoverWriteResult {
  status: 'WRITTEN' | 'SKIPPED' | 'FAILED';
  message: string;
}

/**
 * Tells the user when a new cover didn't make it into the book file (so downloads would lack it),
 * or confirms it did. Nothing is shown when Trove wasn't meant to write it.
 */
export function reportCoverFileResult(result: CoverWriteResult | null | undefined, messageService: MessageService, t: TranslocoService): void {
  if (!result) {
    return;
  }
  if (result.status === 'FAILED') {
    messageService.add({
      severity: 'warn',
      summary: t.translate('metadata.coverFile.failedSummary'),
      detail: result.message,
      life: 10000
    });
  } else if (result.status === 'WRITTEN') {
    messageService.add({
      severity: 'success',
      summary: t.translate('metadata.coverFile.writtenSummary'),
      detail: result.message
    });
  }
}
