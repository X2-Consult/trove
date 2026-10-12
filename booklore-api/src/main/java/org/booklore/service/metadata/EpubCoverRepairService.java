package org.booklore.service.metadata;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileCheckResult;
import org.booklore.model.enums.BookFileCheckType;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.file.BookFileCheckService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds EPUBs whose own cover readers can't show ({@link EpubCover#check}) and writes Trove's copy
 * of the cover into them, through the EPUB writer so each repair is confirmed by reading it back.
 * Only a book's primary file is looked at, as that's the file cover changes are written to. Each
 * result is recorded ({@link BookFileCheckService}), so books found fine or fixed are left out of
 * later runs until their file changes.
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
    private final BookFileCheckService checks;

    /**
     * What a book's EPUB looked like, and what a repair did if one was tried. {@code bookFileId} and
     * {@code fileHash} are the primary file's, after any repair.
     */
    public record Outcome(Long bookId, String path, EpubCover.Check check, CoverWriteResult repair, Long bookFileId, String fileHash) {
    }

    /** The books to check, and how many were left out as already fine or fixed. */
    public record Candidates(List<Long> bookIds, int skipped) {
    }

    /**
     * Books with an EPUB, minus those whose EPUB files were all found fine or fixed and haven't changed
     * since. (Only the primary file gets a result, so a book with two EPUBs is always checked.)
     */
    public Candidates candidates() {
        Map<Long, Boolean> settledByBook = new LinkedHashMap<>();
        BookFileCheckService.Settled settled = checks.settled(BookFileCheckType.EPUB_COVER);
        for (BookFileRepository.EpubFileRef file : bookFileRepository.findEpubFileRefs()) {
            settledByBook.merge(file.getBookId(), settled.covers(file.getBookFileId(), file.getCurrentHash()), Boolean::logicalAnd);
        }
        List<Long> toCheck = settledByBook.entrySet().stream().filter(e -> !e.getValue()).map(Map.Entry::getKey).toList();
        return new Candidates(toCheck, settledByBook.size() - toCheck.size());
    }

    /** @return null if the book's primary file isn't an EPUB */
    public Outcome check(Long bookId) {
        Outcome outcome = new TransactionTemplate(transactionManager).execute(status -> {
            BookEntity book = bookRepository.findById(bookId).orElse(null);
            Path path = epubPath(book);
            if (path == null) {
                return null;
            }
            BookFileEntity primary = book.getPrimaryBookFile();
            return new Outcome(bookId, path.toString(), checkFile(path), null, primary.getId(), primary.getCurrentHash());
        });
        record(outcome);
        return outcome;
    }

    /** Checks the book and, if its cover is broken and Trove has one, writes Trove's cover in. */
    public Outcome repair(Long bookId) {
        Outcome outcome = new TransactionTemplate(transactionManager).execute(status -> {
            BookEntity book = bookRepository.findById(bookId).orElse(null);
            Path path = epubPath(book);
            if (path == null) {
                return null;
            }
            BookFileEntity primary = book.getPrimaryBookFile();
            EpubCover.Check check = checkFile(path);
            if (check.ok()) {
                return new Outcome(bookId, path.toString(), check, null, primary.getId(), primary.getCurrentHash());
            }
            Path troveCover = Path.of(fileService.getCoverFile(bookId));
            if (!Files.isRegularFile(troveCover)) {
                return new Outcome(bookId, path.toString(), check, CoverWriteResult.skipped("Trove has no cover for this book either"),
                        primary.getId(), primary.getCurrentHash());
            }
            CoverWriteResult result;
            try {
                result = epubMetadataWriter.replaceCoverImageFromBytes(path.toFile(), Files.readAllBytes(troveCover));
            } catch (Exception e) {
                result = CoverWriteResult.failed(e.getMessage());
            }
            if (result.status() == CoverWriteResult.Status.WRITTEN) {
                primary.setCurrentHash(FileFingerprint.generateHash(path));
                bookRepository.save(book);
            }
            return new Outcome(bookId, path.toString(), check, result, primary.getId(), primary.getCurrentHash());
        });
        record(outcome);
        return outcome;
    }

    /** Recorded once the book's transaction has committed, so the repair's new hash is what's stored. */
    private void record(Outcome outcome) {
        if (outcome == null) {
            return;
        }
        BookFileCheckResult result;
        if (outcome.check().ok()) {
            result = BookFileCheckResult.OK;
        } else if (outcome.repair() != null && outcome.repair().status() == CoverWriteResult.Status.WRITTEN) {
            result = BookFileCheckResult.FIXED;
        } else {
            result = BookFileCheckResult.NEEDS_FIXING;
        }
        checks.record(BookFileCheckType.EPUB_COVER, outcome.bookFileId(), outcome.fileHash(), result);
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
