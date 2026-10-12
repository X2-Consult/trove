package org.booklore.service.file;

import org.booklore.config.AppProperties;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileCheckResult;
import org.booklore.model.enums.BookFileCheckType;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookFileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EbookAdCleanerServiceTest {

    private static final String AD = "<div style=\"float: none; margin: 10px 0px 10px 0px; text-align: center;\">"
            + "<p><a href=\"https://oceanofpdf.com\"><i>OceanofPDF.com</i></a></p></div>";

    @Mock
    BookFileRepository bookFileRepository;
    @Mock
    AppProperties appProperties;
    @Mock
    BookFileCheckService checks;

    @InjectMocks
    EbookAdCleanerService service;

    @TempDir
    Path dir;

    @Test
    void candidatesSkipFilesAlreadyCheckedAndUnchanged() {
        BookFileEntity unchanged = file(1L, 10L, "same");
        BookFileEntity changed = file(2L, 20L, "edited");
        BookFileEntity neverChecked = file(3L, 30L, "new");
        when(bookFileRepository.findAllWithBookAndLibraryPathByBookTypeIn(List.of(BookFileType.EPUB)))
                .thenReturn(List.of(unchanged, changed, neverChecked));
        when(checks.settled(BookFileCheckType.EBOOK_ADS))
                .thenReturn(new BookFileCheckService.Settled(Map.of(10L, "same", 20L, "before-edit")));

        EbookAdCleanerService.Candidates candidates = service.candidates(null);

        assertThat(candidates.files()).containsExactly(changed, neverChecked);
        assertThat(candidates.skipped()).isEqualTo(1);
    }

    @Test
    void namedBooksAreCheckedEvenIfAlreadyChecked() {
        BookFileEntity named = file(1L, 10L, "same");
        when(bookFileRepository.findAllWithBookAndLibraryPathByBookTypeIn(List.of(BookFileType.EPUB)))
                .thenReturn(List.of(named, file(2L, 20L, "other")));

        EbookAdCleanerService.Candidates candidates = service.candidates(List.of(1L));

        assertThat(candidates.files()).containsExactly(named);
        assertThat(candidates.skipped()).isZero();
        verify(checks, never()).settled(any());
    }

    @Test
    void scanRecordsWhetherTheFileHasAds() throws IOException {
        BookFileEntity clean = epubFile(1L, 10L, "clean.epub", false);
        BookFileEntity withAds = epubFile(2L, 20L, "ads.epub", true);

        service.scan(clean);
        service.scan(withAds);

        verify(checks).record(BookFileCheckType.EBOOK_ADS, 10L, "hash-10", BookFileCheckResult.OK);
        verify(checks).record(BookFileCheckType.EBOOK_ADS, 20L, "hash-20", BookFileCheckResult.NEEDS_FIXING);
    }

    @Test
    void cleanRecordsTheCleanedFileAgainstItsNewHash() throws IOException {
        when(appProperties.getPathConfig()).thenReturn(dir.resolve("config").toString());
        BookFileEntity withAds = epubFile(2L, 20L, "ads.epub", true);

        assertThat(service.clean(withAds)).isNotNull();

        String newHash = FileFingerprint.generateHash(withAds.getFullFilePath());
        assertThat(newHash).isNotEqualTo("hash-20");
        verify(bookFileRepository).updateCurrentHashAndSize(eq(20L), eq(newHash), anyLong());
        verify(checks).record(BookFileCheckType.EBOOK_ADS, 20L, newHash, BookFileCheckResult.FIXED);
    }

    @Test
    void cleanRecordsAFileWithNothingToRemoveAsOk() throws IOException {
        BookFileEntity clean = epubFile(1L, 10L, "clean.epub", false);

        assertThat(service.clean(clean)).isNull();

        verify(checks).record(BookFileCheckType.EBOOK_ADS, 10L, "hash-10", BookFileCheckResult.OK);
    }

    private BookFileEntity file(Long bookId, Long fileId, String hash) {
        BookEntity book = BookEntity.builder().id(bookId).deleted(false).build();
        return BookFileEntity.builder().id(fileId).book(book).bookType(BookFileType.EPUB).isBookFormat(true).currentHash(hash).build();
    }

    private BookFileEntity epubFile(Long bookId, Long fileId, String name, boolean withAds) throws IOException {
        Path path = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(path); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("mimetype"));
            zip.write("application/epub+zip".getBytes(StandardCharsets.US_ASCII));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("OEBPS/ch1.xhtml"));
            zip.write(("<html><body><p>The end of chapter one.</p>" + (withAds ? AD : "") + "</body></html>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        LibraryPathEntity libraryPath = LibraryPathEntity.builder().path(dir.toString()).build();
        BookEntity book = BookEntity.builder().id(bookId).deleted(false).libraryPath(libraryPath).build();
        return BookFileEntity.builder().id(fileId).book(book).bookType(BookFileType.EPUB).isBookFormat(true)
                .fileSubPath("").fileName(name).currentHash("hash-" + fileId).build();
    }
}
