package org.booklore.repository;

import org.booklore.model.dto.BookIdentifierRow;
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

    /**
     * Books with no reviews whose reviews aren't locked and that weren't asked about since
     * {@code askedBefore}, never-asked books first.
     */
    @Query("""
            SELECT m.bookId FROM BookMetadataEntity m JOIN m.book b
            WHERE (b.deleted IS NULL OR b.deleted = false)
              AND (m.reviewsLocked IS NULL OR m.reviewsLocked = false)
              AND (m.reviewsFetchedAt IS NULL OR m.reviewsFetchedAt < :askedBefore)
              AND NOT EXISTS (SELECT r.id FROM BookReviewEntity r WHERE r.bookMetadata = m)
            ORDER BY CASE WHEN m.reviewsFetchedAt IS NULL THEN 0 ELSE 1 END, m.reviewsFetchedAt, m.bookId
            """)
    List<Long> findBookIdsMissingReviews(@Param("askedBefore") Instant askedBefore);

    @Query("""
            SELECT COUNT(m) FROM BookMetadataEntity m JOIN m.book b
            WHERE (b.deleted IS NULL OR b.deleted = false)
              AND (m.reviewsLocked IS NULL OR m.reviewsLocked = false)
              AND NOT EXISTS (SELECT r.id FROM BookReviewEntity r WHERE r.bookMetadata = m)
            """)
    long countBooksMissingReviews();

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
    /**
     * Other live books in the library holding {@code value} as their ASIN, ISBN-13 or ISBN-10.
     * Any column counts, because a book's ISBN-10 doubles as its ASIN on Amazon.
     */
    @Query("""
            SELECT m FROM BookMetadataEntity m JOIN m.book b
            WHERE b.library.id = :libraryId AND m.bookId <> :bookId
              AND (b.deleted IS NULL OR b.deleted = false)
              AND (UPPER(m.asin) = :value OR UPPER(m.isbn13) = :value OR UPPER(m.isbn10) = :value)
            """)
    List<BookMetadataEntity> findOthersInLibraryWithIdentifier(@Param("libraryId") Long libraryId,
                                                               @Param("bookId") Long bookId,
                                                               @Param("value") String value);

    /** Identifier fields of every live book that has an ASIN or ISBN, for the duplicate-identifier report. */
    @Query("""
            SELECT new org.booklore.model.dto.BookIdentifierRow(
                   m.bookId, b.library.id, b.library.name, m.title, m.seriesName, m.seriesNumber,
                   m.goodreadsId, m.asin, m.isbn13, m.isbn10)
            FROM BookMetadataEntity m JOIN m.book b
            WHERE (b.deleted IS NULL OR b.deleted = false)
              AND (m.asin IS NOT NULL OR m.isbn13 IS NOT NULL OR m.isbn10 IS NOT NULL)
            """)
    List<BookIdentifierRow> findIdentifierRows();
}
