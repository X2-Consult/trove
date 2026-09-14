package org.booklore.service.metadata;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.service.reader.CbxReaderService;
import org.booklore.util.PageCounter;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Works out a book's page count from its files, for books no metadata source gave one. Exact
 * sources come first: a PDF's pages, a comic's page images, then an EPUB's print page numbers;
 * only then an estimate from the word count (see {@link PageCounter}). MOBI, AZW3 and audiobooks
 * give nothing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PageCountService {

    private static final List<BookFileType> PREFERENCE = List.of(BookFileType.PDF, BookFileType.CBX, BookFileType.EPUB, BookFileType.FB2);

    /** Fewer measurable books than this and the measured figure isn't trusted. */
    public static final int MIN_CALIBRATION_BOOKS = 20;
    /** Ratios outside this range are a different edition, a picture book or a bad count, not a page of text. */
    private static final double MIN_PLAUSIBLE_WORDS_PER_PAGE = 100;
    private static final double MAX_PLAUSIBLE_WORDS_PER_PAGE = 800;

    private final CbxReaderService cbxReaderService;

    /**
     * Words per page across books whose page count is known: the median of their words divided by
     * their pages, so an odd edition or a wrong count doesn't pull it about. Empty when fewer than
     * {@value #MIN_CALIBRATION_BOOKS} books give a plausible ratio.
     */
    public static OptionalInt medianWordsPerPage(List<Double> ratios) {
        double[] plausible = ratios.stream()
                .mapToDouble(Double::doubleValue)
                .filter(r -> r >= MIN_PLAUSIBLE_WORDS_PER_PAGE && r <= MAX_PLAUSIBLE_WORDS_PER_PAGE)
                .sorted()
                .toArray();
        if (plausible.length < MIN_CALIBRATION_BOOKS) {
            return OptionalInt.empty();
        }
        int mid = plausible.length / 2;
        double median = plausible.length % 2 == 1 ? plausible[mid] : (plausible[mid - 1] + plausible[mid]) / 2;
        return OptionalInt.of((int) Math.round(median));
    }

    /** The words in the book's EPUB or FB2 text, 0 when it has neither or they can't be read. */
    public long countWords(List<BookFileEntity> files) {
        for (BookFileEntity file : files) {
            if ((file.getBookType() != BookFileType.EPUB && file.getBookType() != BookFileType.FB2) || file.isFolderBased()) {
                continue;
            }
            try {
                Path path = file.getFullFilePath();
                if (path == null || !Files.isRegularFile(path)) {
                    continue;
                }
                long words = file.getBookType() == BookFileType.EPUB ? PageCounter.epubWords(path) : PageCounter.fb2Words(path);
                if (words > 0) {
                    return words;
                }
            } catch (Exception | StackOverflowError e) {
                log.debug("Could not count words in book file {}: {}", file.getId(), e.getMessage());
            }
        }
        return 0;
    }

    public Optional<PageCounter.Result> count(List<BookFileEntity> files, int wordsPerPage) {
        List<BookFileEntity> candidates = files.stream()
                .filter(file -> PREFERENCE.contains(file.getBookType()) && !file.isFolderBased())
                .sorted(Comparator.comparingInt(file -> PREFERENCE.indexOf(file.getBookType())))
                .toList();
        Optional<PageCounter.Result> best = Optional.empty();
        for (BookFileEntity file : candidates) {
            Optional<PageCounter.Result> result = countFile(file, wordsPerPage);
            if (result.isPresent() && (best.isEmpty() || isMoreReliable(result.get(), best.get()))) {
                best = result;
            }
            if (best.isPresent() && best.get().source() != PageCounter.Source.WORD_COUNT) {
                break;
            }
        }
        return best;
    }

    private static boolean isMoreReliable(PageCounter.Result candidate, PageCounter.Result current) {
        return current.source() == PageCounter.Source.WORD_COUNT && candidate.source() != PageCounter.Source.WORD_COUNT;
    }

    Optional<PageCounter.Result> countFile(BookFileEntity file, int wordsPerPage) {
        Path path;
        try {
            path = file.getFullFilePath();
        } catch (Exception e) {
            return Optional.empty();
        }
        if (path == null || !Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return switch (file.getBookType()) {
                case PDF -> positive(pdfPages(path), PageCounter.Source.PDF_PAGES);
                case CBX -> positive(cbxReaderService.countPages(path), PageCounter.Source.COMIC_PAGES);
                case EPUB -> PageCounter.countEpub(path, wordsPerPage);
                case FB2 -> PageCounter.countFb2(path, wordsPerPage);
                default -> Optional.empty();
            };
        } catch (Exception | StackOverflowError e) {
            log.debug("Could not count pages in {}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    private static int pdfPages(Path path) throws IOException {
        try (RandomAccessReadBufferedFile file = new RandomAccessReadBufferedFile(path.toFile());
             PDDocument pdf = Loader.loadPDF(file)) {
            return pdf.getNumberOfPages();
        }
    }

    private static Optional<PageCounter.Result> positive(int pages, PageCounter.Source source) {
        return pages > 0 ? Optional.of(new PageCounter.Result(pages, source)) : Optional.empty();
    }
}
