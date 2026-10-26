package org.booklore.task.tasks;

import org.booklore.config.AppProperties;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.service.NotificationService;
import org.booklore.service.audit.AuditService;
import org.booklore.service.file.EbookAdCleanerService;
import org.booklore.task.TaskCancellationManager;
import org.booklore.task.TaskStatus;
import org.booklore.util.EpubAdCleaner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RemoveEbookAdsTaskTest {

    @Mock EbookAdCleanerService cleanerService;
    @Mock NotificationService notificationService;
    @Mock TaskCancellationManager cancellationManager;
    @Mock AuditService auditService;
    @Mock AppProperties appProperties;

    @InjectMocks RemoveEbookAdsTask task;

    private BookFileEntity file(long bookId) {
        BookFileEntity f = mock(BookFileEntity.class);
        when(f.getBook()).thenReturn(BookEntity.builder().id(bookId).build());
        when(f.getFileName()).thenReturn(bookId + ".epub");
        return f;
    }

    private void found(BookFileEntity f, int adBlocks) throws Exception {
        when(cleanerService.scan(f)).thenReturn(new EpubAdCleaner.Findings(adBlocks, false, 0));
    }

    @Test
    void checksEveryFileThenCleansOnlyTheOnesWithAds() throws Exception {
        BookFileEntity clean = file(1);
        BookFileEntity withAds = file(2);
        found(clean, 0);
        found(withAds, 3);
        when(cleanerService.candidates(any())).thenReturn(new EbookAdCleanerService.Candidates(List.of(clean, withAds), 5));
        when(appProperties.isLocalStorage()).thenReturn(true);
        when(cleanerService.clean(withAds)).thenReturn(new EbookAdCleanerService.Cleaned(new EpubAdCleaner.Findings(3, false, 0), Path.of("/backup/2.epub")));

        var response = task.execute(TaskCreateRequest.builder().taskId("t").build());

        assertThat(response.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        verify(cleanerService, never()).clean(clean);
        verify(cleanerService).clean(withAds);
        verify(auditService).log(any(), eq("Book"), eq(2L), contains("Removed 3 OceanofPDF links"));
        assertThat(task.getMetadata())
                .contains("Removed OceanofPDF ads from 1 book of the 1 EPUBs that had them, out of 2 checked")
                .contains("5 EPUBs already checked and unchanged since were skipped");
    }

    @Test
    void onlyFindsWhenBookFilesCantBeChanged() throws Exception {
        BookFileEntity withAds = file(2);
        found(withAds, 1);
        when(cleanerService.candidates(any())).thenReturn(new EbookAdCleanerService.Candidates(List.of(withAds), 0));
        when(appProperties.isLocalStorage()).thenReturn(false);

        task.execute(TaskCreateRequest.builder().taskId("t").build());

        verify(cleanerService, never()).clean(any());
        assertThat(task.getMetadata()).contains("1 book of 1 EPUBs carry OceanofPDF ads").contains("can't be changed");
    }

    @Test
    void cancellingWhileCheckingCleansNothing() throws Exception {
        BookFileEntity withAds = file(2);
        BookFileEntity notReached = file(3);
        found(withAds, 1);
        when(cleanerService.candidates(any())).thenReturn(new EbookAdCleanerService.Candidates(List.of(withAds, notReached), 0));
        when(appProperties.isLocalStorage()).thenReturn(true);
        when(cancellationManager.isTaskCancelled("t")).thenReturn(false, true);

        task.execute(TaskCreateRequest.builder().taskId("t").build());

        verify(cleanerService, never()).scan(notReached);
        verify(cleanerService, never()).clean(any());
        assertThat(task.getMetadata()).contains("Cancelled before the end");
    }
}
