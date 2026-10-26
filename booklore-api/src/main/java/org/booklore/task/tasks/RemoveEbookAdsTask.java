package org.booklore.task.tasks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.AppProperties;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.AuditAction;
import org.booklore.model.enums.TaskType;
import org.booklore.model.enums.UserPermission;
import org.booklore.model.websocket.TaskProgressPayload;
import org.booklore.model.websocket.Topic;
import org.booklore.service.NotificationService;
import org.booklore.service.audit.AuditService;
import org.booklore.service.file.EbookAdCleanerService;
import org.booklore.task.TaskCancellationManager;
import org.booklore.task.TaskStatus;
import org.booklore.task.options.EbookAdOptions;
import org.booklore.util.EpubAdCleaner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Removes the OceanofPDF advert from the library's EPUBs in two stages: first every EPUB is checked,
 * then the ones carrying the advert are cleaned, replacing the file in place and keeping the original
 * as a backup. Each cleaned book gets an audit log entry naming its backup. Where book files can't be
 * changed, only the first stage runs. Can be limited to some books with {@link EbookAdOptions};
 * otherwise files an earlier run found clean or cleaned, and unchanged since, are skipped.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RemoveEbookAdsTask implements Task {

    private static final long MIN_NOTIFICATION_INTERVAL_MS = 500;
    /** Share of the progress bar for checking; cleaning, of far fewer files, gets the rest. */
    private static final int CHECK_SHARE = 80;

    private final EbookAdCleanerService cleanerService;
    private final NotificationService notificationService;
    private final TaskCancellationManager cancellationManager;
    private final AuditService auditService;
    private final AppProperties appProperties;

    private long lastNotification;
    private volatile String lastResult;

    /** Running totals for one run. */
    static final class Tally {
        int checked;
        int withAds;
        int blocks;
        int strayFiles;
        int otherMentions;
        int cleaned;
        int failed;
        int notCleaned;
        int skipped;
        boolean cancelled;
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
            EbookAdOptions options = request != null ? request.getOptionsAs(EbookAdOptions.class) : null;
            EbookAdCleanerService.Candidates candidates = cleanerService.candidates(options != null ? options.getBookIds() : null);
            Tally tally = new Tally();
            tally.skipped = candidates.skipped();

            List<BookFileEntity> withAds = find(taskId, candidates.files(), tally);
            boolean canChangeFiles = appProperties.isLocalStorage();
            if (!tally.cancelled && canChangeFiles) {
                clean(taskId, withAds, tally);
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

    /** Stage 1: checks every file, returning those carrying the advert. */
    private List<BookFileEntity> find(String taskId, List<BookFileEntity> files, Tally tally) {
        List<BookFileEntity> withAds = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            if (cancellationManager.isTaskCancelled(taskId)) {
                log.info("{}: Cancelled after checking {} of {} file(s)", getTaskType(), i, files.size());
                tally.cancelled = true;
                break;
            }
            BookFileEntity bookFile = files.get(i);
            try {
                EpubAdCleaner.Findings findings = cleanerService.scan(bookFile);
                tally.checked++;
                tally.otherMentions += findings.otherMentions();
                if (findings.hasAds()) {
                    withAds.add(bookFile);
                    tally.withAds++;
                    tally.blocks += findings.adBlocks();
                    tally.strayFiles += findings.strayFile() ? 1 : 0;
                    log.info("{}: bookId={} has {} OceanofPDF link(s){}{}: {}", getTaskType(), bookFile.getBook().getId(),
                            findings.adBlocks(), findings.strayFile() ? " and the stray oceanofpdf.com file" : "",
                            findings.otherMentions() > 0 ? ", plus " + findings.otherMentions() + " other mention(s) that won't be removed" : "",
                            describePath(bookFile));
                }
            } catch (Exception e) {
                tally.failed++;
                log.warn("{}: Could not read bookId={} path={}: {}", getTaskType(), bookFile.getBook().getId(),
                        describePath(bookFile), e.getMessage());
            }
            progress(taskId, (int) ((long) CHECK_SHARE * (i + 1) / files.size()),
                    String.format("Stage 1 of 2: checked %d of %d EPUBs, %d with ads so far", i + 1, files.size(), tally.withAds),
                    TaskStatus.IN_PROGRESS, false);
        }
        return withAds;
    }

    /** Stage 2: cleans the files stage 1 found the advert in. */
    private void clean(String taskId, List<BookFileEntity> files, Tally tally) {
        for (int i = 0; i < files.size(); i++) {
            if (cancellationManager.isTaskCancelled(taskId)) {
                log.info("{}: Cancelled after cleaning {} of {} file(s)", getTaskType(), i, files.size());
                tally.cancelled = true;
                break;
            }
            BookFileEntity bookFile = files.get(i);
            Long bookId = bookFile.getBook().getId();
            String path = describePath(bookFile);
            try {
                EbookAdCleanerService.Cleaned cleaned = cleanerService.clean(bookFile);
                if (cleaned != null) {
                    tally.cleaned++;
                    log.info("{}: Removed {} OceanofPDF link(s) from bookId={} path={} (original kept at {})",
                            getTaskType(), cleaned.findings().adBlocks(), bookId, path, cleaned.backup());
                    auditService.log(AuditAction.EBOOK_ADS_REMOVED, "Book", bookId,
                            "Removed " + plural(cleaned.findings().adBlocks(), "OceanofPDF link")
                                    + (cleaned.findings().strayFile() ? " and the stray oceanofpdf.com file" : "")
                                    + " from " + path + "; original kept at " + cleaned.backup());
                }
            } catch (Exception e) {
                tally.notCleaned++;
                log.warn("{}: Could not clean bookId={} path={}: {}", getTaskType(), bookId, path, e.getMessage());
            }
            progress(taskId, CHECK_SHARE + (int) ((long) (100 - CHECK_SHARE) * (i + 1) / files.size()),
                    String.format("Stage 2 of 2: cleaned %d of %d EPUBs with ads", i + 1, files.size()), TaskStatus.IN_PROGRESS, false);
        }
    }

    static String summary(Tally tally, boolean canChangeFiles) {
        String text;
        if (tally.withAds == 0) {
            text = String.format("None of the %d EPUBs checked carry OceanofPDF ads.", tally.checked);
        } else if (!canChangeFiles) {
            text = String.format("%s of %d EPUBs carry OceanofPDF ads (%s, %s), but book files can't be changed on this server's storage, so they were left as they are. The books are listed under Settings > Server Logs.",
                    plural(tally.withAds, "book"), tally.checked, plural(tally.blocks, "link"), plural(tally.strayFiles, "stray file"));
        } else {
            text = String.format("Removed OceanofPDF ads from %s of the %d EPUBs that had them, out of %d checked (%s, %s). Originals are kept in the ad-cleaner-backups folder.",
                    plural(tally.cleaned, "book"), tally.withAds, tally.checked, plural(tally.blocks, "link"), plural(tally.strayFiles, "stray file"));
        }
        if (tally.otherMentions > 0) {
            text += " " + plural(tally.otherMentions, "other mention") + " of OceanofPDF were left in.";
        }
        if (tally.failed > 0) {
            text += " " + plural(tally.failed, "file") + " couldn't be read.";
        }
        if (tally.notCleaned > 0) {
            text += " " + plural(tally.notCleaned, "file") + " couldn't be cleaned; see Settings > Server Logs.";
        }
        if (tally.cancelled) {
            text += " Cancelled before the end; the next run carries on.";
        }
        if (tally.skipped > 0) {
            text += " " + plural(tally.skipped, "EPUB") + " already checked and unchanged since were skipped.";
        }
        return text;
    }

    private static String describePath(BookFileEntity bookFile) {
        try {
            return bookFile.getFullFilePath().toString();
        } catch (Exception e) {
            return bookFile.getFileName();
        }
    }

    private static String plural(int n, String word) {
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

    @Override
    public TaskType getTaskType() {
        return TaskType.REMOVE_EBOOK_ADS;
    }
}
