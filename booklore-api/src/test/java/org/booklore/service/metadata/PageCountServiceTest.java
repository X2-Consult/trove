package org.booklore.service.metadata;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.service.reader.CbxReaderService;
import org.booklore.util.PageCounter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PageCountServiceTest {

    @TempDir
    Path dir;

    private final CbxReaderService cbxReaderService = mock(CbxReaderService.class);
    private final PageCountService service = new PageCountService(cbxReaderService);

    private BookFileEntity file(String name, BookFileType type) {
        BookEntity book = BookEntity.builder().id(1L).libraryPath(LibraryPathEntity.builder().id(1L).path(dir.toString()).build()).build();
        return BookFileEntity.builder().book(book).fileSubPath("").fileName(name).bookType(type).isBookFormat(true).build();
    }

    private void pdf(String name, int pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage());
            }
            doc.save(dir.resolve(name).toFile());
        }
    }

    private void fb2(String name, int words) throws IOException {
        String text = IntStream.range(0, words).mapToObj(i -> "word").collect(Collectors.joining(" "));
        Files.writeString(dir.resolve(name), "<FictionBook><body><p>" + text + "</p></body></FictionBook>");
    }

    @Test
    void anExactCountBeatsAnEstimateFromAnotherFormat() throws IOException {
        pdf("book.pdf", 3);
        fb2("book.fb2", 5000);

        assertThat(service.count(List.of(file("book.fb2", BookFileType.FB2), file("book.pdf", BookFileType.PDF)), 250))
                .contains(new PageCounter.Result(3, PageCounter.Source.PDF_PAGES));
    }

    @Test
    void estimatesWhenThereIsNothingExact() throws IOException {
        fb2("book.fb2", 5000);

        assertThat(service.count(List.of(file("book.fb2", BookFileType.FB2)), 200))
                .contains(new PageCounter.Result(25, PageCounter.Source.WORD_COUNT));
    }

    @Test
    void missingFilesAndUncountableFormatsGiveNothing() {
        assertThat(service.count(List.of(file("gone.pdf", BookFileType.PDF), file("book.mobi", BookFileType.MOBI)), 250)).isEmpty();
        verifyNoInteractions(cbxReaderService);
    }

    @Test
    void wordsPerPageIsTheMedianOfPlausibleRatios() {
        List<Double> ratios = new ArrayList<>();
        IntStream.range(0, 21).forEach(i -> ratios.add(240.0 + i));   // 240..260, median 250
        ratios.add(5.0);        // a picture book
        ratios.add(3000.0);     // a wrong count

        assertThat(PageCountService.medianWordsPerPage(ratios)).hasValue(250);
    }

    @Test
    void tooFewBooksToMeasureGivesNothing() {
        assertThat(PageCountService.medianWordsPerPage(List.of(250.0, 260.0, 270.0))).isEmpty();
    }
}
