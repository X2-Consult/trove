package org.booklore.service.file;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.AppProperties;
import org.booklore.model.entity.BookFileEntity;
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
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EbookAdCleanerService {

    static final String BACKUP_DIR = "ad-cleaner-backups";
    private static final DateTimeFormatter BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final BookFileRepository bookFileRepository;
    private final AppProperties appProperties;

    /** EPUB files of books that aren't deleted, all of them or only those of {@code bookIds}. */
    public List<BookFileEntity> candidates(Collection<Long> bookIds) {
        return bookFileRepository.findAllWithBookAndLibraryPathByBookTypeIn(List.of(BookFileType.EPUB)).stream()
                .filter(BookFileEntity::isBookFormat)
                .filter(file -> !Boolean.TRUE.equals(file.getBook().getDeleted()))
                .filter(file -> bookIds == null || bookIds.isEmpty() || bookIds.contains(file.getBook().getId()))
                .toList();
    }

    public EpubAdCleaner.Findings scan(BookFileEntity bookFile) throws IOException {
        return EpubAdCleaner.scan(bookFile.getFullFilePath());
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
        return new Cleaned(findings, backup);
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
}
