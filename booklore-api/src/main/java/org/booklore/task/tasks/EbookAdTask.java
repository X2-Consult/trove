package org.booklore.task.tasks;

import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.UserPermission;
import org.booklore.model.websocket.TaskProgressPayload;
import org.booklore.model.websocket.Topic;
import org.booklore.service.NotificationService;
import org.booklore.service.file.EbookAdCleanerService;
import org.booklore.task.TaskCancellationManager;
import org.booklore.task.TaskStatus;
import org.booklore.task.options.EbookAdOptions;

import java.util.List;
import java.util.UUID;

/**
 * Walks the library's EPUBs looking for the OceanofPDF advert: {@link FindEbookAdsTask} only reports,
 * {@link RemoveEbookAdsTask} cleans each book it finds. Both can be limited to some books with
 * {@link EbookAdOptions}.
 */
@Slf4j
abstract class EbookAdTask implements Task {

    private static final long MIN_NOTIFICATION_INTERVAL_MS = 500;

    protected final EbookAdCleanerService cleanerService;
    private final NotificationService notificationService;
    private final TaskCancellationManager cancellationManager;

    private long lastNotification;
    private volatile String lastResult;

    EbookAdTask(EbookAdCleanerService cleanerService, NotificationService notificationService, TaskCancellationManager cancellationManager) {
        this.cleanerService = cleanerService;
        this.notificationService = notificationService;
        this.cancellationManager = cancellationManager;
    }

    /** Running totals for one run. */
    protected static final class Tally {
        int checked;
        int books;
        int blocks;
        int strayFiles;
        int otherMentions;
        int failed;
    }

    /** Handles one EPUB, adding to the tally. */
    protected abstract void process(BookFileEntity bookFile, Tally tally) throws Exception;

    protected abstract String summary(Tally tally);

    /** Throws if the task can't run at all. */
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
            EbookAdOptions options = request != null ? request.getOptionsAs(EbookAdOptions.class) : null;
            List<BookFileEntity> files = cleanerService.candidates(options != null ? options.getBookIds() : null);
            Tally tally = new Tally();
            for (int i = 0; i < files.size(); i++) {
                if (cancellationManager.isTaskCancelled(taskId)) {
                    log.info("{}: Cancelled after {} of {} file(s)", getTaskType(), i, files.size());
                    break;
                }
                BookFileEntity bookFile = files.get(i);
                try {
                    process(bookFile, tally);
                    tally.checked++;
                } catch (Exception e) {
                    tally.failed++;
                    log.warn("{}: Could not process bookId={} path={}: {}", getTaskType(), bookFile.getBook().getId(),
                            describePath(bookFile), e.getMessage());
                }
                progress(taskId, (int) (100L * (i + 1) / files.size()),
                        String.format("Checked %d of %d EPUBs, %d with ads so far", i + 1, files.size(), tally.books), TaskStatus.IN_PROGRESS, false);
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

    protected static String describePath(BookFileEntity bookFile) {
        try {
            return bookFile.getFullFilePath().toString();
        } catch (Exception e) {
            return bookFile.getFileName();
        }
    }

    protected static String plural(int n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
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
