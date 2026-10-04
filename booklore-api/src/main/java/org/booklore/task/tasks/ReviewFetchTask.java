package org.booklore.task.tasks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.mapper.BookMapper;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.BookReview;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.model.enums.TaskType;
import org.booklore.model.enums.UserPermission;
import org.booklore.model.websocket.TaskProgressPayload;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookMetadataRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.book.BookReviewService;
import org.booklore.service.metadata.BookReviewUpdateService;
import org.booklore.service.metadata.parser.MetadataProviderGuard;
import org.booklore.task.TaskCancellationManager;
import org.booklore.task.TaskStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Fetches reviews for books that have none, from the review sources turned on under Public Reviews,
 * so a schedule can fill them in over time instead of waiting for each book to be opened. Every book
 * asked about is stamped ({@code reviews_fetched_at}); one that came back empty is left for
 * {@link BookReviewService#RECHECK_AFTER} before it's asked again.
 * <p>
 * Requests go through the providers' usual pacing ({@link MetadataProviderGuard}). A run stops after
 * {@link #MAX_RUN} or when every source is blocking requests, and the next run carries on from the
 * books not yet asked, so a large library is worked through over several nights.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewFetchTask implements Task {

    static final Duration MAX_RUN = Duration.ofHours(3);
    private static final long MIN_NOTIFICATION_INTERVAL_MS = 500;

    private final BookMetadataRepository bookMetadataRepository;
    private final BookRepository bookRepository;
    private final BookMapper bookMapper;
    private final BookReviewService bookReviewService;
    private final BookReviewUpdateService bookReviewUpdateService;
    private final MetadataProviderGuard providerGuard;
    private final PlatformTransactionManager transactionManager;
    private final NotificationService notificationService;
    private final TaskCancellationManager cancellationManager;

    private long lastNotification;

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
            List<MetadataProvider> providers = bookReviewService.enabledReviewProviders();
            if (providers.isEmpty()) {
                progress(taskId, 100, "Review downloads are turned off, or no review source is enabled.", TaskStatus.COMPLETED, true);
                return response.status(TaskStatus.COMPLETED).build();
            }
            List<Long> bookIds = bookMetadataRepository.findBookIdsMissingReviews(Instant.now().minus(BookReviewService.RECHECK_AFTER));
            if (bookIds.isEmpty()) {
                progress(taskId, 100, "No books are due a review check.", TaskStatus.COMPLETED, true);
                return response.status(TaskStatus.COMPLETED).build();
            }

            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            long deadline = start + MAX_RUN.toMillis();
            int asked = 0;
            int withReviews = 0;
            int failed = 0;
            String stopReason = null;
            for (int i = 0; i < bookIds.size(); i++) {
                Long bookId = bookIds.get(i);
                if (i > 0) {
                    progress(taskId, (int) (100L * i / bookIds.size()),
                            String.format("Checked %d of %d books, found reviews for %d", i, bookIds.size(), withReviews),
                            TaskStatus.IN_PROGRESS, false);
                }
                if (cancellationManager.isTaskCancelled(taskId)) {
                    stopReason = "cancelled";
                    break;
                }
                if (System.currentTimeMillis() > deadline) {
                    stopReason = "reached the " + MAX_RUN.toHours() + " hour limit for one run";
                    break;
                }
                if (allBlocked(providers)) {
                    stopReason = "every review source is blocking requests for now";
                    break;
                }
                try {
                    Book book = tx.execute(status -> loadIfStillMissing(bookId));
                    if (book == null) {
                        continue;
                    }
                    List<BookReview> reviews = bookReviewService.fetchBookReviews(book);
                    if (reviews.isEmpty() && anyBlocked(providers)) {
                        // A source turned us away rather than finding nothing; ask again next run.
                        continue;
                    }
                    tx.executeWithoutResult(status -> saveReviews(bookId, reviews));
                    asked++;
                    if (!reviews.isEmpty()) {
                        withReviews++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.warn("{}: Could not fetch reviews for book {}: {}", getTaskType(), bookId, e.getMessage());
                }
            }

            String summary = summary(bookIds.size(), asked, withReviews, failed, stopReason);
            log.info("{}: {} ({} ms)", getTaskType(), summary, System.currentTimeMillis() - start);
            progress(taskId, 100, summary, TaskStatus.COMPLETED, true);
            return response.status(TaskStatus.COMPLETED).build();
        } catch (Exception e) {
            log.error("{}: Failed", getTaskType(), e);
            progress(taskId, 100, "Failed: " + e.getMessage(), TaskStatus.FAILED, true);
            return response.status(TaskStatus.FAILED).build();
        }
    }

    /** The book, unless it was deleted, got reviews or had them locked since the list was made. */
    private Book loadIfStillMissing(Long bookId) {
        BookEntity book = bookRepository.findAllWithMetadataByIds(Set.of(bookId)).stream().findFirst().orElse(null);
        if (book == null || book.getMetadata() == null || Boolean.TRUE.equals(book.getDeleted())
                || Boolean.TRUE.equals(book.getMetadata().getReviewsLocked()) || !book.getMetadata().getReviews().isEmpty()) {
            return null;
        }
        return bookMapper.toBook(book);
    }

    private void saveReviews(Long bookId, List<BookReview> reviews) {
        bookRepository.findAllWithMetadataByIds(Set.of(bookId)).stream().findFirst().ifPresent(book -> {
            if (!reviews.isEmpty()) {
                bookReviewUpdateService.addReviewsToBook(reviews, book.getMetadata());
            }
            book.getMetadata().setReviewsFetchedAt(Instant.now());
            bookRepository.save(book);
        });
    }

    private boolean allBlocked(List<MetadataProvider> providers) {
        return providers.stream().allMatch(providerGuard::isBlocked);
    }

    private boolean anyBlocked(List<MetadataProvider> providers) {
        return providers.stream().anyMatch(providerGuard::isBlocked);
    }

    static String summary(int due, int asked, int withReviews, int failed, String stopReason) {
        StringBuilder text = new StringBuilder(String.format("Checked %d of %d books due a review check: found reviews for %d",
                asked, due, withReviews));
        if (asked > withReviews) {
            text.append(String.format(", %d had none and will be checked again in %d days", asked - withReviews,
                    BookReviewService.RECHECK_AFTER.toDays()));
        }
        if (failed > 0) {
            text.append(String.format(", %d failed", failed));
        }
        text.append('.');
        if (stopReason != null) {
            text.append(" Stopped early: ").append(stopReason).append("; the next run carries on.");
        }
        return text.toString();
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

    @Override
    public String getMetadata() {
        long missing = bookMetadataRepository.countBooksMissingReviews();
        return "Book" + (missing != 1 ? "s" : "") + " without reviews: " + missing;
    }

    @Override
    public TaskType getTaskType() {
        return TaskType.FETCH_MISSING_REVIEWS;
    }
}
