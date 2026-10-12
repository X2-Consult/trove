package org.booklore.service.file;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.AppProperties;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileCheckResult;
import org.booklore.model.enums.BookFileCheckType;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookFileRepository;
import org.booklore.util.EpubAdCleaner;
import org.booklore.util.SafeFiles;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;

/**
 * Finds and strips the OceanofPDF advert from the library's EPUBs ({@link EpubAdCleaner}). A cleaned
 * book replaces the original in place through {@link SafeFiles#replace}, so the library doesn't gain a
 * second copy, and the original is kept under {@code <config>/ad-cleaner-backups/<book id>/} first.
 * Each result is recorded ({@link BookFileCheckService}), so files found clean or cleaned are left
 * out of later runs until they change.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EbookAdCleanerService {

    static final String BACKUP_DIR = "ad-cleaner-backups";
    private static final DateTimeFormatter BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final BookFileRepository bookFileRepository;
    private final AppProperties appProperties;
    private final BookFileCheckService checks;

    /**
     * EPUB files of books that aren't deleted, all of them or only those of {@code bookIds}. Unless
     * books were named, files already found clean or cleaned, and unchanged since, are skipped.
     */
    public Candidates candidates(Collection<Long> bookIds) {
        boolean named = bookIds != null && !bookIds.isEmpty();
        List<BookFileEntity> files = bookFileRepository.findAllWithBookAndLibraryPathByBookTypeIn(List.of(BookFileType.EPUB)).stream()
                .filter(BookFileEntity::isBookFormat)
                .filter(file -> !Boolean.TRUE.equals(file.getBook().getDeleted()))
                .filter(file -> !named || bookIds.contains(file.getBook().getId()))
                .toList();
        if (named) {
            return new Candidates(files, 0);
        }
        BookFileCheckService.Settled settled = checks.settled(BookFileCheckType.EBOOK_ADS);
        List<BookFileEntity> toCheck = files.stream()
                .filter(file -> !settled.covers(file.getId(), file.getCurrentHash()))
                .toList();
        return new Candidates(toCheck, files.size() - toCheck.size());
    }

    public EpubAdCleaner.Findings scan(BookFileEntity bookFile) throws IOException {
        EpubAdCleaner.Findings findings = EpubAdCleaner.scan(bookFile.getFullFilePath());
        record(bookFile, findings.hasAds() ? BookFileCheckResult.NEEDS_FIXING : BookFileCheckResult.OK);
        return findings;
    }

    /**
     * Backs up and cleans one book file, then records its new hash and size.
     *
     * @return what was removed, or null if there was nothing to remove
     */
    public Cleaned clean(BookFileEntity bookFile) throws IOException {
        Path path = bookFile.getFullFilePath();
        EpubAdCleaner.Findings findings = EpubAdCleaner.scan(path);
        if (!findings.hasAds()) {
            record(bookFile, BookFileCheckResult.OK);
            return null;
        }
        Path backup = backupPath(bookFile);
        SafeFiles.copy(path, backup);
        try {
            SafeFiles.replace(path, local -> {
                EpubAdCleaner.rewrite(path, local);
                return true;
            });
        } catch (IOException e) {
            Files.deleteIfExists(backup);
            throw e;
        }
        String hash = FileFingerprint.generateHash(path);
        bookFileRepository.updateCurrentHashAndSize(bookFile.getId(), hash, Files.size(path) / 1024);
        bookFile.setCurrentHash(hash);
        record(bookFile, BookFileCheckResult.FIXED);
        return new Cleaned(findings, backup);
    }

    private void record(BookFileEntity bookFile, BookFileCheckResult result) {
        checks.record(BookFileCheckType.EBOOK_ADS, bookFile.getId(), bookFile.getCurrentHash(), result);
    }

    private Path backupPath(BookFileEntity bookFile) {
        Path dir = Paths.get(appProperties.getPathConfig(), BACKUP_DIR, String.valueOf(bookFile.getBook().getId()));
        Path backup = dir.resolve(bookFile.getFileName());
        if (Files.exists(backup)) {
            backup = dir.resolve(LocalDateTime.now().format(BACKUP_STAMP) + "-" + bookFile.getFileName());
        }
        return backup;
    }

    public record Cleaned(EpubAdCleaner.Findings findings, Path backup) {
    }

    /** The files to check, and how many were left out as already clean or cleaned. */
    public record Candidates(List<BookFileEntity> files, int skipped) {
    }
}
