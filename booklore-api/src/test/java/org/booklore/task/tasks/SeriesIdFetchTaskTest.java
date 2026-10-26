package org.booklore.task.tasks;

import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.model.enums.MetadataProvider;
import org.booklore.repository.BookMetadataRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.metadata.parser.AmazonBookParser;
import org.booklore.service.metadata.parser.GoodReadsParser;
import org.booklore.service.metadata.parser.MetadataProviderGuard;
import org.booklore.task.TaskCancellationManager;
import org.booklore.task.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeriesIdFetchTaskTest {

    @Mock BookMetadataRepository bookMetadataRepository;
    @Mock GoodReadsParser goodReadsParser;
    @Mock AmazonBookParser amazonBookParser;
    @Mock MetadataProviderGuard providerGuard;
    @Mock PlatformTransactionManager transactionManager;
    @Mock NotificationService notificationService;
    @Mock TaskCancellationManager cancellationManager;

    @InjectMocks SeriesIdFetchTask task;

    private BookMetadataEntity book(long id, String goodreadsId, String asin) {
        BookMetadataEntity m = BookMetadataEntity.builder().bookId(id).seriesName("Harem University")
                .goodreadsId(goodreadsId).asin(asin).build();
        when(bookMetadataRepository.findById(id)).thenReturn(Optional.of(m));
        return m;
    }

    @Test
    void fillsIdsWhenTheSourceNamesTheSameSeries_andStampsTheBook() {
        when(bookMetadataRepository.findBookIdsMissingSeriesIds(any())).thenReturn(List.of(1L));
        BookMetadataEntity m = book(1, "216451654", "B0D9DRRNXZ");
        when(goodReadsParser.fetchDetailedMetadata("216451654")).thenReturn(BookMetadata.builder().title("Tempting Tutor").build());
        when(amazonBookParser.fetchDetailedMetadata("B0D9DRRNXZ"))
                .thenReturn(BookMetadata.builder().seriesName("Harem University").seriesAsin("B0D9DNS7D3").build());

        var response = task.execute(TaskCreateRequest.builder().taskId("t").build());

        assertThat(response.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(m.getSeriesAsin()).isEqualTo("B0D9DNS7D3");
        assertThat(m.getSeriesGoodreadsId()).isNull();   // Goodreads has no series record for it
        assertThat(m.getSeriesIdsCheckedAt()).isNotNull();
        assertThat(task.getMetadata()).contains("found series IDs for 1");
    }

    @Test
    void ignoresAnIdForADifferentlyNamedSeries() {
        when(bookMetadataRepository.findBookIdsMissingSeriesIds(any())).thenReturn(List.of(1L));
        BookMetadataEntity m = book(1, null, "B0D9DRRNXZ");
        when(amazonBookParser.fetchDetailedMetadata("B0D9DRRNXZ"))
                .thenReturn(BookMetadata.builder().seriesName("Harem University Omnibus").seriesAsin("B0OTHER000").build());

        task.execute(TaskCreateRequest.builder().taskId("t").build());

        assertThat(m.getSeriesAsin()).isNull();
        assertThat(m.getSeriesIdsCheckedAt()).isNotNull();
    }

    @Test
    void aBlockedSourceLeavesTheBookToBeAskedAgain() {
        when(bookMetadataRepository.findBookIdsMissingSeriesIds(any())).thenReturn(List.of(1L));
        BookMetadataEntity m = book(1, "216451654", "B0D9DRRNXZ");
        when(providerGuard.isBlocked(MetadataProvider.Amazon)).thenReturn(true);
        when(goodReadsParser.fetchDetailedMetadata("216451654"))
                .thenReturn(BookMetadata.builder().seriesName("Harem University").seriesGoodreadsId("123").build());

        task.execute(TaskCreateRequest.builder().taskId("t").build());

        assertThat(m.getSeriesGoodreadsId()).isEqualTo("123");
        assertThat(m.getSeriesIdsCheckedAt()).isNull();
        verify(amazonBookParser, never()).fetchDetailedMetadata(any());
    }
}
