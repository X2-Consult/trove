package org.booklore.task.tasks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.AuditAction;
import org.booklore.model.enums.BookFileCheckResult;
import org.booklore.model.enums.BookFileCheckType;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.TaskType;
import org.booklore.model.enums.UserPermission;
import org.booklore.model.websocket.TaskProgressPayload;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookFileRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.audit.AuditService;
import org.booklore.service.file.BookFileCheckService;
import org.booklore.task.TaskCancellationManager;
import org.booklore.task.TaskStatus;
import org.booklore.util.BookFileIntegrity;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Checks every book file with {@link BookFileIntegrity}: each entry's checksum in EPUB/CBZ/CB7/CBR
 * archives, the end marker and page tree of PDFs, the box structure of M4B/M4A audiobooks and the
 * frames of MP3s - well beyond confirming the file starts with a valid header. A file can pass a
 * shallow header check while still being corrupted deeper in (see the Diddly Squat / Camping With
 * Her Step Dad incidents this task exists to catch earlier). Folder-based audiobooks have each file
 * checked. Formats with no deeper check (MOBI, AZW3) are skipped. Read-only: it only reports.
 * <p>
 * Files that pass are recorded ({@link BookFileCheckService}) against their hash, size and
 * modified time, so later runs only open new and changed files. Files that fail are checked again
 * every run until they're replaced.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EbookIntegrityCheckTask implements Task {

    private static final List<BookFileType> CANDIDATE_TYPES = List.of(BookFileType.values());
    private static final long MIN_NOTIFICATION_INTERVAL_MS = 500;

    private final BookFileRepository bookFileRepository;
    private final AuditService auditService;
    private final BookFileCheckService checks;
    private final NotificationService notificationService;
    private final TaskCancellationManager cancellationManager;

    private long lastNotification;
    private volatile String lastResult;

    @Override
    public void validatePermissions(BookLoreUser user, TaskCreateRequest request) {
        if (!UserPermission.CAN_ACCESS_TASK_MANAGER.isGranted(user.getPermissions())) {
            throw ApiError.PERMISSION_DENIED.createException(UserPermission.CAN_ACCESS_TASK_MANAGER);
        }
    }

    @Override
    public TaskCreateResponse execute(TaskCreateRequest request) {
        String taskId = request != null && request.getTaskId() != null ? request.getTaskId() : UUID.randomUUID().toString();
        TaskCreateResponse.TaskCreateResponseBuilder builder = TaskCreateResponse.builder()
                .taskId(taskId)
                .taskType(getTaskType());
        lastNotification = 0;

        long startTime = System.currentTimeMillis();
        log.info("{}: Task started", getTaskType());

        int checked = 0;
        int bad = 0;
        int unchanged = 0;

        try {
            progress(taskId, 0, "Loading book files...", TaskStatus.IN_PROGRESS, true);
            List<BookFileEntity> files = bookFileRepository.findAllWithBookAndLibraryPathByBookTypeIn(CANDIDATE_TYPES);
            BookFileCheckService.Settled settled = checks.settled(BookFileCheckType.INTEGRITY);
            for (int i = 0; i < files.size(); i++) {
                if (cancellationManager.isTaskCancelled(taskId)) {
                    log.info("{}: Cancelled after {} of {} file(s)", getTaskType(), i, files.size());
                    break;
                }
                BookFileEntity bookFile = files.get(i);
                String state = fileState(bookFile);
                if (state != null && settled.covers(bookFile.getId(), state)) {
                    unchanged++;
                } else {
                    String failureReason = checkIntegrity(bookFile);
                    if (failureReason != SKIPPED) {
                        checked++;
                        if (failureReason != null) {
                            bad++;
                            reportBadFile(bookFile, failureReason);
                        }
                        // Re-read the state: a file that changed while being checked mustn't be marked as passed.
                        String after = fileState(bookFile);
                        if (after != null && after.equals(state)) {
                            checks.record(BookFileCheckType.INTEGRITY, bookFile.getId(), state,
                                    failureReason == null ? BookFileCheckResult.OK : BookFileCheckResult.NEEDS_FIXING);
                        }
                    }
                }
                progress(taskId, (int) (100L * (i + 1) / files.size()),
                        String.format("Checked %d of %d files, %d corrupt so far", i + 1, files.size(), bad), TaskStatus.IN_PROGRESS, false);
            }
            String summary = String.format("Checked %d file(s), found %d corrupt.", checked, bad)
                    + (unchanged > 0 ? " " + unchanged + " already checked and unchanged since were skipped." : "");
            lastResult = summary;
            log.info("{}: {}", getTaskType(), summary);
            progress(taskId, 100, summary, TaskStatus.COMPLETED, true);
            builder.status(TaskStatus.COMPLETED);
        } catch (Exception e) {
            log.error("{}: Error during integrity check (checked {} before failing)", getTaskType(), checked, e);
            progress(taskId, 100, "Failed: " + e.getMessage(), TaskStatus.FAILED, true);
            builder.status(TaskStatus.FAILED);
        }

        long endTime = System.currentTimeMillis();
        log.info("{}: Task completed. Duration: {} ms", getTaskType(), endTime - startTime);

        return builder.build();
    }

    private static final String SKIPPED = "skipped";

    /**
     * What the file looks like on disk now - its recorded hash plus its size and modified time (for a
     * folder-based audiobook, the total size and latest modified time of its files) - so a file
     * damaged or replaced outside Trove is checked again even before a rescan updates its hash.
     * Null if the file can't be read.
     */
    private static String fileState(BookFileEntity bookFile) {
        try {
            Path path = bookFile.getFullFilePath();
            long size;
            long modified;
            if (Files.isDirectory(path)) {
                size = 0;
                modified = 0;
                try (Stream<Path> files = Files.walk(path)) {
                    for (Path file : files.filter(Files::isRegularFile).toList()) {
                        size += Files.size(file);
                        modified = Math.max(modified, Files.getLastModifiedTime(file).toMillis());
                    }
                }
            } else {
                size = Files.size(path);
                modified = Files.getLastModifiedTime(path).toMillis();
            }
            return bookFile.getCurrentHash() + ":" + size + ":" + modified;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * @return null if the file is intact, {@link #SKIPPED} if its format has no deeper check,
     * otherwise a short description of what went wrong.
     */
    private String checkIntegrity(BookFileEntity bookFile) {
        Path path;
        try {
            path = bookFile.getFullFilePath();
        } catch (Exception e) {
            return "could not resolve file path: " + e.getMessage();
        }

        if (!Files.exists(path)) {
            return "file missing on disk";
        }
        if (!Files.isDirectory(path)) {
            return BookFileIntegrity.isCheckable(path) ? BookFileIntegrity.check(path) : SKIPPED;
        }

        // Folder-based audiobook: check each file that has a deeper check.
        try (Stream<Path> files = Files.walk(path)) {
            List<Path> checkable = files.filter(Files::isRegularFile).filter(BookFileIntegrity::isCheckable).sorted().toList();
            if (checkable.isEmpty()) {
                return SKIPPED;
            }
            for (Path file : checkable) {
                String problem = BookFileIntegrity.check(file);
                if (problem != null) {
                    return path.relativize(file) + ": " + problem;
                }
            }
            return null;
        } catch (Exception e) {
            return "could not read folder: " + e.getMessage();
        }
    }

    private void reportBadFile(BookFileEntity bookFile, String reason) {
        Long bookId = bookFile.getBook() != null ? bookFile.getBook().getId() : null;
        String path;
        try {
            path = bookFile.getFullFilePath().toString();
        } catch (Exception e) {
            path = bookFile.getFileName();
        }

        log.warn("{}: Corrupt file detected - bookId={} bookFileId={} path={} reason={}",
                getTaskType(), bookId, bookFile.getId(), path, reason);

        auditService.log(AuditAction.BOOK_FILE_INTEGRITY_FAILED, "Book", bookId,
                "Corrupt " + bookFile.getBookType() + " file, needs re-import: " + path + " (" + reason + ")");
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
        return TaskType.CHECK_EBOOK_INTEGRITY;
    }
}
