package org.booklore.task.tasks;

import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.enums.UserPermission;
import org.booklore.model.websocket.TaskProgressPayload;
import org.booklore.model.websocket.Topic;
import org.booklore.service.NotificationService;
import org.booklore.service.metadata.EpubCoverRepairService;
import org.booklore.task.TaskCancellationManager;
import org.booklore.task.TaskStatus;
import org.booklore.util.EpubCover;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Walks the books whose primary file is an EPUB, checking that the file's own cover is one readers
 * can show: {@link FindEpubCoverProblemsTask} only reports, {@link FixEpubCoversTask} also writes
 * Trove's cover into the broken ones.
 */
@Slf4j
abstract class EpubCoverTask implements Task {

    private static final long MIN_NOTIFICATION_INTERVAL_MS = 500;

    protected final EpubCoverRepairService repairService;
    private final NotificationService notificationService;
    private final TaskCancellationManager cancellationManager;

    private long lastNotification;
    private volatile String lastResult;

    EpubCoverTask(EpubCoverRepairService repairService, NotificationService notificationService, TaskCancellationManager cancellationManager) {
        this.repairService = repairService;
        this.notificationService = notificationService;
        this.cancellationManager = cancellationManager;
    }

    /** Running totals for one run. */
    protected static final class Tally {
        int checked;
        final Map<EpubCover.Problem, Integer> problems = new EnumMap<>(EpubCover.Problem.class);
        int repaired;
        int notRepaired;
        int failed;

        int broken() {
            return problems.values().stream().mapToInt(Integer::intValue).sum();
        }

        String describeProblems() {
            return problems.entrySet().stream()
                    .map(e -> e.getValue() + " " + e.getKey().description())
                    .collect(Collectors.joining("; "));
        }
    }

    /** Checks (and maybe repairs) one book, adding to the tally. */
    protected abstract void process(Long bookId, Tally tally) throws Exception;

    protected abstract String summary(Tally tally);

    protected void checkCanRun() {
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
            checkCanRun();
            List<Long> bookIds = repairService.candidates();
            Tally tally = new Tally();
            for (int i = 0; i < bookIds.size(); i++) {
                if (cancellationManager.isTaskCancelled(taskId)) {
                    log.info("{}: Cancelled after {} of {} book(s)", getTaskType(), i, bookIds.size());
                    break;
                }
                Long bookId = bookIds.get(i);
                try {
                    process(bookId, tally);
                } catch (Exception e) {
                    tally.failed++;
                    log.warn("{}: Could not process book {}: {}", getTaskType(), bookId, e.getMessage());
                }
                progress(taskId, (int) (100L * (i + 1) / bookIds.size()),
                        String.format("Checked %d of %d books, %d with a broken cover so far", i + 1, bookIds.size(), tally.broken()),
                        TaskStatus.IN_PROGRESS, false);
            }

            String summary = summary(tally);
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

    /** Records a checked book's result; returns true if its cover is broken. */
    protected boolean count(EpubCoverRepairService.Outcome outcome, Tally tally) {
        if (outcome == null) {
            return false;
        }
        tally.checked++;
        if (outcome.check().ok()) {
            return false;
        }
        tally.problems.merge(outcome.check().problem(), 1, Integer::sum);
        return true;
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
}
