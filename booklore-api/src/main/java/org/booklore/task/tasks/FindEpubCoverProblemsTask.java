package org.booklore.task.tasks;

import lombok.extern.slf4j.Slf4j;
import org.booklore.model.enums.TaskType;
import org.booklore.service.NotificationService;
import org.booklore.service.metadata.EpubCoverRepairService;
import org.booklore.task.TaskCancellationManager;
import org.springframework.stereotype.Component;

/** Dry run: logs every EPUB whose own cover readers can't show, and why. Changes nothing. */
@Component
@Slf4j
public class FindEpubCoverProblemsTask extends EpubCoverTask {

    public FindEpubCoverProblemsTask(EpubCoverRepairService repairService, NotificationService notificationService,
                                     TaskCancellationManager cancellationManager) {
        super(repairService, notificationService, cancellationManager);
    }

    @Override
    protected void process(Long bookId, Tally tally) {
        EpubCoverRepairService.Outcome outcome = repairService.check(bookId);
        if (count(outcome, tally)) {
            log.info("{}: bookId={} {}: {}", getTaskType(), bookId, outcome.check().describe(), outcome.path());
        }
    }

    @Override
    protected String summary(Tally tally) {
        if (tally.broken() == 0) {
            return String.format("All %d EPUBs have a cover readers can show. Nothing was changed.", tally.checked);
        }
        String text = String.format("%d of %d EPUBs have a cover readers may not show: %s. Nothing was changed; the books are listed in the server log.",
                tally.broken(), tally.checked, tally.describeProblems());
        return tally.failed > 0 ? text + " " + tally.failed + " couldn't be checked." : text;
    }

    @Override
    public TaskType getTaskType() {
        return TaskType.FIND_EPUB_COVER_PROBLEMS;
    }
}
