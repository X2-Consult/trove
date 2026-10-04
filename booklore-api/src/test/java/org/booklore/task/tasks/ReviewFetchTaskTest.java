package org.booklore.task.tasks;

import org.booklore.mapper.BookMapper;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookReview;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.repository.BookMetadataRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.book.BookReviewService;
import org.booklore.service.metadata.BookReviewUpdateService;
import org.booklore.service.metadata.parser.MetadataProviderGuard;
import org.booklore.task.TaskCancellationManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReviewFetchTaskTest {

    @Mock BookMetadataRepository bookMetadataRepository;
    @Mock BookRepository bookRepository;
    @Mock BookMapper bookMapper;
    @Mock BookReviewService bookReviewService;
    @Mock BookReviewUpdateService bookReviewUpdateService;
    @Mock MetadataProviderGuard providerGuard;
    @Mock PlatformTransactionManager transactionManager;
    @Mock NotificationService notificationService;
    @Mock TaskCancellationManager cancellationManager;

    ReviewFetchTask task;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(bookReviewService.enabledReviewProviders()).thenReturn(List.of(MetadataProvider.Amazon));
        task = new ReviewFetchTask(bookMetadataRepository, bookRepository, bookMapper, bookReviewService,
                bookReviewUpdateService, providerGuard, transactionManager, notificationService, cancellationManager);
    }

    @Test
    void asksOnlyBooksNotCheckedForNinetyDays() {
        task.execute(new TaskCreateRequest());

        verify(bookMetadataRepository).findBookIdsMissingReviews(argThat(cutoff ->
                Math.abs(Duration.between(cutoff, Instant.now().minus(Duration.ofDays(90))).toSeconds()) < 60));
    }

    @Test
    void savesReviewsFoundAndStampsEveryBookAsked() {
        BookEntity withReviews = book(1L);
        BookEntity without = book(2L);
        BookReview review = BookReview.builder().metadataProvider(MetadataProvider.Amazon).build();
        when(bookMetadataRepository.findBookIdsMissingReviews(any())).thenReturn(List.of(1L, 2L));
        when(bookReviewService.fetchBookReviews(any(Book.class))).thenReturn(List.of(review)).thenReturn(List.of());

        task.execute(new TaskCreateRequest());

        verify(bookReviewUpdateService).addReviewsToBook(List.of(review), withReviews.getMetadata());
        verify(bookReviewUpdateService, never()).addReviewsToBook(any(), eq(without.getMetadata()));
        assertThat(withReviews.getMetadata().getReviewsFetchedAt()).isNotNull();
        assertThat(without.getMetadata().getReviewsFetchedAt()).isNotNull();
    }

    @Test
    void emptyResultWhileASourceIsBlockedIsNotRecordedAsChecked() {
        BookEntity book = book(1L);
        when(bookMetadataRepository.findBookIdsMissingReviews(any())).thenReturn(List.of(1L));
        when(bookReviewService.fetchBookReviews(any(Book.class))).thenAnswer(invocation -> {
            when(providerGuard.isBlocked(MetadataProvider.Amazon)).thenReturn(true);
            return List.of();
        });

        task.execute(new TaskCreateRequest());

        assertThat(book.getMetadata().getReviewsFetchedAt()).isNull();
        verify(bookRepository, never()).save(any());
    }

    @Test
    void stopsWhenEverySourceIsBlocked() {
        book(1L);
        when(bookMetadataRepository.findBookIdsMissingReviews(any())).thenReturn(List.of(1L));
        when(providerGuard.isBlocked(MetadataProvider.Amazon)).thenReturn(true);

        task.execute(new TaskCreateRequest());

        verify(bookReviewService, never()).fetchBookReviews(any(Book.class));
    }

    @Test
    void doesNothingWhenNoReviewSourceIsEnabled() {
        when(bookReviewService.enabledReviewProviders()).thenReturn(List.of());

        task.execute(new TaskCreateRequest());

        verify(bookMetadataRepository, never()).findBookIdsMissingReviews(any());
    }

    @Test
    void summaryExplainsTheRecheckAndAnEarlyStop() {
        assertThat(ReviewFetchTask.summary(500, 40, 25, 1, "every review source is blocking requests for now"))
                .isEqualTo("Checked 40 of 500 books due a review check: found reviews for 25, 15 had none and will be checked "
                        + "again in 90 days, 1 failed. Stopped early: every review source is blocking requests for now; the next run carries on.");
    }

    private BookEntity book(Long id) {
        BookEntity book = new BookEntity();
        book.setId(id);
        book.setMetadata(new BookMetadataEntity());
        when(bookRepository.findAllWithMetadataByIds(Set.of(id))).thenReturn(List.of(book));
        when(bookMapper.toBook(book)).thenReturn(Book.builder().id(id).build());
        return book;
    }
}
