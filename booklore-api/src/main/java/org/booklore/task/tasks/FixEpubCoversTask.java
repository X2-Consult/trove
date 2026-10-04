package org.booklore.task.tasks;

import lombok.extern.slf4j.Slf4j;
import org.booklore.config.AppProperties;
import org.booklore.model.enums.AuditAction;
import org.booklore.model.enums.TaskType;
import org.booklore.service.NotificationService;
import org.booklore.service.audit.AuditService;
import org.booklore.service.metadata.EpubCoverRepairService;
import org.booklore.service.metadata.writer.CoverWriteResult;
import org.booklore.task.TaskCancellationManager;
import org.springframework.stereotype.Component;

/**
 * Writes Trove's cover into every EPUB whose own cover readers can't show. Each repair is read back
 * and confirmed before it counts, and gets an audit log entry.
 */
@Component
@Slf4j
public class FixEpubCoversTask extends EpubCoverTask {

    private final AuditService auditService;
    private final AppProperties appProperties;

    public FixEpubCoversTask(EpubCoverRepairService repairService, NotificationService notificationService,
                             TaskCancellationManager cancellationManager, AuditService auditService, AppProperties appProperties) {
        super(repairService, notificationService, cancellationManager);
        this.auditService = auditService;
        this.appProperties = appProperties;
    }

    @Override
    protected void checkCanRun() {
        if (!appProperties.isLocalStorage()) {
            throw new IllegalStateException("Book files can't be changed with DISK_TYPE=" + appProperties.getDiskType());
        }
    }

    @Override
    protected void process(Long bookId, Tally tally) {
        EpubCoverRepairService.Outcome outcome = repairService.repair(bookId);
        if (!count(outcome, tally)) {
            return;
        }
        CoverWriteResult repair = outcome.repair();
        if (repair != null && repair.status() == CoverWriteResult.Status.WRITTEN) {
            tally.repaired++;
            log.info("{}: Repaired bookId={} ({}): {}", getTaskType(), bookId, outcome.check().describe(), outcome.path());
            auditService.log(AuditAction.EPUB_COVER_REPAIRED, "Book", bookId,
                    "Wrote Trove's cover into " + outcome.path() + " (" + outcome.check().describe() + ")");
        } else {
            tally.notRepaired++;
            log.warn("{}: Couldn't repair bookId={} ({}): {} - {}", getTaskType(), bookId, outcome.check().describe(),
                    repair != null ? repair.message() : "no repair tried", outcome.path());
        }
    }

    @Override
    protected String summary(Tally tally) {
        if (tally.broken() == 0) {
            return String.format("All %d EPUBs already have a cover readers can show.", tally.checked);
        }
        String text = String.format("Fixed the cover in %d of %d EPUBs that needed it (%s), each confirmed by reading the file back.",
                tally.repaired, tally.broken(), tally.describeProblems());
        if (tally.notRepaired > 0) {
            text += " " + tally.notRepaired + " couldn't be fixed; see the server log.";
        }
        return tally.failed > 0 ? text + " " + tally.failed + " couldn't be checked." : text;
    }

    @Override
    public TaskType getTaskType() {
        return TaskType.FIX_EPUB_COVERS;
    }
}
