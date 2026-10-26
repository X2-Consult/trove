package org.booklore.task.tasks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.model.enums.TaskType;
import org.booklore.model.enums.UserPermission;
import org.booklore.model.websocket.TaskProgressPayload;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookMetadataRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.metadata.parser.AmazonBookParser;
import org.booklore.service.metadata.parser.GoodReadsParser;
import org.booklore.service.metadata.parser.MetadataProviderGuard;
import org.booklore.service.series.SeriesGrouping;
import org.booklore.task.TaskCancellationManager;
import org.booklore.task.TaskStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Fills in the series' Goodreads ID and Amazon series ASIN for books that have a series name and a
 * Goodreads ID or ASIN but not the series ID it would find, so existing books group the way newly
 * fetched ones do ({@link SeriesGrouping}). An ID is only taken when the source names the same
 * series as the book, and IDs already there are never replaced - which is also why a locked series
 * name doesn't stop it: it only adds what belongs with the name that's there.
 * <p>
 * Every book asked about is stamped, and one that came back without an ID is left for
 * {@link #RECHECK_AFTER}. Requests go through the providers' usual pacing; a run stops after
 * {@link #MAX_RUN} or when both sources are blocking, and the next run carries on.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeriesIdFetchTask implements Task {

    static final Duration MAX_RUN = Duration.ofHours(3);
    static final Duration RECHECK_AFTER = Duration.ofDays(90);
    private static final long MIN_NOTIFICATION_INTERVAL_MS = 500;

    private final BookMetadataRepository bookMetadataRepository;
    private final GoodReadsParser goodReadsParser;
    private final AmazonBookParser amazonBookParser;
    private final MetadataProviderGuard providerGuard;
    private final PlatformTransactionManager transactionManager;
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
        TaskCreateResponse.TaskCreateResponseBuilder response = TaskCreateResponse.builder().taskId(taskId).taskType(getTaskType());
        lastNotification = 0;
        long start = System.currentTimeMillis();

        try {
            List<Long> bookIds = bookMetadataRepository.findBookIdsMissingSeriesIds(Instant.now().minus(RECHECK_AFTER));
            if (bookIds.isEmpty()) {
                finish(taskId, "No series books are due a series ID check.");
                return response.status(TaskStatus.COMPLETED).build();
            }

            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            long deadline = start + MAX_RUN.toMillis();
            int asked = 0;
            int found = 0;
            int failed = 0;
            String stopReason = null;
            for (int i = 0; i < bookIds.size(); i++) {
                Long bookId = bookIds.get(i);
                if (i > 0) {
                    progress(taskId, (int) (100L * i / bookIds.size()),
                            String.format("Checked %d of %d books, found series IDs for %d", i, bookIds.size(), found), TaskStatus.IN_PROGRESS, false);
                }
                if (cancellationManager.isTaskCancelled(taskId)) {
                    stopReason = "cancelled";
                    break;
                }
                if (System.currentTimeMillis() > deadline) {
                    stopReason = "reached the " + MAX_RUN.toHours() + " hour limit for one run";
                    break;
                }
                if (providerGuard.isBlocked(MetadataProvider.GoodReads) && providerGuard.isBlocked(MetadataProvider.Amazon)) {
                    stopReason = "Goodreads and Amazon are both blocking requests for now";
                    break;
                }
                try {
                    BookMetadataEntity book = tx.execute(status -> bookMetadataRepository.findById(bookId).orElse(null));
                    if (book == null || book.getSeriesName() == null) {
                        continue;
                    }
                    Lookup lookup = lookUp(book);
                    if (lookup.blocked() && lookup.goodreadsId() == null && lookup.asin() == null) {
                        continue; // turned away rather than told; ask again next run
                    }
                    tx.executeWithoutResult(status -> save(bookId, book.getSeriesName(), lookup));
                    asked++;
                    if (lookup.goodreadsId() != null || lookup.asin() != null) {
                        found++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.warn("{}: Could not look up the series IDs of book {}: {}", getTaskType(), bookId, e.getMessage());
                }
            }

            String summary = String.format("Asked about %d of %d books, found series IDs for %d", asked, bookIds.size(), found)
                    + (failed > 0 ? String.format(", %d failed", failed) : "")
                    + (stopReason != null ? ". Stopped early: " + stopReason + "; the next run carries on." : ".");
            log.info("{}: {} ({} ms)", getTaskType(), summary, System.currentTimeMillis() - start);
            finish(taskId, summary);
            return response.status(TaskStatus.COMPLETED).build();
        } catch (Exception e) {
            log.error("{}: Failed", getTaskType(), e);
            progress(taskId, 100, "Failed: " + e.getMessage(), TaskStatus.FAILED, true);
            return response.status(TaskStatus.FAILED).build();
        }
    }

    record Lookup(String goodreadsId, String asin, boolean blocked) {
    }

    private Lookup lookUp(BookMetadataEntity book) {
        String goodreadsId = null;
        String asin = null;
        boolean blocked = false;
        if (book.getSeriesGoodreadsId() == null && hasText(book.getGoodreadsId())) {
            if (providerGuard.isBlocked(MetadataProvider.GoodReads)) {
                blocked = true;
            } else {
                goodreadsId = seriesIdIfSameSeries(goodReadsParser.fetchDetailedMetadata(book.getGoodreadsId()), book.getSeriesName(), true);
                blocked |= goodreadsId == null && providerGuard.isBlocked(MetadataProvider.GoodReads);
            }
        }
        if (book.getSeriesAsin() == null && hasText(book.getAsin())) {
            if (providerGuard.isBlocked(MetadataProvider.Amazon)) {
                blocked = true;
            } else {
                asin = seriesIdIfSameSeries(amazonBookParser.fetchDetailedMetadata(book.getAsin()), book.getSeriesName(), false);
                blocked |= asin == null && providerGuard.isBlocked(MetadataProvider.Amazon);
            }
        }
        return new Lookup(goodreadsId, asin, blocked);
    }

    static String seriesIdIfSameSeries(BookMetadata fetched, String seriesName, boolean goodreads) {
        if (fetched == null || fetched.getSeriesName() == null
                || !SeriesGrouping.normalize(fetched.getSeriesName()).equals(SeriesGrouping.normalize(seriesName))) {
            return null;
        }
        String id = goodreads ? fetched.getSeriesGoodreadsId() : fetched.getSeriesAsin();
        return hasText(id) ? id.strip() : null;
    }

    /**
     * Fills only what's still missing, for the series name the book had when it was asked about. The
     * stamp only goes on when neither source turned the request away, so a blocked one is asked again.
     */
    private void save(Long bookId, String askedFor, Lookup lookup) {
        bookMetadataRepository.findById(bookId).ifPresent(m -> {
            if (m.getSeriesName() == null || !SeriesGrouping.normalize(m.getSeriesName()).equals(SeriesGrouping.normalize(askedFor))) {
                return; // renamed meanwhile
            }
            if (lookup.goodreadsId() != null && m.getSeriesGoodreadsId() == null) {
                m.setSeriesGoodreadsId(lookup.goodreadsId());
            }
            if (lookup.asin() != null && m.getSeriesAsin() == null) {
                m.setSeriesAsin(lookup.asin());
            }
            if (!lookup.blocked()) {
                m.setSeriesIdsCheckedAt(Instant.now());
            }
            bookMetadataRepository.save(m);
        });
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private void finish(String taskId, String summary) {
        lastResult = summary;
        progress(taskId, 100, summary, TaskStatus.COMPLETED, true);
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
        return TaskType.FETCH_SERIES_IDS;
    }
}
