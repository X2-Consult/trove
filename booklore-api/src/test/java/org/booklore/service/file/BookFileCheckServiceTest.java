package org.booklore.service.file;

import org.booklore.model.entity.BookFileCheckEntity;
import org.booklore.model.enums.BookFileCheckResult;
import org.booklore.model.enums.BookFileCheckType;
import org.booklore.repository.BookFileCheckRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookFileCheckServiceTest {

    @Mock
    BookFileCheckRepository repository;

    @InjectMocks
    BookFileCheckService service;

    @Test
    void settledCoversOnlyFilesWhoseHashHasNotChanged() {
        when(repository.findByTypeVersionAndResults(BookFileCheckType.EBOOK_ADS, BookFileCheckType.EBOOK_ADS.getVersion(),
                List.of(BookFileCheckResult.OK, BookFileCheckResult.FIXED)))
                .thenReturn(List.of(check(1L, "aaa"), check(2L, null)));

        BookFileCheckService.Settled settled = service.settled(BookFileCheckType.EBOOK_ADS);

        assertThat(settled.covers(1L, "aaa")).isTrue();
        assertThat(settled.covers(1L, "bbb")).as("file changed since").isFalse();
        assertThat(settled.covers(1L, null)).as("no hash to compare").isFalse();
        assertThat(settled.covers(2L, null)).as("checked without a hash").isFalse();
        assertThat(settled.covers(3L, "aaa")).as("never checked").isFalse();
    }

    @Test
    void recordUpdatesTheFilesExistingRow() {
        BookFileCheckEntity existing = check(1L, "old");
        existing.setResult(BookFileCheckResult.NEEDS_FIXING);
        when(repository.findByBookFileIdAndCheckType(1L, BookFileCheckType.EPUB_COVER)).thenReturn(Optional.of(existing));

        service.record(BookFileCheckType.EPUB_COVER, 1L, "new", BookFileCheckResult.FIXED);

        verify(repository).save(existing);
        assertThat(existing.getResult()).isEqualTo(BookFileCheckResult.FIXED);
        assertThat(existing.getFileHash()).isEqualTo("new");
        assertThat(existing.getCheckVersion()).isEqualTo(BookFileCheckType.EPUB_COVER.getVersion());
        assertThat(existing.getCheckedAt()).isNotNull();
    }

    @Test
    void recordCreatesARowForAFileNotCheckedBefore() {
        when(repository.findByBookFileIdAndCheckType(5L, BookFileCheckType.EBOOK_ADS)).thenReturn(Optional.empty());

        service.record(BookFileCheckType.EBOOK_ADS, 5L, "hash", BookFileCheckResult.OK);

        ArgumentCaptor<BookFileCheckEntity> saved = ArgumentCaptor.forClass(BookFileCheckEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getBookFileId()).isEqualTo(5L);
        assertThat(saved.getValue().getCheckType()).isEqualTo(BookFileCheckType.EBOOK_ADS);
        assertThat(saved.getValue().getResult()).isEqualTo(BookFileCheckResult.OK);
        assertThat(saved.getValue().getFileHash()).isEqualTo("hash");
    }

    @Test
    void aFailedRecordIsOnlyLogged() {
        when(repository.findByBookFileIdAndCheckType(any(), any())).thenThrow(new IllegalStateException("database down"));

        assertThatCode(() -> service.record(BookFileCheckType.EBOOK_ADS, 1L, "hash", BookFileCheckResult.OK))
                .doesNotThrowAnyException();
    }

    private static BookFileCheckEntity check(Long bookFileId, String hash) {
        return BookFileCheckEntity.builder().bookFileId(bookFileId).fileHash(hash).result(BookFileCheckResult.OK).build();
    }
}
