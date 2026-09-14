package org.booklore.repository;

import org.booklore.model.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface BookMetadataRepository extends JpaRepository<BookMetadataEntity, Long> {

    @Query("SELECT m FROM BookMetadataEntity m WHERE m.bookId IN :bookIds")
    List<BookMetadataEntity> getMetadataForBookIds(@Param("bookIds") List<Long> bookIds);

    @Modifying
    @Transactional
    @Query("UPDATE BookMetadataEntity m SET m.coverUpdatedOn = :timestamp WHERE m.bookId = :bookId")
    void updateCoverTimestamp(@Param("bookId") Long bookId, @Param("timestamp") Instant timestamp);

    @Modifying
    @Transactional
    @Query("UPDATE BookMetadataEntity m SET m.audiobookCoverUpdatedOn = :timestamp WHERE m.bookId = :bookId")
    void updateAudiobookCoverTimestamp(@Param("bookId") Long bookId, @Param("timestamp") Instant timestamp);

    List<BookMetadataEntity> findAllByAuthorsContaining(AuthorEntity author);

    List<BookMetadataEntity> findAllByCategoriesContaining(CategoryEntity category);

    List<BookMetadataEntity> findAllByMoodsContaining(MoodEntity mood);

    List<BookMetadataEntity> findAllByTagsContaining(TagEntity tag);

    List<BookMetadataEntity> findAllBySeriesNameIgnoreCase(String seriesName);

    List<BookMetadataEntity> findAllByPublisherIgnoreCase(String publisher);

    List<BookMetadataEntity> findAllByLanguageIgnoreCase(String language);

    /** Books with a readable file but no page count, whose page count isn't locked. Audiobooks have no pages. */
    @Query("""
            SELECT m.bookId FROM BookMetadataEntity m JOIN m.book b
            WHERE (b.deleted IS NULL OR b.deleted = false)
              AND (m.pageCount IS NULL OR m.pageCount <= 0)
              AND (m.pageCountLocked IS NULL OR m.pageCountLocked = false)
              AND EXISTS (SELECT f.id FROM BookFileEntity f WHERE f.book = b AND f.isBookFormat = true
                          AND f.bookType <> org.booklore.model.enums.BookFileType.AUDIOBOOK)
            ORDER BY m.bookId
            """)
    List<Long> findBookIdsMissingPageCount();

    @Query("""
            SELECT COUNT(m) FROM BookMetadataEntity m JOIN m.book b
            WHERE (b.deleted IS NULL OR b.deleted = false)
              AND (m.pageCount IS NULL OR m.pageCount <= 0)
              AND (m.pageCountLocked IS NULL OR m.pageCountLocked = false)
              AND EXISTS (SELECT f.id FROM BookFileEntity f WHERE f.book = b AND f.isBookFormat = true
                          AND f.bookType <> org.booklore.model.enums.BookFileType.AUDIOBOOK)
            """)
    long countBooksMissingPageCount();

    @Query("SELECT m.pageCount FROM BookMetadataEntity m WHERE m.bookId = :bookId")
    Integer findPageCount(@Param("bookId") Long bookId);

    /** Books whose page count came from metadata, with an EPUB or FB2 to measure words per page against. */
    @Query("""
            SELECT m.bookId FROM BookMetadataEntity m JOIN m.book b
            WHERE (b.deleted IS NULL OR b.deleted = false)
              AND m.pageCount > 10
              AND m.pageCountEstimated = false
              AND EXISTS (SELECT f.id FROM BookFileEntity f WHERE f.book = b AND f.isBookFormat = true
                          AND f.bookType IN (org.booklore.model.enums.BookFileType.EPUB, org.booklore.model.enums.BookFileType.FB2))
            ORDER BY m.bookId
            """)
    List<Long> findBookIdsWithKnownPageCountAndText();

    /** Fills in a worked-out page count, unless one has appeared or the field was locked meanwhile. */
    @Modifying
    @Transactional
    @Query("""
            UPDATE BookMetadataEntity m SET m.pageCount = :pageCount, m.pageCountEstimated = :estimated
            WHERE m.bookId = :bookId
              AND (m.pageCount IS NULL OR m.pageCount <= 0)
              AND (m.pageCountLocked IS NULL OR m.pageCountLocked = false)
            """)
    int fillMissingPageCount(@Param("bookId") Long bookId, @Param("pageCount") int pageCount, @Param("estimated") boolean estimated);
}
