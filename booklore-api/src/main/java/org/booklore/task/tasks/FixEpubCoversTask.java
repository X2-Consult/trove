package org.booklore.task.tasks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.AppProperties;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.enums.AuditAction;
import org.booklore.model.enums.TaskType;
import org.booklore.model.enums.UserPermission;
import org.booklore.model.websocket.TaskProgressPayload;
import org.booklore.model.websocket.Topic;
import org.booklore.service.NotificationService;
import org.booklore.service.audit.AuditService;
import org.booklore.service.metadata.EpubCoverRepairService;
import org.booklore.service.metadata.writer.CoverWriteResult;
import org.booklore.task.TaskCancellationManager;
import org.booklore.task.TaskStatus;
import org.booklore.util.EpubCover;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Fixes the covers of books whose primary file is an EPUB in two stages: first every book's own
 * cover is checked, then Trove's cover is written into the ones readers can't show. Each repair is
 * read back and confirmed before it counts, and gets an audit log entry. Where book files can't be
 * changed, only the first stage runs. Books an earlier run found fine or fixed, and unchanged since,
 * are skipped.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FixEpubCoversTask implements Task {

    private static final long MIN_NOTIFICATION_INTERVAL_MS = 500;
    /** Share of the progress bar for checking; fixing, of far fewer books, gets the rest. */
    private static final int CHECK_SHARE = 80;

    private final EpubCoverRepairService repairService;
    private final NotificationService notificationService;
    private final TaskCancellationManager cancellationManager;
    private final AuditService auditService;
    private final AppProperties appProperties;

    private long lastNotification;
    private volatile String lastResult;

    /** Running totals for one run. */
    static final class Tally {
        int checked;
        final Map<EpubCover.Problem, Integer> problems = new EnumMap<>(EpubCover.Problem.class);
        int repaired;
        int notRepaired;
        int failed;
        int skipped;
        boolean cancelled;

        int broken() {
            return problems.values().stream().mapToInt(Integer::intValue).sum();
        }

        String describeProblems() {
            return problems.entrySet().stream()
                    .map(e -> e.getValue() + " " + e.getKey().description())
                    .collect(Collectors.joining("; "));
        }
    }

    @Override
    public void validatePermissions(BookLoreUser user, TaskCreateRequest request) {
        if (!UserPermission.CAN_ACCESS_TASK_MANAGER.isGranted(user.getPermissions())) {
            throw ApiError.PERMISSION_DENIED.createException(UserPermission.CAN_ACCESS_TASK_MANAGER);
        }
    }

    @Override
    public TaskCreateResponse execute(TaskCreateRequest request) {
        String taskId = request != null && request.getTaskId() != null ? request.getTaskId() : UUID.randomUUID().toString();
        TaskCreateResponse.TaskCreateResponseBuilder response = TaskCreateResponse.builder().taskId(taskId).taskType(getTaskType());
        lastNotification = 0;
        long start = System.currentTimeMillis();

        try {
            EpubCoverRepairService.Candidates candidates = repairService.candidates();
            Tally tally = new Tally();
            tally.skipped = candidates.skipped();

            List<Long> broken = find(taskId, candidates.bookIds(), tally);
            boolean canChangeFiles = appProperties.isLocalStorage();
            if (!tally.cancelled && canChangeFiles) {
                fix(taskId, broken, tally);
            }

            String summary = summary(tally, canChangeFiles);
            lastResult = summary;
            log.info("{}: {} ({} ms)", getTaskType(), summary, System.currentTimeMillis() - start);
            progress(taskId, 100, summary, TaskStatus.COMPLETED, true);
            return response.status(TaskStatus.COMPLETED).build();
        } catch (Exception e) {
            log.error("{}: Failed", getTaskType(), e);
            progress(taskId, 100, "Failed: " + e.getMessage(), TaskStatus.FAILED, true);
            return response.status(TaskStatus.FAILED).build();
        }
    }

    /** Stage 1: checks every book, returning those whose cover readers may not show. */
    private List<Long> find(String taskId, List<Long> bookIds, Tally tally) {
        List<Long> broken = new ArrayList<>();
        for (int i = 0; i < bookIds.size(); i++) {
            if (cancellationManager.isTaskCancelled(taskId)) {
                log.info("{}: Cancelled after checking {} of {} book(s)", getTaskType(), i, bookIds.size());
                tally.cancelled = true;
                break;
            }
            Long bookId = bookIds.get(i);
            try {
                EpubCoverRepairService.Outcome outcome = repairService.check(bookId);
                if (outcome != null) {
                    tally.checked++;
                    if (!outcome.check().ok()) {
                        tally.problems.merge(outcome.check().problem(), 1, Integer::sum);
                        broken.add(bookId);
                        log.info("{}: bookId={} {}: {}", getTaskType(), bookId, outcome.check().describe(), outcome.path());
                    }
                }
            } catch (Exception e) {
                tally.failed++;
                log.warn("{}: Could not check book {}: {}", getTaskType(), bookId, e.getMessage());
            }
            progress(taskId, (int) ((long) CHECK_SHARE * (i + 1) / bookIds.size()),
                    String.format("Stage 1 of 2: checked %d of %d books, %d with a broken cover so far", i + 1, bookIds.size(), broken.size()),
                    TaskStatus.IN_PROGRESS, false);
        }
        return broken;
    }

    /** Stage 2: writes Trove's cover into the books stage 1 found broken. */
    private void fix(String taskId, List<Long> bookIds, Tally tally) {
        for (int i = 0; i < bookIds.size(); i++) {
            if (cancellationManager.isTaskCancelled(taskId)) {
                log.info("{}: Cancelled after fixing {} of {} book(s)", getTaskType(), i, bookIds.size());
                tally.cancelled = true;
                break;
            }
            Long bookId = bookIds.get(i);
            try {
                EpubCoverRepairService.Outcome outcome = repairService.repair(bookId);
                if (outcome != null && !outcome.check().ok()) {
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
            } catch (Exception e) {
                tally.notRepaired++;
                log.warn("{}: Couldn't repair bookId={}: {}", getTaskType(), bookId, e.getMessage());
            }
            progress(taskId, CHECK_SHARE + (int) ((long) (100 - CHECK_SHARE) * (i + 1) / bookIds.size()),
                    String.format("Stage 2 of 2: fixed %d of %d broken covers", i + 1, bookIds.size()), TaskStatus.IN_PROGRESS, false);
        }
    }

    static String summary(Tally tally, boolean canChangeFiles) {
        String text;
        if (tally.broken() == 0) {
            text = String.format("All %d EPUBs checked have a cover readers can show.", tally.checked);
        } else if (!canChangeFiles) {
            text = String.format("%d of %d EPUBs have a cover readers may not show (%s), but book files can't be changed on this server's storage, so they were left as they are. The books are listed under Settings > Server Logs.",
                    tally.broken(), tally.checked, tally.describeProblems());
        } else {
            text = String.format("Fixed the cover in %d of the %d EPUBs that needed it, out of %d checked (%s), each confirmed by reading the file back.",
                    tally.repaired, tally.broken(), tally.checked, tally.describeProblems());
            if (tally.notRepaired > 0) {
                text += " " + tally.notRepaired + " couldn't be fixed; see Settings > Server Logs.";
            }
        }
        if (tally.failed > 0) {
            text += " " + tally.failed + " couldn't be checked.";
        }
        if (tally.cancelled) {
            text += " Cancelled before the end; the next run carries on.";
        }
        if (tally.skipped > 0) {
            text += " " + tally.skipped + (tally.skipped == 1 ? " EPUB" : " EPUBs") + " already checked and unchanged since were skipped.";
        }
        return text;
    }

    private void progress(String taskId, int percent, String message, TaskStatus status, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastNotification < MIN_NOTIFICATION_INTERVAL_MS) {
            return;
        }
        lastNotification = now;
        try {
            notificationService.sendMessage(Topic.TASK_PROGRESS, TaskProgressPayload.builder()
                    .taskId(taskId).taskType(getTaskType()).message(message).progress(percent).taskStatus(status).build());
        } catch (Exception e) {
            log.debug("Could not send task progress: {}", e.getMessage());
        }
    }

    /** The last run's result, until the server restarts. */
    @Override
    public String getMetadata() {
        return lastResult != null ? "Last run: " + lastResult : null;
    }

    @Override
    public TaskType getTaskType() {
        return TaskType.FIX_EPUB_COVERS;
    }
}
