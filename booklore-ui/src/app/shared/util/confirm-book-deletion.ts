import {ConfirmationService} from 'primeng/api';
import {TranslocoService} from '@jsverse/transloco';

/** Deleting more books than this asks twice, the second time naming the number. */
export const BULK_DELETE_THRESHOLD = 50;

/**
 * Asks before books are deleted: once, and for a bulk delete (more than BULK_DELETE_THRESHOLD
 * books) a second time, stating how many, with Cancel focused so Enter can't confirm it.
 */
export function confirmBookDeletion(confirmationService: ConfirmationService, t: TranslocoService, count: number, onConfirmed: () => void): void {
  confirmationService.confirm({
    message: t.translate('book.browser.confirm.deleteMessage', {count}),
    header: t.translate('book.browser.confirm.deleteHeader'),
    icon: 'pi pi-exclamation-triangle',
    acceptIcon: 'pi pi-trash',
    rejectIcon: 'pi pi-times',
    acceptLabel: t.translate('common.delete'),
    rejectLabel: t.translate('common.cancel'),
    acceptButtonStyleClass: 'p-button-danger',
    rejectButtonStyleClass: 'p-button-outlined',
    accept: () => {
      if (count <= BULK_DELETE_THRESHOLD) {
        onConfirmed();
        return;
      }
      // Opened once the first dialog has closed; PrimeNG shows one confirmation at a time.
      setTimeout(() => confirmationService.confirm({
        message: t.translate('book.browser.confirm.bulkDeleteMessage', {count}),
        header: t.translate('book.browser.confirm.bulkDeleteHeader', {count}),
        icon: 'pi pi-exclamation-circle',
        acceptIcon: 'pi pi-trash',
        rejectIcon: 'pi pi-times',
        acceptLabel: t.translate('book.browser.confirm.bulkDeleteAccept', {count}),
        rejectLabel: t.translate('common.cancel'),
        acceptButtonStyleClass: 'p-button-danger',
        rejectButtonStyleClass: 'p-button-outlined',
        defaultFocus: 'reject',
        accept: onConfirmed
      }));
    }
  });
}
