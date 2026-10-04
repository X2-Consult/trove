package org.booklore.service.book;

import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.ApiError;
import org.booklore.mapper.BookReviewMapper;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.BookReview;
import org.booklore.model.dto.settings.MetadataPublicReviewsSettings;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.repository.BookRepository;
import org.booklore.repository.BookReviewRepository;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.metadata.BookReviewUpdateService;
import org.booklore.service.metadata.MetadataRefreshService;
import jakarta.persistence.EntityNotFoundException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@AllArgsConstructor
public class BookReviewService {

    private final BookReviewRepository bookReviewRepository;
    private final BookReviewMapper mapper;
    private final BookReviewUpdateService bookReviewUpdateService;
    private final BookRepository bookRepository;
    private final AppSettingService appSettingService;
    private final MetadataRefreshService metadataRefreshService;
    private final AuthenticationService authenticationService;

    /** How long a book whose review sources had nothing for it is left before they're asked again. */
    public static final Duration RECHECK_AFTER = Duration.ofDays(90);

    /** True if the review sources were asked about this book within {@link #RECHECK_AFTER}. */
    public static boolean checkedRecently(BookEntity book) {
        Instant fetchedAt = book.getMetadata() != null ? book.getMetadata().getReviewsFetchedAt() : null;
        return fetchedAt != null && fetchedAt.isAfter(Instant.now().minus(RECHECK_AFTER));
    }

    public List<BookReview> getByBookId(Long bookId) {
        BookEntity bookEntity = bookRepository.findById(bookId).orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));

        List<BookReview> existingReviews = bookReviewRepository.findByBookMetadataBookId(bookId).stream()
                .map(mapper::toDto)
                .collect(Collectors.toList());

        MetadataPublicReviewsSettings reviewSettings = appSettingService.getAppSettings().getMetadataPublicReviewsSettings();

        // Return existing reviews if download is disabled or reviews already exist
        if (!reviewSettings.isDownloadEnabled() || !existingReviews.isEmpty()) {
            return existingReviews;
        }

        // Check user permissions for auto-download
        BookLoreUser currentUser = authenticationService.getAuthenticatedUser();
        boolean hasPermission = currentUser.getPermissions().isAdmin() || currentUser.getPermissions().isCanManageLibrary();

        if (!hasPermission || !reviewSettings.isAutoDownloadEnabled() || checkedRecently(bookEntity)) {
            return existingReviews;
        }

        try {
            List<BookReview> fetchedReviews = fetchBookReviews(bookEntity);
            bookReviewUpdateService.addReviewsToBook(fetchedReviews, bookEntity.getMetadata());
            bookEntity.getMetadata().setReviewsFetchedAt(Instant.now());
            bookRepository.save(bookEntity);
            if (!fetchedReviews.isEmpty()) {
                return bookReviewRepository.findByBookMetadataBookId(bookId).stream()
                        .map(mapper::toDto)
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.warn("Failed to auto-fetch reviews for book {}: {}", bookId, e.getMessage());
        }

        return existingReviews;
    }

    public List<BookReview> fetchBookReviews(BookEntity bookEntity) {
        List<MetadataProvider> providers = enabledReviewProviders();
        if (providers.isEmpty()) {
            return Collections.emptyList();
        }
        return reviewsOf(metadataRefreshService.fetchMetadataForBook(providers, bookEntity));
    }

    /** {@link #fetchBookReviews(BookEntity)} for a book read in an earlier transaction. */
    public List<BookReview> fetchBookReviews(Book book) {
        List<MetadataProvider> providers = enabledReviewProviders();
        if (providers.isEmpty()) {
            return Collections.emptyList();
        }
        return reviewsOf(metadataRefreshService.fetchMetadataForBook(providers, book));
    }

    /** The review sources turned on in settings, or none if review downloads are off. */
    public List<MetadataProvider> enabledReviewProviders() {
        MetadataPublicReviewsSettings settings = appSettingService.getAppSettings().getMetadataPublicReviewsSettings();
        if (settings == null || !settings.isDownloadEnabled() || settings.getProviders() == null) {
            return Collections.emptyList();
        }
        return settings.getProviders().stream()
                .filter(MetadataPublicReviewsSettings.ReviewProviderConfig::isEnabled)
                .map(MetadataPublicReviewsSettings.ReviewProviderConfig::getProvider)
                .collect(Collectors.toList());
    }

    private static List<BookReview> reviewsOf(Map<MetadataProvider, BookMetadata> metadataMap) {
        return metadataMap.values().stream()
                .filter(meta -> meta.getBookReviews() != null)
                .flatMap(meta -> meta.getBookReviews().stream())
                .collect(Collectors.toList());
    }

    @Transactional
    public void delete(Long id) {
        if (!bookReviewRepository.existsById(id)) {
            throw new EntityNotFoundException("Review not found: " + id);
        }
        bookReviewRepository.deleteById(id);
    }

    @Transactional
    public List<BookReview> refreshReviews(Long bookId) {
        BookEntity bookEntity = bookRepository.findById(bookId)
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));

        bookEntity.getMetadata().getReviews().clear();
        bookRepository.save(bookEntity);

        bookReviewRepository.deleteByBookMetadataBookId(bookId);

        List<BookReview> freshReviews = fetchBookReviews(bookEntity);
        bookReviewUpdateService.addReviewsToBook(freshReviews, bookEntity.getMetadata());
        bookEntity.getMetadata().setReviewsFetchedAt(Instant.now());
        bookRepository.save(bookEntity);

        return bookReviewRepository.findByBookMetadataBookId(bookId).stream()
                .map(mapper::toDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteAllByBookId(Long bookId) {
        if (!bookRepository.existsById(bookId)) {
            throw ApiError.BOOK_NOT_FOUND.createException(bookId);
        }
        bookReviewRepository.deleteByBookMetadataBookId(bookId);
    }
}