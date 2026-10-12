package org.booklore.service.file;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.entity.BookFileCheckEntity;
import org.booklore.model.enums.BookFileCheckResult;
import org.booklore.model.enums.BookFileCheckType;
import org.booklore.repository.BookFileCheckRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Remembers what the file-checking tasks found in each book file, so a later run can skip the files
 * already found fine or fixed. A skipped file must still have the hash it was checked at: anything
 * that rewrites a book file records its new hash, so a changed file is checked again.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookFileCheckService {

    private final BookFileCheckRepository repository;

    /** The files whose last {@code type} check, under its current version, needs no follow-up. */
    public Settled settled(BookFileCheckType type) {
        Map<Long, String> hashes = new HashMap<>();
        var results = Arrays.stream(BookFileCheckResult.values()).filter(BookFileCheckResult::isSettled).toList();
        for (BookFileCheckEntity check : repository.findByTypeVersionAndResults(type, type.getVersion(), results)) {
            hashes.put(check.getBookFileId(), check.getFileHash());
        }
        return new Settled(hashes);
    }

    /**
     * Records a check's result against the file's current hash. A failure is only logged: the record
     * just saves work next time, it mustn't fail the check itself.
     */
    public void record(BookFileCheckType type, Long bookFileId, String fileHash, BookFileCheckResult result) {
        try {
            BookFileCheckEntity check = repository.findByBookFileIdAndCheckType(bookFileId, type)
                    .orElseGet(() -> BookFileCheckEntity.builder().bookFileId(bookFileId).checkType(type).build());
            check.setResult(result);
            check.setFileHash(fileHash);
            check.setCheckVersion(type.getVersion());
            check.setCheckedAt(Instant.now());
            repository.save(check);
        } catch (Exception e) {
            log.warn("Could not record the {} check of book file {}: {}", type, bookFileId, e.getMessage());
        }
    }

    /** Book files that can be skipped, by id, with the hash each was checked at. */
    public record Settled(Map<Long, String> hashes) {

        /** True if the file was found fine or fixed and its hash hasn't changed since. */
        public boolean covers(Long bookFileId, String currentHash) {
            return currentHash != null && Objects.equals(hashes.get(bookFileId), currentHash);
        }
    }
}
