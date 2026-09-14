package org.booklore.task.tasks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.TaskType;
import org.booklore.model.enums.UserPermission;
import org.booklore.model.websocket.TaskProgressPayload;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookMetadataRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.metadata.PageCountService;
import org.booklore.task.TaskStatus;
import org.booklore.util.PageCounter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Gives books with no page count one worked out from their files (see {@link PageCountService}).
 * Before estimating from word counts it measures words per page on up to
 * {@value #CALIBRATION_SAMPLE} of the library's books that already have a page count, so the
 * estimates follow this library's editions rather than a fixed figure. Page counts that exist or
 * are locked are never touched, and estimates are marked as such.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PageCountFillTask implements Task {

    static final int CALIBRATION_SAMPLE = 300;
    private static final long MIN_NOTIFICATION_INTERVAL_MS = 500;

    private final BookMetadataRepository bookMetadataRepository;
    private final BookAdditionalFileRepository bookFileRepository;
    private final PageCountService pageCountService;
    private final NotificationService notificationService;

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
            List<Long> missing = bookMetadataRepository.findBookIdsMissingPageCount();
            if (missing.isEmpty()) {
                progress(taskId, 100, "Every book already has a page count.", TaskStatus.COMPLETED, true);
                return response.status(TaskStatus.COMPLETED).build();
            }

            progress(taskId, 0, "Measuring words per page on books that have a page count...", TaskStatus.IN_PROGRESS, true);
            Calibration calibration = calibrate(taskId);
            log.info("{}: {} book(s) without a page count; estimating {}", getTaskType(), missing.size(), calibration.describe());

            Map<PageCounter.Source, Integer> filled = new EnumMap<>(PageCounter.Source.class);
            int notCounted = 0;
            for (int i = 0; i < missing.size(); i++) {
                Long bookId = missing.get(i);
                try {
                    Optional<PageCounter.Result> result = pageCountService.count(files(bookId), calibration.wordsPerPage());
                    if (result.isPresent() && bookMetadataRepository.fillMissingPageCount(bookId, result.get().pages(),
                            result.get().source() == PageCounter.Source.WORD_COUNT) > 0) {
                        filled.merge(result.get().source(), 1, Integer::sum);
                    } else {
                        notCounted++;
                    }
                } catch (Exception e) {
                    notCounted++;
                    log.warn("{}: Could not count pages for book {}: {}", getTaskType(), bookId, e.getMessage());
                }
                progress(taskId, 20 + (int) (80L * (i + 1) / missing.size()),
                        String.format("Counting pages: %d of %d books", i + 1, missing.size()), TaskStatus.IN_PROGRESS, false);
            }

            String summary = summary(missing.size(), filled, notCounted, calibration);
            log.info("{}: {} ({} ms)", getTaskType(), summary, System.currentTimeMillis() - start);
            progress(taskId, 100, summary, TaskStatus.COMPLETED, true);
            return response.status(TaskStatus.COMPLETED).build();
        } catch (Exception e) {
            log.error("{}: Failed", getTaskType(), e);
            progress(taskId, 100, "Failed: " + e.getMessage(), TaskStatus.FAILED, true);
            return response.status(TaskStatus.FAILED).build();
        }
    }

    record Calibration(int wordsPerPage, int measuredBooks) {
        String describe() {
            return measuredBooks > 0
                    ? String.format("at %d words per page, measured from %d of your books", wordsPerPage, measuredBooks)
                    : String.format("at %d words per page, the default, as too few books have a page count to measure", wordsPerPage);
        }
    }

    /** Words per page from an even spread of books whose page count came from metadata. */
    private Calibration calibrate(String taskId) {
        List<Long> known = bookMetadataRepository.findBookIdsWithKnownPageCountAndText();
        List<Long> sample = spread(known, CALIBRATION_SAMPLE);
        List<Double> ratios = new ArrayList<>();
        for (int i = 0; i < sample.size(); i++) {
            Long bookId = sample.get(i);
            Integer pages = bookMetadataRepository.findPageCount(bookId);
            long words = pageCountService.countWords(files(bookId));
            if (pages != null && pages > 0 && words > 0) {
                ratios.add((double) words / pages);
            }
            progress(taskId, (int) (20L * (i + 1) / sample.size()),
                    String.format("Measuring words per page: %d of %d books", i + 1, sample.size()), TaskStatus.IN_PROGRESS, false);
        }
        return PageCountService.medianWordsPerPage(ratios)
                .stream()
                .mapToObj(wordsPerPage -> new Calibration(wordsPerPage, ratios.size()))
                .findFirst()
                .orElse(new Calibration(PageCounter.WORDS_PER_PAGE, 0));
    }

    /** Up to {@code max} items spread evenly through the list, so the sample isn't just the oldest books. */
    static <T> List<T> spread(List<T> items, int max) {
        if (items.size() <= max) {
            return items;
        }
        double step = (double) items.size() / max;
        return IntStream.range(0, max).mapToObj(i -> items.get((int) (i * step))).collect(Collectors.toList());
    }

    private List<BookFileEntity> files(Long bookId) {
        return bookFileRepository.findByBookIdAndIsBookFormat(bookId, true);
    }

    static String summary(int total, Map<PageCounter.Source, Integer> filled, int notCounted, Calibration calibration) {
        int exact = filled.getOrDefault(PageCounter.Source.PDF_PAGES, 0) + filled.getOrDefault(PageCounter.Source.COMIC_PAGES, 0)
                + filled.getOrDefault(PageCounter.Source.FIXED_LAYOUT, 0);
        int printed = filled.getOrDefault(PageCounter.Source.PRINT_PAGE_NUMBERS, 0);
        int estimated = filled.getOrDefault(PageCounter.Source.WORD_COUNT, 0);
        StringBuilder text = new StringBuilder(String.format("Filled page counts for %d of %d books: %d counted exactly (PDF, comic, fixed layout), %d from the print edition's page numbers, %d estimated from word count",
                exact + printed + estimated, total, exact, printed, estimated));
        if (estimated > 0) {
            text.append(' ').append(calibration.describe());
        }
        if (notCounted > 0) {
            text.append(". ").append(notCounted).append(" couldn't be counted (MOBI or AZW3 only, or the file couldn't be read)");
        }
        return text.append('.').toString();
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
        long missing = bookMetadataRepository.countBooksMissingPageCount();
        return "Book" + (missing != 1 ? "s" : "") + " without a page count: " + missing;
    }

    @Override
    public TaskType getTaskType() {
        return TaskType.FILL_MISSING_PAGE_COUNTS;
    }
}
