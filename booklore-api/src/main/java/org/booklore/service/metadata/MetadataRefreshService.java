package org.booklore.service.metadata;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.ApiError;
import org.booklore.mapper.BookMapper;
import org.booklore.model.MetadataUpdateContext;
import org.booklore.model.MetadataUpdateWrapper;
import org.booklore.model.dto.*;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.booklore.model.dto.request.MetadataRefreshOptions;
import org.booklore.model.dto.request.MetadataRefreshRequest;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.LibraryEntity;
import org.booklore.model.entity.MetadataFetchJobEntity;
import org.booklore.model.entity.MetadataFetchProposalEntity;
import org.booklore.model.enums.FetchedMetadataProposalStatus;
import org.booklore.model.enums.MetadataFetchTaskStatus;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.model.enums.MetadataReplaceMode;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookRepository;
import org.booklore.repository.LibraryRepository;
import org.booklore.repository.MetadataFetchJobRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.metadata.parser.BookParser;
import org.booklore.service.metadata.parser.MetadataProviderGuard;
import org.booklore.task.TaskCancellationManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.booklore.model.enums.MetadataProvider.*;

@Slf4j
@AllArgsConstructor
@Service
public class MetadataRefreshService {

    // A scraped provider can start blocking part-way through a batch; its parser then skips it
    // for a cooldown (see MetadataProviderGuard). Say so once per batch instead of letting those
    // books quietly come back without that provider's fields.
    private static final Map<MetadataProvider, String> BLOCKED_PROVIDER_WARNINGS = Map.of(
            Amazon, "Amazon started blocking requests (bot check) at book %d, so Amazon fields are skipped while the block lasts.",
            GoodReads, "GoodReads started blocking book pages at book %d, so GoodReads fields come from limited search data while the block lasts.",
            Google, "Google Books hit its rate limit at book %d, so Google fields are skipped for now. Adding a Google Books API key in the metadata provider settings avoids this.");

    private record BookRefreshPlan(Book book, String title, String identifier,
                                   MetadataRefreshOptions refreshOptions, List<MetadataProvider> providers) {}

    private final LibraryRepository libraryRepository;
    private final MetadataFetchJobRepository metadataFetchJobRepository;
    private final BookMapper bookMapper;
    private final BookMetadataUpdater bookMetadataUpdater;
    private final NotificationService notificationService;
    private final AppSettingService appSettingService;
    private final Map<MetadataProvider, BookParser> parserMap;
    private final ObjectMapper objectMapper;
    private final BookRepository bookRepository;
    private final PlatformTransactionManager transactionManager;
    private final AuthenticationService authenticationService;
    private final TaskCancellationManager cancellationManager;
    private final MetadataProviderGuard providerGuard;


    public void refreshMetadata(MetadataRefreshRequest request, String jobId) {
        BookLoreUser user = authenticationService.getAuthenticatedUser();
        Long userId = user != null ? user.getId() : null;
        final Set<Long> bookIds = null;
        final int totalBooks;
        try {
            AppSettings appSettings = appSettingService.getAppSettings();

            final boolean isLibraryRefresh = request.getRefreshType() == MetadataRefreshRequest.RefreshType.LIBRARY;
            final MetadataRefreshOptions requestRefreshOptions = request.getRefreshOptions();

            final boolean useRequestOptions = requestRefreshOptions != null;
            final MetadataRefreshOptions libraryRefreshOptions = !useRequestOptions && isLibraryRefresh ? resolveMetadataRefreshOptions(request.getLibraryId(), appSettings) : null;
            final List<MetadataProvider> fixedProviders = useRequestOptions ?
                    prepareProviders(requestRefreshOptions) :
                    (isLibraryRefresh ? prepareProviders(libraryRefreshOptions) : null);

            final Set<Long> actualBookIds = getBookEntities(request);
            totalBooks = actualBookIds.size();

            MetadataRefreshOptions reviewModeOptions = requestRefreshOptions != null ?
                    requestRefreshOptions :
                    (libraryRefreshOptions != null ? libraryRefreshOptions : appSettings.getDefaultMetadataRefreshOptions());
            boolean isReviewMode = Boolean.TRUE.equals(reviewModeOptions.getReviewBeforeApply());

            MetadataFetchJobEntity task = MetadataFetchJobEntity.builder()
                    .taskId(jobId)
                    .userId(userId)
                    .status(MetadataFetchTaskStatus.IN_PROGRESS)
                    .startedAt(Instant.now())
                    .totalBooksCount(totalBooks)
                    .completedBooks(0)
                    .build();
            metadataFetchJobRepository.save(task);

            TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
            int completedCount = 0;
            List<String> warnings = new ArrayList<>();
            Set<MetadataProvider> queriedProviders = EnumSet.noneOf(MetadataProvider.class);
            Set<MetadataProvider> reportedBlocked = EnumSet.noneOf(MetadataProvider.class);

            for (Long bookId : actualBookIds) {
                if (cancellationManager.isTaskCancelled(jobId)) {
                    log.info("RefreshMetadataTask {} was cancelled, stopping execution", jobId);
                    cancelTask(task);
                    cancellationManager.clearCancellation(jobId);
                    return;
                }

                int finalCompletedCount = completedCount;
                BookRefreshPlan plan = null;
                try {
                    // Read what the fetch needs in a short transaction, run the slow (paced, possibly
                    // retried) provider requests with no transaction open, then write in a second one.
                    plan = txTemplate.execute(status -> {
                        BookEntity book = bookRepository.findAllWithMetadataByIds(Collections.singleton(bookId))
                                .stream().findFirst()
                                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
                        if (book.getMetadata().areAllFieldsLocked()) {
                            log.info("Skipping locked book: {}", getBookIdentifier(book));
                            sendBatchProgressNotification(jobId, finalCompletedCount, totalBooks, "Skipped locked book: " + book.getMetadata().getTitle(), MetadataFetchTaskStatus.IN_PROGRESS, isReviewMode, warnings);
                            return null;
                        }

                        MetadataRefreshOptions refreshOptions;
                        List<MetadataProvider> providers;

                        if (useRequestOptions) {
                            refreshOptions = requestRefreshOptions;
                            providers = fixedProviders;
                        } else if (isLibraryRefresh) {
                            refreshOptions = libraryRefreshOptions;
                            providers = fixedProviders;
                        } else {
                            refreshOptions = resolveMetadataRefreshOptions(book.getLibrary().getId(), appSettings);
                            providers = prepareProviders(refreshOptions);
                        }
                        return new BookRefreshPlan(bookMapper.toBook(book), book.getMetadata().getTitle(), getBookIdentifier(book), refreshOptions, providers);
                    });

                    if (plan != null) {
                        queriedProviders.addAll(plan.providers());
                        refreshBook(plan, task, jobId, finalCompletedCount, totalBooks, isReviewMode, warnings, txTemplate);
                    }
                } catch (Exception e) {
                    String label = plan != null ? plan.identifier() : "Book ID: " + bookId;
                    if (Thread.currentThread().isInterrupted()) {
                        log.info("Processing interrupted for book: {}", label);
                    } else {
                        log.error("Metadata update failed for book: {}", label, e);
                        String title = plan != null ? plan.title() : label;
                        sendBatchProgressNotification(jobId, finalCompletedCount, totalBooks, String.format("Failed to process: %s - %s", title, e.getMessage()), MetadataFetchTaskStatus.ERROR, isReviewMode, warnings);
                    }
                }
                recordBlockedProviders(queriedProviders, reportedBlocked, warnings, completedCount + 1);
                completedCount++;
            }

            completeTask(task, completedCount, totalBooks, isReviewMode, warnings);
            cancellationManager.clearCancellation(jobId);
            log.info("Metadata refresh task {} completed successfully", jobId);

        } catch (RuntimeException e) {
            cancellationManager.clearCancellation(jobId);
            if (e.getCause() instanceof InterruptedException) {
                log.info("Metadata refresh task {} cancelled successfully", jobId);
                return;
            }
            log.error("Fatal error during metadata refresh", e);
            int totalBooksForError = 0;
            sendBatchProgressNotification(jobId, 0, totalBooksForError, "Fatal error during metadata refresh: " + e.getMessage(), MetadataFetchTaskStatus.ERROR, false);
            throw e;
        } catch (Exception fatal) {
            cancellationManager.clearCancellation(jobId);
            log.error("Fatal error during metadata refresh", fatal);
            int totalBooksForError = bookIds != null ? bookIds.size() : 0;
            sendBatchProgressNotification(jobId, 0, totalBooksForError, "Fatal error during metadata refresh: " + fatal.getMessage(), MetadataFetchTaskStatus.ERROR, false);
            throw fatal;
        }
    }

    MetadataRefreshOptions resolveMetadataRefreshOptions(Long libraryId, AppSettings appSettings) {
        MetadataRefreshOptions defaultOptions = appSettings.getDefaultMetadataRefreshOptions();
        List<MetadataRefreshOptions> libraryOptions = appSettings.getLibraryMetadataRefreshOptions();

        if (libraryId != null && libraryOptions != null) {
            return libraryOptions.stream()
                    .filter(options -> libraryId.equals(options.getLibraryId()))
                    .findFirst()
                    .orElse(defaultOptions);
        }

        return defaultOptions;
    }

    public Map<MetadataProvider, BookMetadata> fetchMetadataForBook(List<MetadataProvider> providers, Book book) {
        return providers.stream()
                .map(provider -> fetchTopMetadataFromAProvider(provider, book))
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        BookMetadata::getProvider,
                        metadata -> metadata,
                        (existing, replacement) -> existing
                ));
    }

    public Map<MetadataProvider, BookMetadata> fetchMetadataForBook(List<MetadataProvider> providers, BookEntity bookEntity) {
        Book book = bookMapper.toBook(bookEntity);
        return providers.stream()
                .map(provider -> fetchTopMetadataFromAProvider(provider, book))
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        BookMetadata::getProvider,
                        metadata -> metadata,
                        (existing, replacement) -> existing
                ));
    }

    private void refreshBook(BookRefreshPlan plan, MetadataFetchJobEntity task, String jobId, int completedCount, int totalBooks,
                             boolean isReviewMode, List<String> warnings, TransactionTemplate txTemplate) {
        reportProgressIfNeeded(task, jobId, completedCount, totalBooks, plan.title(), isReviewMode, warnings);
        Map<MetadataProvider, BookMetadata> metadataMap = fetchMetadataForBook(plan.providers(), plan.book());

        MetadataRefreshOptions refreshOptions = plan.refreshOptions();
        Long bookId = plan.book().getId();
        BookMetadata fetched = buildFetchMetadata(plan.book().getMetadata(), bookId, refreshOptions, metadataMap);
        boolean bookReviewMode = Boolean.TRUE.equals(refreshOptions.getReviewBeforeApply());

        if (bookReviewMode) {
            saveProposal(task, bookId, fetched);
        } else {
            // Use the replaceMode from options - allows user to control whether to replace existing or only fill missing
            MetadataReplaceMode replaceMode = refreshOptions.getReplaceMode() != null
                    ? refreshOptions.getReplaceMode()
                    : MetadataReplaceMode.REPLACE_MISSING;
            txTemplate.executeWithoutResult(status -> {
                BookEntity book = bookRepository.findAllWithMetadataByIds(Collections.singleton(bookId))
                        .stream().findFirst()
                        .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
                updateBookMetadata(book, fetched, refreshOptions.isRefreshCovers(), refreshOptions.isMergeCategories(), replaceMode);
                bookRepository.saveAndFlush(book);
            });
        }

        sendBatchProgressNotification(jobId, completedCount + 1, totalBooks, "Processed: " + plan.title(), MetadataFetchTaskStatus.IN_PROGRESS, bookReviewMode, warnings);
    }

    private void recordBlockedProviders(Set<MetadataProvider> queriedProviders, Set<MetadataProvider> reportedBlocked, List<String> warnings, int bookNumber) {
        BLOCKED_PROVIDER_WARNINGS.forEach((provider, template) -> {
            if (queriedProviders.contains(provider) && !reportedBlocked.contains(provider) && providerGuard.isBlocked(provider)) {
                reportedBlocked.add(provider);
                warnings.add(String.format(template, bookNumber));
            }
        });
    }

    private void reportProgressIfNeeded(MetadataFetchJobEntity task, String taskId, int completedCount, int total, String title, boolean isReviewMode, List<String> warnings) {
        if (task == null) return;
        task.setCompletedBooks(completedCount);
        metadataFetchJobRepository.save(task);
        String message = String.format("Processing '%s'", title);
        sendBatchProgressNotification(taskId, completedCount, total, message, MetadataFetchTaskStatus.IN_PROGRESS, isReviewMode, warnings);
    }

    private String getBookIdentifier(BookEntity book) {
        if (book.getPrimaryBookFile() != null && book.getPrimaryBookFile().getFileName() != null) {
            return book.getPrimaryBookFile().getFileName();
        }
        if (book.getMetadata() != null && book.getMetadata().getTitle() != null) {
            return book.getMetadata().getTitle();
        }
        return "Book ID: " + book.getId();
    }

    private void sendBatchProgressNotification(String taskId, int current, int total, String message, MetadataFetchTaskStatus status, boolean isReview) {
        sendBatchProgressNotification(taskId, current, total, message, status, isReview, List.of());
    }

    private void sendBatchProgressNotification(String taskId, int current, int total, String message, MetadataFetchTaskStatus status, boolean isReview, List<String> warnings) {
        notificationService.sendMessage(Topic.BOOK_METADATA_BATCH_PROGRESS, new MetadataBatchProgressNotification(taskId, current, total, message, status.name(), isReview, List.copyOf(warnings)));
    }

    private void completeTask(MetadataFetchJobEntity task, int completed, int total, boolean isReviewMode, List<String> warnings) {
        task.setStatus(MetadataFetchTaskStatus.COMPLETED);
        task.setCompletedAt(Instant.now());
        task.setCompletedBooks(completed);
        metadataFetchJobRepository.save(task);
        String message = warnings.isEmpty() ? "Batch metadata fetch successfully completed!" : "Batch metadata fetch completed with warnings.";
        sendBatchProgressNotification(task.getTaskId(), completed, total, message, MetadataFetchTaskStatus.COMPLETED, isReviewMode, warnings);
    }

    private void cancelTask(MetadataFetchJobEntity task) {
        task.setStatus(MetadataFetchTaskStatus.CANCELLED);
        task.setCompletedAt(Instant.now());
        metadataFetchJobRepository.save(task);
        sendBatchProgressNotification(task.getTaskId(), task.getCompletedBooks(), task.getTotalBooksCount(), "Task cancelled by user", MetadataFetchTaskStatus.CANCELLED, false);
    }

    private void saveProposal(MetadataFetchJobEntity job, Long bookId, BookMetadata metadata) throws JacksonException {
        MetadataFetchProposalEntity proposal = MetadataFetchProposalEntity.builder()
                .job(job)
                .bookId(bookId)
                .metadataJson(objectMapper.writeValueAsString(metadata))
                .status(FetchedMetadataProposalStatus.FETCHED)
                .fetchedAt(Instant.now())
                .build();
        job.getProposals().add(proposal);
    }


    public void updateBookMetadata(BookEntity bookEntity, BookMetadata metadata, boolean replaceCover, boolean mergeCategories) {
        updateBookMetadata(bookEntity, metadata, replaceCover, mergeCategories, MetadataReplaceMode.REPLACE_MISSING);
    }

    public void updateBookMetadata(BookEntity bookEntity, BookMetadata metadata, boolean replaceCover, boolean mergeCategories, MetadataReplaceMode replaceMode) {
        MetadataUpdateContext context = MetadataUpdateContext.builder()
                .bookEntity(bookEntity)
                .metadataUpdateWrapper(MetadataUpdateWrapper.builder()
                        .metadata(metadata)
                        .build())
                .updateThumbnail(replaceCover)
                .mergeCategories(mergeCategories)
                .replaceMode(replaceMode)
                .mergeMoods(true)
                .mergeTags(true)
                .build();

        updateBookMetadata(context);
    }

    public void updateBookMetadata(MetadataUpdateContext context) {
        if (context.getMetadataUpdateWrapper() != null && context.getMetadataUpdateWrapper().getMetadata() != null) {
            bookMetadataUpdater.setBookMetadata(context);

            Book book = bookMapper.toBookWithDescription(context.getBookEntity(), true);
            
            BookLoreUser user = authenticationService.getAuthenticatedUser();
            if (user != null && book.getShelves() != null) {
                book.setShelves(filterShelvesByUserId(book.getShelves(), user.getId()));
            }
            
            notificationService.sendMessage(Topic.BOOK_METADATA_UPDATE, book);
        }
    }

    public List<MetadataProvider> prepareProviders(MetadataRefreshOptions refreshOptions) {
        AppSettings appSettings = appSettingService.getAppSettings();
        Set<MetadataProvider> allProviders = EnumSet.noneOf(MetadataProvider.class);
        allProviders.addAll(getAllProvidersUsingIndividualFields(refreshOptions, appSettings));
        // Bookshelf is an API-only source with no dedicated ID field, so it never gets pinned
        // to a field-provider slot and would otherwise never be queried. Include it whenever
        // it's enabled so it can contribute to fallback fields (e.g. page count).
        if (appSettings.getMetadataProviderSettings() != null
                && appSettings.getMetadataProviderSettings().getBookshelf() != null
                && appSettings.getMetadataProviderSettings().getBookshelf().isEnabled()) {
            allProviders.add(Bookshelf);
        }
        return new ArrayList<>(allProviders);
    }

    protected Set<MetadataProvider> getAllProvidersUsingIndividualFields(MetadataRefreshOptions refreshOptions, AppSettings appSettings) {
        MetadataRefreshOptions.FieldOptions fieldOptions = refreshOptions.getFieldOptions();
        Set<MetadataProvider> uniqueProviders = EnumSet.noneOf(MetadataProvider.class);

        if (fieldOptions != null) {
            addProviderToSet(fieldOptions.getTitle(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getSubtitle(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getDescription(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getAuthors(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getPublisher(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getPublishedDate(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getSeriesName(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getSeriesNumber(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getSeriesTotal(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getIsbn13(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getIsbn10(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getLanguage(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getCategories(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getCover(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getPageCount(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getAsin(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getGoodreadsId(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getComicvineId(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getHardcoverId(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getGoogleId(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getLubimyczytacId(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getAmazonRating(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getAmazonReviewCount(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getGoodreadsRating(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getGoodreadsReviewCount(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getHardcoverRating(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getHardcoverReviewCount(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getLubimyczytacRating(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getRanobedbId(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getRanobedbRating(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getMoods(), uniqueProviders, appSettings);
            addProviderToSet(fieldOptions.getTags(), uniqueProviders, appSettings);
        }

        return uniqueProviders;
    }

    protected void addProviderToSet(MetadataRefreshOptions.FieldProvider fieldProvider, Set<MetadataProvider> providerSet, AppSettings appSettings) {
        if (fieldProvider != null) {
            if (fieldProvider.getP1() != null && isProviderEnabled(fieldProvider.getP1(), appSettings)) providerSet.add(fieldProvider.getP1());
            if (fieldProvider.getP2() != null && isProviderEnabled(fieldProvider.getP2(), appSettings)) providerSet.add(fieldProvider.getP2());
            if (fieldProvider.getP3() != null && isProviderEnabled(fieldProvider.getP3(), appSettings)) providerSet.add(fieldProvider.getP3());
            if (fieldProvider.getP4() != null && isProviderEnabled(fieldProvider.getP4(), appSettings)) providerSet.add(fieldProvider.getP4());
        }
    }

    protected boolean isProviderEnabled(MetadataProvider provider, AppSettings appSettings) {
        if (provider == null || appSettings == null || appSettings.getMetadataProviderSettings() == null) {
            return true;
        }

        var settings = appSettings.getMetadataProviderSettings();
        return switch (provider) {
            case Amazon -> settings.getAmazon() != null && settings.getAmazon().isEnabled();
            case Google -> settings.getGoogle() != null && settings.getGoogle().isEnabled();
            case GoodReads -> settings.getGoodReads() != null && settings.getGoodReads().isEnabled();
            case Hardcover -> settings.getHardcover() != null && settings.getHardcover().isEnabled();
            case OpenLibrary -> settings.getOpenLibrary() != null && settings.getOpenLibrary().isEnabled();
            case Comicvine -> settings.getComicvine() != null && settings.getComicvine().isEnabled();
            case Ranobedb -> settings.getRanobedb() != null && settings.getRanobedb().isEnabled();
            case Douban -> settings.getDouban() != null && settings.getDouban().isEnabled();
            case Lubimyczytac -> settings.getLubimyczytac() != null && settings.getLubimyczytac().isEnabled();
            case Bookshelf -> settings.getBookshelf() != null && settings.getBookshelf().isEnabled();
            case AppleBooks -> settings.getAppleBooks() != null && settings.getAppleBooks().isEnabled();
            default -> true;
        };
    }

    public BookMetadata fetchTopMetadataFromAProvider(MetadataProvider provider, Book book) {
        return getParser(provider).fetchTopMetadata(book, buildFetchMetadataRequestFromBook(book));
    }

    private BookParser getParser(MetadataProvider provider) {
        BookParser parser = parserMap.get(provider);
        if (parser == null) {
            throw ApiError.METADATA_SOURCE_NOT_IMPLEMENT_OR_DOES_NOT_EXIST.createException();
        }
        return parser;
    }

    private FetchMetadataRequest buildFetchMetadataRequestFromBook(Book book) {
        BookMetadata metadata = book.getMetadata();
        if (metadata == null) {
            return FetchMetadataRequest.builder()
                    .bookId(book.getId())
                    .build();
        }
        String isbn = metadata.getIsbn13();
        if (isbn == null || isbn.isBlank()) {
            isbn = metadata.getIsbn10();
        }
        return FetchMetadataRequest.builder()
                .isbn(isbn)
                .asin(metadata.getAsin())
                .author(metadata.getAuthors() != null ? String.join(", ", metadata.getAuthors()) : null)
                .title(metadata.getTitle())
                .bookId(book.getId())
                .build();
    }

    public BookMetadata buildFetchMetadata(BookMetadata existingMetadata, Long bookId, MetadataRefreshOptions refreshOptions, Map<MetadataProvider, BookMetadata> metadataMap) {
        BookMetadata metadata = BookMetadata.builder().bookId(bookId).build();

        MetadataRefreshOptions.FieldOptions fieldOptions = refreshOptions.getFieldOptions();
        if (fieldOptions == null) {
            fieldOptions = new MetadataRefreshOptions.FieldOptions();
        }

        MetadataRefreshOptions.EnabledFields enabledFields = refreshOptions.getEnabledFields();
        if (enabledFields == null) {
            enabledFields = new MetadataRefreshOptions.EnabledFields();
        }
        
        MetadataReplaceMode replaceMode = refreshOptions.getReplaceMode();
        boolean isReplaceAll = replaceMode == MetadataReplaceMode.REPLACE_ALL;

        if (enabledFields.isTitle()) {
            metadata.setTitle(resolveFieldAsString(metadataMap, fieldOptions.getTitle(), BookMetadata::getTitle));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setTitle(existingMetadata.getTitle());
        }
        
        if (enabledFields.isSubtitle()) {
            metadata.setSubtitle(resolveFieldAsString(metadataMap, fieldOptions.getSubtitle(), BookMetadata::getSubtitle));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setSubtitle(existingMetadata.getSubtitle());
        }
        
        if (enabledFields.isDescription()) {
            metadata.setDescription(resolveFieldAsString(metadataMap, fieldOptions.getDescription(), BookMetadata::getDescription));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setDescription(existingMetadata.getDescription());
        }
        
        if (enabledFields.isAuthors()) {
            metadata.setAuthors(resolveFieldAsList(metadataMap, fieldOptions.getAuthors(), BookMetadata::getAuthors));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setAuthors(existingMetadata.getAuthors());
        }
        
        if (enabledFields.isPublisher()) {
            metadata.setPublisher(resolveFieldAsString(metadataMap, fieldOptions.getPublisher(), BookMetadata::getPublisher));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setPublisher(existingMetadata.getPublisher());
        }
        
        if (enabledFields.isPublishedDate()) {
            metadata.setPublishedDate(resolveField(metadataMap, fieldOptions.getPublishedDate(), BookMetadata::getPublishedDate));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setPublishedDate(existingMetadata.getPublishedDate());
        }
        
        if (enabledFields.isSeriesName()) {
            metadata.setSeriesName(resolveFieldAsString(metadataMap, fieldOptions.getSeriesName(), BookMetadata::getSeriesName));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setSeriesName(existingMetadata.getSeriesName());
        }
        
        if (enabledFields.isSeriesNumber()) {
            metadata.setSeriesNumber(resolveField(metadataMap, fieldOptions.getSeriesNumber(), BookMetadata::getSeriesNumber));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setSeriesNumber(existingMetadata.getSeriesNumber());
        }
        
        if (enabledFields.isSeriesTotal()) {
            metadata.setSeriesTotal(resolveFieldAsInteger(metadataMap, fieldOptions.getSeriesTotal(), BookMetadata::getSeriesTotal));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setSeriesTotal(existingMetadata.getSeriesTotal());
        }
        
        if (enabledFields.isIsbn13()) {
            metadata.setIsbn13(resolveFieldAsString(metadataMap, fieldOptions.getIsbn13(), BookMetadata::getIsbn13));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setIsbn13(existingMetadata.getIsbn13());
        }
        
        if (enabledFields.isIsbn10()) {
            metadata.setIsbn10(resolveFieldAsString(metadataMap, fieldOptions.getIsbn10(), BookMetadata::getIsbn10));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setIsbn10(existingMetadata.getIsbn10());
        }
        
        if (enabledFields.isLanguage()) {
            metadata.setLanguage(resolveFieldAsString(metadataMap, fieldOptions.getLanguage(), BookMetadata::getLanguage));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setLanguage(existingMetadata.getLanguage());
        }
        
        if (enabledFields.isPageCount()) {
            metadata.setPageCount(resolvePageCount(metadataMap, fieldOptions.getPageCount()));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setPageCount(existingMetadata.getPageCount());
        }
        
        if (enabledFields.isCover()) {
            metadata.setThumbnailUrl(resolveFieldAsString(metadataMap, fieldOptions.getCover(), BookMetadata::getThumbnailUrl));
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setThumbnailUrl(existingMetadata.getThumbnailUrl());
        }
        if (enabledFields.isAmazonRating()) {
            if (metadataMap.containsKey(Amazon)) {
                metadata.setAmazonRating(metadataMap.get(Amazon).getAmazonRating());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setAmazonRating(existingMetadata.getAmazonRating());
        }

        if (enabledFields.isAmazonReviewCount()) {
            if (metadataMap.containsKey(Amazon)) {
                metadata.setAmazonReviewCount(metadataMap.get(Amazon).getAmazonReviewCount());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setAmazonReviewCount(existingMetadata.getAmazonReviewCount());
        }

        if (enabledFields.isGoodreadsRating()) {
            if (metadataMap.containsKey(GoodReads)) {
                metadata.setGoodreadsRating(metadataMap.get(GoodReads).getGoodreadsRating());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setGoodreadsRating(existingMetadata.getGoodreadsRating());
        }

        if (enabledFields.isGoodreadsReviewCount()) {
            if (metadataMap.containsKey(GoodReads)) {
                metadata.setGoodreadsReviewCount(metadataMap.get(GoodReads).getGoodreadsReviewCount());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setGoodreadsReviewCount(existingMetadata.getGoodreadsReviewCount());
        }

        if (enabledFields.isHardcoverRating()) {
            if (metadataMap.containsKey(Hardcover)) {
                metadata.setHardcoverRating(metadataMap.get(Hardcover).getHardcoverRating());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setHardcoverRating(existingMetadata.getHardcoverRating());
        }

        if (enabledFields.isHardcoverReviewCount()) {
            if (metadataMap.containsKey(Hardcover)) {
                metadata.setHardcoverReviewCount(metadataMap.get(Hardcover).getHardcoverReviewCount());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setHardcoverReviewCount(existingMetadata.getHardcoverReviewCount());
        }

        if (enabledFields.isAsin()) {
            String asin = resolveFieldAsString(metadataMap, fieldOptions.getAsin(), BookMetadata::getAsin);
            if (asin == null) {
                // No provider priority configured for ASIN (e.g. settings saved before ASIN
                // became a multi-provider field) -- fall back to any fetched provider that
                // returned one, rather than silently dropping it.
                asin = metadataMap.values().stream()
                        .map(BookMetadata::getAsin)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null);
            }
            metadata.setAsin(asin);
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setAsin(existingMetadata.getAsin());
        }
        if (enabledFields.isGoodreadsId()) {
            if (metadataMap.containsKey(GoodReads)) {
                metadata.setGoodreadsId(metadataMap.get(GoodReads).getGoodreadsId());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setGoodreadsId(existingMetadata.getGoodreadsId());
        }

        if (enabledFields.isHardcoverId()) {
            if (metadataMap.containsKey(Hardcover)) {
                metadata.setHardcoverId(metadataMap.get(Hardcover).getHardcoverId());
                metadata.setHardcoverBookId(metadataMap.get(Hardcover).getHardcoverBookId());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setHardcoverId(existingMetadata.getHardcoverId());
            metadata.setHardcoverBookId(existingMetadata.getHardcoverBookId());
        }

        if (enabledFields.isGoogleId()) {
            if (metadataMap.containsKey(Google)) {
                metadata.setGoogleId(metadataMap.get(Google).getGoogleId());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setGoogleId(existingMetadata.getGoogleId());
        }

        if (enabledFields.isComicvineId()) {
            if (metadataMap.containsKey(Comicvine)) {
                metadata.setComicvineId(metadataMap.get(Comicvine).getComicvineId());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setComicvineId(existingMetadata.getComicvineId());
        }

        if (metadataMap.containsKey(Comicvine) && metadataMap.get(Comicvine).getComicMetadata() != null) {
            metadata.setComicMetadata(metadataMap.get(Comicvine).getComicMetadata());
        }

        if (enabledFields.isLubimyczytacId()) {
            if (metadataMap.containsKey(Lubimyczytac)) {
                metadata.setLubimyczytacId(metadataMap.get(Lubimyczytac).getLubimyczytacId());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setLubimyczytacId(existingMetadata.getLubimyczytacId());
        }

        if (enabledFields.isLubimyczytacRating()) {
            if (metadataMap.containsKey(Lubimyczytac)) {
                metadata.setLubimyczytacRating(metadataMap.get(Lubimyczytac).getLubimyczytacRating());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setLubimyczytacRating(existingMetadata.getLubimyczytacRating());
        }

        if (enabledFields.isRanobedbId()) {
            if (metadataMap.containsKey(Ranobedb)) {
                metadata.setRanobedbId(metadataMap.get(Ranobedb).getRanobedbId());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setRanobedbId(existingMetadata.getRanobedbId());
        }

        if (enabledFields.isRanobedbRating()) {
            if (metadataMap.containsKey(Ranobedb)) {
                metadata.setRanobedbRating(metadataMap.get(Ranobedb).getRanobedbRating());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setRanobedbRating(existingMetadata.getRanobedbRating());
        }

        if (enabledFields.isMoods()) {
            if (metadataMap.containsKey(Hardcover)) {
                metadata.setMoods(metadataMap.get(Hardcover).getMoods());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setMoods(existingMetadata.getMoods());
        }

        if (enabledFields.isTags()) {
            if (metadataMap.containsKey(Hardcover)) {
                metadata.setTags(metadataMap.get(Hardcover).getTags());
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setTags(existingMetadata.getTags());
        }

        if (enabledFields.isCategories()) {
            if (refreshOptions.isMergeCategories()) {
                metadata.setCategories(getAllCategories(metadataMap, fieldOptions.getCategories(), BookMetadata::getCategories));
            } else {
                metadata.setCategories(resolveFieldAsSet(metadataMap, fieldOptions.getCategories(), BookMetadata::getCategories));
            }
        } else if (isReplaceAll && existingMetadata != null) {
            metadata.setCategories(existingMetadata.getCategories());
        }

        List<BookReview> allReviews = metadataMap.values().stream()
                .filter(Objects::nonNull)
                .flatMap(md -> Optional.ofNullable(md.getBookReviews()).stream().flatMap(Collection::stream))
                .collect(Collectors.toList());
        if (!allReviews.isEmpty()) {
            metadata.setBookReviews(allReviews);
        }

        return metadata;
    }

    /**
     * Page count is historically a single-provider field (the default priority is Amazon only),
     * so a book whose configured provider(s) didn't return a count ends up with none even when
     * another provider fetched in the same refresh did have it. Resolve by priority first, then
     * fall back to any other fetched provider that returned a usable value - mirroring the ASIN
     * fallback above. A non-positive count is treated as "missing".
     */
    private Integer resolvePageCount(Map<MetadataProvider, BookMetadata> metadataMap, MetadataRefreshOptions.FieldProvider fieldProvider) {
        Integer resolved = resolveFieldWithProviders(metadataMap, fieldProvider, BookMetadata::getPageCount, this::isUsablePageCount);
        if (isUsablePageCount(resolved)) {
            return resolved;
        }
        return Arrays.stream(MetadataProvider.values())
                .map(metadataMap::get)
                .filter(Objects::nonNull)
                .map(BookMetadata::getPageCount)
                .filter(this::isUsablePageCount)
                .findFirst()
                .orElse(null);
    }

    private boolean isUsablePageCount(Integer pageCount) {
        return pageCount != null && pageCount > 0;
    }

    protected <T > T resolveField(Map < MetadataProvider, BookMetadata > metadataMap, MetadataRefreshOptions.FieldProvider fieldProvider, Function < BookMetadata, T > extractor) {
        return resolveFieldWithProviders(metadataMap, fieldProvider, extractor, Objects::nonNull);
    }

    protected Integer resolveFieldAsInteger (Map < MetadataProvider, BookMetadata > metadataMap, MetadataRefreshOptions.FieldProvider fieldProvider, Function < BookMetadata, Integer > fieldValueExtractor){
        return resolveFieldWithProviders(metadataMap, fieldProvider, fieldValueExtractor, Objects::nonNull);
    }

    protected String resolveFieldAsString (Map < MetadataProvider, BookMetadata > metadataMap, MetadataRefreshOptions.FieldProvider fieldProvider, FieldValueExtractor fieldValueExtractor){
        return resolveFieldWithProviders(metadataMap, fieldProvider, fieldValueExtractor::extract, Objects::nonNull);
    }

    protected List<String> resolveFieldAsList (Map < MetadataProvider, BookMetadata > metadataMap, MetadataRefreshOptions.FieldProvider fieldProvider, FieldValueExtractorList fieldValueExtractor){
        Collection<String> result = resolveFieldWithProviders(metadataMap, fieldProvider, fieldValueExtractor::extract, (value) -> value != null && !value.isEmpty());
        if (result == null) return null;
        return result instanceof List<String> list ? list : new ArrayList<>(result);
    }

    protected Set<String> resolveFieldAsSet (Map < MetadataProvider, BookMetadata > metadataMap, MetadataRefreshOptions.FieldProvider fieldProvider, FieldValueExtractorList fieldValueExtractor){
        Collection<String> result = resolveFieldWithProviders(metadataMap, fieldProvider, fieldValueExtractor::extract, (value) -> value != null && !value.isEmpty());
        if (result == null) return null;
        return result instanceof Set<String> set ? set : new HashSet<>(result);
    }

    private <T > T resolveFieldWithProviders(Map < MetadataProvider, BookMetadata > metadataMap, MetadataRefreshOptions.FieldProvider fieldProvider, Function < BookMetadata, T > extractor, Predicate < T > isValidValue) {
        if (fieldProvider == null) {
            return null;
        }
        MetadataProvider[] providers = {
                fieldProvider.getP1(),
                fieldProvider.getP2(),
                fieldProvider.getP3(),
                fieldProvider.getP4()
        };
        for (MetadataProvider provider : providers) {
            if (provider != null && metadataMap.containsKey(provider)) {
                T value = extractor.apply(metadataMap.get(provider));
                if (isValidValue.test(value)) {
                    return value;
                }
            }
        }
        return null;
    }

    Set<String> getAllCategories (Map < MetadataProvider, BookMetadata > metadataMap, MetadataRefreshOptions.FieldProvider fieldProvider, FieldValueExtractorList fieldValueExtractor){
        Set<String> uniqueCategories = new HashSet<>();
        if (fieldProvider == null) {
            return uniqueCategories;
        }

        MetadataProvider[] providers = {
                fieldProvider.getP1(),
                fieldProvider.getP2(),
                fieldProvider.getP3(),
                fieldProvider.getP4()
        };

        for (MetadataProvider provider : providers) {
            if (provider != null && metadataMap.containsKey(provider)) {
                Collection<String> extracted = fieldValueExtractor.extract(metadataMap.get(provider));
                if (extracted != null) {
                    uniqueCategories.addAll(extracted);
                }
            }
        }

        return uniqueCategories;
    }

    protected Set<Long> getBookEntities (MetadataRefreshRequest request){
        MetadataRefreshRequest.RefreshType refreshType = request.getRefreshType();
        if (refreshType != MetadataRefreshRequest.RefreshType.LIBRARY && refreshType != MetadataRefreshRequest.RefreshType.BOOKS) {
            throw ApiError.INVALID_REFRESH_TYPE.createException();
        }
        return switch (refreshType) {
            case LIBRARY -> {
                LibraryEntity libraryEntity = libraryRepository.findById(request.getLibraryId()).orElseThrow(() -> ApiError.LIBRARY_NOT_FOUND.createException(request.getLibraryId()));
                yield bookRepository.findBookIdsByLibraryId(libraryEntity.getId());
            }
            case BOOKS -> request.getBookIds();
        };
    }

    private Set<Shelf> filterShelvesByUserId(Set<Shelf> shelves, Long userId) {
        if (shelves == null) return Collections.emptySet();
        return shelves.stream()
                .filter(shelf -> userId.equals(shelf.getUserId()))
                .collect(Collectors.toSet());
    }
}
