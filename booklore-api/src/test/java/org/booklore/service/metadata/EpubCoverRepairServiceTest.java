package org.booklore.service.metadata;

import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileCheckResult;
import org.booklore.model.enums.BookFileCheckType;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.file.BookFileCheckService;
import org.booklore.service.metadata.writer.EpubMetadataWriter;
import org.booklore.util.FileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EpubCoverRepairServiceTest {

    @Mock
    BookFileRepository bookFileRepository;
    @Mock
    BookRepository bookRepository;
    @Mock
    EpubMetadataWriter epubMetadataWriter;
    @Mock
    FileService fileService;
    @Mock
    PlatformTransactionManager transactionManager;
    @Mock
    BookFileCheckService checks;

    @InjectMocks
    EpubCoverRepairService service;

    @TempDir
    Path dir;

    @Test
    void candidatesSkipBooksWhoseEpubsAreAllCheckedAndUnchanged() {
        when(bookFileRepository.findEpubFileRefs()).thenReturn(List.of(
                ref(1L, 10L, "same"),
                ref(2L, 20L, "edited"),
                ref(3L, 30L, "same"), ref(3L, 31L, "never-checked"),
                ref(4L, 40L, "new")));
        when(checks.settled(BookFileCheckType.EPUB_COVER))
                .thenReturn(new BookFileCheckService.Settled(Map.of(10L, "same", 20L, "before-edit", 30L, "same")));

        EpubCoverRepairService.Candidates candidates = service.candidates();

        assertThat(candidates.bookIds()).containsExactly(2L, 3L, 4L);
        assertThat(candidates.skipped()).isEqualTo(1);
    }

    @Test
    void checkRecordsABrokenCoverAgainstThePrimaryFile() throws IOException {
        Files.writeString(dir.resolve("book.epub"), "not a zip");
        LibraryPathEntity libraryPath = LibraryPathEntity.builder().path(dir.toString()).build();
        BookFileEntity primary = BookFileEntity.builder().id(10L).bookType(BookFileType.EPUB).isBookFormat(true)
                .fileSubPath("").fileName("book.epub").currentHash("hash-10").build();
        BookEntity book = BookEntity.builder().id(1L).deleted(false).libraryPath(libraryPath).bookFiles(new ArrayList<>(List.of(primary))).build();
        primary.setBook(book);
        when(bookRepository.findById(1L)).thenReturn(Optional.of(book));

        EpubCoverRepairService.Outcome outcome = service.check(1L);

        assertThat(outcome.check().ok()).isFalse();
        verify(checks).record(BookFileCheckType.EPUB_COVER, 10L, "hash-10", BookFileCheckResult.NEEDS_FIXING);
    }

    private static BookFileRepository.EpubFileRef ref(Long bookId, Long bookFileId, String hash) {
        return new BookFileRepository.EpubFileRef() {
            @Override
            public Long getBookId() {
                return bookId;
            }

            @Override
            public Long getBookFileId() {
                return bookFileId;
            }

            @Override
            public String getCurrentHash() {
                return hash;
            }
        };
    }
}
