package org.booklore.service.metadata;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.file.FileFingerprint;
import org.booklore.service.metadata.writer.CoverWriteResult;
import org.booklore.service.metadata.writer.EpubMetadataWriter;
import org.booklore.util.EpubCover;
import org.booklore.util.FileService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Finds EPUBs whose own cover readers can't show ({@link EpubCover#check}) and writes Trove's copy
 * of the cover into them, through the EPUB writer so each repair is confirmed by reading it back.
 * Only a book's primary file is looked at, as that's the file cover changes are written to.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EpubCoverRepairService {

    private final BookFileRepository bookFileRepository;
    private final BookRepository bookRepository;
    private final EpubMetadataWriter epubMetadataWriter;
    private final FileService fileService;
    private final PlatformTransactionManager transactionManager;

    /** What a book's EPUB looked like, and what a repair did if one was tried. */
    public record Outcome(Long bookId, String path, EpubCover.Check check, CoverWriteResult repair) {
    }

    public List<Long> candidates() {
        return bookFileRepository.findBookIdsWithEpub();
    }

    /** @return null if the book's primary file isn't an EPUB */
    public Outcome check(Long bookId) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            BookEntity book = bookRepository.findById(bookId).orElse(null);
            Path path = epubPath(book);
            return path == null ? null : new Outcome(bookId, path.toString(), checkFile(path), null);
        });
    }

    /** Checks the book and, if its cover is broken and Trove has one, writes Trove's cover in. */
    public Outcome repair(Long bookId) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            BookEntity book = bookRepository.findById(bookId).orElse(null);
            Path path = epubPath(book);
            if (path == null) {
                return null;
            }
            EpubCover.Check check = checkFile(path);
            if (check.ok()) {
                return new Outcome(bookId, path.toString(), check, null);
            }
            Path troveCover = Path.of(fileService.getCoverFile(bookId));
            if (!Files.isRegularFile(troveCover)) {
                return new Outcome(bookId, path.toString(), check, CoverWriteResult.skipped("Trove has no cover for this book either"));
            }
            CoverWriteResult result;
            try {
                result = epubMetadataWriter.replaceCoverImageFromBytes(book, Files.readAllBytes(troveCover));
            } catch (Exception e) {
                result = CoverWriteResult.failed(e.getMessage());
            }
            if (result.status() == CoverWriteResult.Status.WRITTEN) {
                BookFileEntity primary = book.getPrimaryBookFile();
                primary.setCurrentHash(FileFingerprint.generateHash(path));
                bookRepository.save(book);
            }
            return new Outcome(bookId, path.toString(), check, result);
        });
    }

    private static Path epubPath(BookEntity book) {
        if (book == null || Boolean.TRUE.equals(book.getDeleted())) {
            return null;
        }
        BookFileEntity primary = book.getPrimaryBookFile();
        if (primary == null || primary.getBookType() != BookFileType.EPUB) {
            return null;
        }
        return book.getFullFilePath();
    }

    private static EpubCover.Check checkFile(Path path) {
        try {
            return EpubCover.check(path);
        } catch (Exception e) {
            return new EpubCover.Check(EpubCover.Problem.UNREADABLE, "couldn't open the EPUB: " + e.getMessage(), null, 0, 0);
        }
    }
}
