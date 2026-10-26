package org.booklore.task.tasks;

import org.booklore.config.AppProperties;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.service.NotificationService;
import org.booklore.service.audit.AuditService;
import org.booklore.service.metadata.EpubCoverRepairService;
import org.booklore.service.metadata.writer.CoverWriteResult;
import org.booklore.task.TaskCancellationManager;
import org.booklore.task.TaskStatus;
import org.booklore.util.EpubCover;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FixEpubCoversTaskTest {

    @Mock EpubCoverRepairService repairService;
    @Mock NotificationService notificationService;
    @Mock TaskCancellationManager cancellationManager;
    @Mock AuditService auditService;
    @Mock AppProperties appProperties;

    @InjectMocks FixEpubCoversTask task;

    private static final EpubCover.Check OK = new EpubCover.Check(null, null, "cover.jpg", 600, 900);
    private static final EpubCover.Check NO_COVER = new EpubCover.Check(EpubCover.Problem.NO_COVER, null, null, 0, 0);

    private static EpubCoverRepairService.Outcome outcome(long bookId, EpubCover.Check check, CoverWriteResult repair) {
        return new EpubCoverRepairService.Outcome(bookId, "/library/" + bookId + ".epub", check, repair, bookId, "hash");
    }

    @Test
    void checksEveryBookThenFixesOnlyTheBrokenOnes() {
        when(repairService.candidates()).thenReturn(new EpubCoverRepairService.Candidates(List.of(1L, 2L, 3L), 0));
        when(repairService.check(1L)).thenReturn(outcome(1, OK, null));
        when(repairService.check(2L)).thenReturn(outcome(2, NO_COVER, null));
        when(repairService.check(3L)).thenReturn(outcome(3, NO_COVER, null));
        when(appProperties.isLocalStorage()).thenReturn(true);
        when(repairService.repair(2L)).thenReturn(outcome(2, NO_COVER, CoverWriteResult.written("ok")));
        when(repairService.repair(3L)).thenReturn(outcome(3, NO_COVER, CoverWriteResult.skipped("Trove has no cover for this book either")));

        var response = task.execute(TaskCreateRequest.builder().taskId("t").build());

        assertThat(response.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        verify(repairService, never()).repair(1L);
        verify(auditService).log(any(), eq("Book"), eq(2L), contains("Wrote Trove's cover"));
        verify(auditService, never()).log(any(), any(), eq(3L), any());
        assertThat(task.getMetadata())
                .contains("Fixed the cover in 1 of the 2 EPUBs that needed it, out of 3 checked (2 no cover is declared)")
                .contains("1 couldn't be fixed");
    }

    @Test
    void onlyFindsWhenBookFilesCantBeChanged() {
        when(repairService.candidates()).thenReturn(new EpubCoverRepairService.Candidates(List.of(2L), 0));
        when(repairService.check(2L)).thenReturn(outcome(2, NO_COVER, null));
        when(appProperties.isLocalStorage()).thenReturn(false);

        task.execute(TaskCreateRequest.builder().taskId("t").build());

        verify(repairService, never()).repair(any());
        assertThat(task.getMetadata()).contains("1 of 1 EPUBs have a cover readers may not show").contains("can't be changed");
    }

    @Test
    void reportsAllFineWhenNothingIsBroken() {
        when(repairService.candidates()).thenReturn(new EpubCoverRepairService.Candidates(List.of(1L), 4));
        when(repairService.check(1L)).thenReturn(outcome(1, OK, null));
        when(appProperties.isLocalStorage()).thenReturn(true);

        task.execute(TaskCreateRequest.builder().taskId("t").build());

        verify(repairService, never()).repair(any());
        assertThat(task.getMetadata()).contains("All 1 EPUBs checked have a cover readers can show")
                .contains("4 EPUBs already checked and unchanged since were skipped");
    }
}
