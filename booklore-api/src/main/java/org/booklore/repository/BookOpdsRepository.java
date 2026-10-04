package org.booklore.repository;

import org.booklore.model.entity.AuthorEntity;
import org.booklore.model.entity.BookEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface BookOpdsRepository extends JpaRepository<BookEntity, Long>, JpaSpecificationExecutor<BookEntity> {

    // ============================================
    // ALL BOOKS - Two Query Pattern
    // ============================================

    @Query("SELECT b.id FROM BookEntity b WHERE (b.deleted IS NULL OR b.deleted = false) ORDER BY b.addedOn DESC, b.id DESC")
    Page<Long> findBookIds(Pageable pageable);

    @EntityGraph(attributePaths = {"metadata", "bookFiles", "shelves"})
    @Query("SELECT b FROM BookEntity b WHERE b.id IN :ids AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllWithMetadataByIds(@Param("ids") Collection<Long> ids);

    // ============================================
    // RECENT BOOKS - Two Query Pattern
    // ============================================

    @Query("SELECT b.id FROM BookEntity b WHERE (b.deleted IS NULL OR b.deleted = false) ORDER BY b.addedOn DESC, b.id DESC")
    Page<Long> findRecentBookIds(Pageable pageable);

    // Uses same findAllWithMetadataByIds for second query

    // ============================================
    // BOOKS BY LIBRARY IDs - Two Query Pattern
    // ============================================

    @Query("SELECT b.id FROM BookEntity b WHERE b.library.id IN :libraryIds AND (b.deleted IS NULL OR b.deleted = false) ORDER BY b.addedOn DESC, b.id DESC")
    Page<Long> findBookIdsByLibraryIds(@Param("libraryIds") Collection<Long> libraryIds, Pageable pageable);

    @EntityGraph(attributePaths = {"metadata", "bookFiles", "shelves"})
    @Query("SELECT b FROM BookEntity b WHERE b.id IN :ids AND b.library.id IN :libraryIds AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllWithMetadataByIdsAndLibraryIds(@Param("ids") Collection<Long> ids, @Param("libraryIds") Collection<Long> libraryIds);

    // ============================================
    // RECENT BOOKS BY LIBRARY IDs - Two Query Pattern
    // ============================================

    @Query("SELECT b.id FROM BookEntity b WHERE b.library.id IN :libraryIds AND (b.deleted IS NULL OR b.deleted = false) ORDER BY b.addedOn DESC, b.id DESC")
    Page<Long> findRecentBookIdsByLibraryIds(@Param("libraryIds") Collection<Long> libraryIds, Pageable pageable);

    // Uses findAllWithMetadataByIdsAndLibraryIds for second query

    // Every ORDER BY ends on b.id: books added in the same import share addedOn, and without a unique
    // last key the database may order them differently per page, so OPDS paging repeats or skips them.

    // ============================================
    // BOOKS BY SHELF ID - Two Query Pattern
    // ============================================

    // book.shelves is @ManyToMany, so the join can genuinely fan a book into multiple rows -
    // DISTINCT is needed. Deduping in an inner subquery (rather than "SELECT DISTINCT b.id
    // ... ORDER BY b.addedOn") keeps the ORDER BY column out of a DISTINCT projection, which
    // PostgreSQL requires; see the note on findBookIdsBySeriesName.
    @Query("""
            SELECT b.id FROM BookEntity b
            WHERE b.id IN (
                SELECT DISTINCT b2.id FROM BookEntity b2 JOIN b2.shelves s
                WHERE s.id = :shelfId AND (b2.deleted IS NULL OR b2.deleted = false)
            )
            ORDER BY b.addedOn DESC, b.id DESC
            """)
    Page<Long> findBookIdsByShelfId(@Param("shelfId") Long shelfId, Pageable pageable);

    @EntityGraph(attributePaths = {"metadata", "bookFiles", "shelves"})
    @Query("SELECT DISTINCT b FROM BookEntity b JOIN b.shelves s WHERE b.id IN :ids AND s.id = :shelfId AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllWithMetadataByIdsAndShelfId(@Param("ids") Collection<Long> ids, @Param("shelfId") Long shelfId);

    // ============================================
    // SEARCH BY METADATA - Two Query Pattern
    // ============================================

    // book.metadata is a @OneToOne - see the DISTINCT/ORDER BY note on findBookIdsBySeriesName.
    @Query("""
            SELECT b.id FROM BookEntity b
            LEFT JOIN b.metadata m
            WHERE (b.deleted IS NULL OR b.deleted = false) AND (
                  m.searchText LIKE CONCAT('%', :text, '%')
            )
            ORDER BY b.addedOn DESC, b.id DESC
            """)
    Page<Long> findBookIdsByMetadataSearch(@Param("text") String text, Pageable pageable);

    @EntityGraph(attributePaths = {"metadata", "metadata.authors", "metadata.categories", "bookFiles", "shelves"})
    @Query("SELECT DISTINCT b FROM BookEntity b WHERE b.id IN :ids AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllWithFullMetadataByIds(@Param("ids") Collection<Long> ids);

    // ============================================
    // SEARCH BY METADATA IN LIBRARIES - Two Query Pattern
    // ============================================

    @Query("""
            SELECT b.id FROM BookEntity b
            LEFT JOIN b.metadata m
            WHERE (b.deleted IS NULL OR b.deleted = false)
              AND b.library.id IN :libraryIds
              AND (
                  m.searchText LIKE CONCAT('%', :text, '%')
              )
            ORDER BY b.addedOn DESC, b.id DESC
            """)
    Page<Long> findBookIdsByMetadataSearchAndLibraryIds(@Param("text") String text, @Param("libraryIds") Collection<Long> libraryIds, Pageable pageable);

    @EntityGraph(attributePaths = {"metadata", "metadata.authors", "metadata.categories", "bookFiles", "shelves"})
    @Query("SELECT DISTINCT b FROM BookEntity b WHERE b.id IN :ids AND b.library.id IN :libraryIds AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllWithFullMetadataByIdsAndLibraryIds(@Param("ids") Collection<Long> ids, @Param("libraryIds") Collection<Long> libraryIds);

    // ============================================
    // SEARCH BY METADATA IN SHELVES - Two Query Pattern
    // ============================================
    // Shelf queries also take the user's accessible libraries: a shelf can hold books from libraries
    // the user can no longer see (or never could), and OPDS must not list them (Grimmory 0cc48a8d).

    @Query("""
            SELECT b.id FROM BookEntity b
            WHERE b.id IN (
                SELECT DISTINCT b2.id FROM BookEntity b2
                LEFT JOIN b2.metadata m
                JOIN b2.shelves s
                WHERE (b2.deleted IS NULL OR b2.deleted = false)
                  AND b2.library.id IN :libraryIds
                  AND s.id IN :shelfIds
                  AND (
                      m.searchText LIKE CONCAT('%', :text, '%')
                  )
            )
            ORDER BY b.addedOn DESC, b.id DESC
            """)
    Page<Long> findBookIdsByMetadataSearchAndShelfIds(@Param("text") String text, @Param("libraryIds") Collection<Long> libraryIds, @Param("shelfIds") Collection<Long> shelfIds, Pageable pageable);

    @EntityGraph(attributePaths = {"metadata", "metadata.authors", "metadata.categories", "bookFiles", "shelves"})
    @Query("SELECT DISTINCT b FROM BookEntity b JOIN b.shelves s WHERE b.id IN :ids AND b.library.id IN :libraryIds AND s.id IN :shelfIds AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllWithFullMetadataByIdsAndShelfIds(@Param("ids") Collection<Long> ids, @Param("libraryIds") Collection<Long> libraryIds, @Param("shelfIds") Collection<Long> shelfIds);

    // ============================================
    // BOOKS BY SHELF IDs - Two Query Pattern
    // ============================================

    @Query("""
            SELECT b.id FROM BookEntity b
            WHERE b.id IN (
                SELECT DISTINCT b2.id FROM BookEntity b2 JOIN b2.shelves s
                WHERE s.id IN :shelfIds AND b2.library.id IN :libraryIds AND (b2.deleted IS NULL OR b2.deleted = false)
            )
            ORDER BY b.addedOn DESC, b.id DESC
            """)
    Page<Long> findBookIdsByShelfIds(@Param("libraryIds") Collection<Long> libraryIds, @Param("shelfIds") Collection<Long> shelfIds, Pageable pageable);

    @EntityGraph(attributePaths = {"metadata", "bookFiles", "shelves"})
    @Query("SELECT DISTINCT b FROM BookEntity b JOIN b.shelves s WHERE b.id IN :ids AND b.library.id IN :libraryIds AND s.id IN :shelfIds AND (b.deleted IS NULL OR b.deleted = false)")
    List<BookEntity> findAllWithMetadataByIdsAndShelfIds(@Param("ids") Collection<Long> ids, @Param("libraryIds") Collection<Long> libraryIds, @Param("shelfIds") Collection<Long> shelfIds);

    // ============================================
    // RANDOM BOOKS - "Surprise Me" Feed
    // ============================================

    @Query(value = "SELECT b.id FROM BookEntity b WHERE (b.deleted IS NULL OR b.deleted = false) ORDER BY function('RANDOM')", nativeQuery = false)
    List<Long> findRandomBookIds();

    @Query(value = "SELECT b.id FROM BookEntity b WHERE b.library.id IN :libraryIds AND (b.deleted IS NULL OR b.deleted = false) ORDER BY function('RANDOM')", nativeQuery = false)
    List<Long> findRandomBookIdsByLibraryIds(@Param("libraryIds") Collection<Long> libraryIds);

    // ============================================
    // AUTHORS - Distinct Authors List
    // ============================================

    @Query("""
            SELECT DISTINCT a FROM AuthorEntity a
            JOIN a.bookMetadataEntityList m
            JOIN m.book b
            WHERE (b.deleted IS NULL OR b.deleted = false)
            ORDER BY a.name
            """)
    List<AuthorEntity> findDistinctAuthors();

    @Query("""
            SELECT DISTINCT a FROM AuthorEntity a
            JOIN a.bookMetadataEntityList m
            JOIN m.book b
            WHERE (b.deleted IS NULL OR b.deleted = false)
              AND b.library.id IN :libraryIds
            ORDER BY a.name
            """)
    List<AuthorEntity> findDistinctAuthorsByLibraryIds(@Param("libraryIds") Collection<Long> libraryIds);

    // ============================================
    // BOOKS BY AUTHOR - Two Query Pattern
    // ============================================

    // metadata.authors is @ManyToMany, so it can fan out - same subquery-dedup reasoning
    // as findBookIdsByShelfId.
    @Query("""
            SELECT b.id FROM BookEntity b
            WHERE b.id IN (
                SELECT DISTINCT b2.id FROM BookEntity b2
                JOIN b2.metadata m
                JOIN m.authors a
                WHERE a.name = :authorName
                  AND (b2.deleted IS NULL OR b2.deleted = false)
            )
            ORDER BY b.addedOn DESC, b.id DESC
            """)
    Page<Long> findBookIdsByAuthorName(@Param("authorName") String authorName, Pageable pageable);

    @Query("""
            SELECT b.id FROM BookEntity b
            WHERE b.id IN (
                SELECT DISTINCT b2.id FROM BookEntity b2
                JOIN b2.metadata m
                JOIN m.authors a
                WHERE a.name = :authorName
                  AND b2.library.id IN :libraryIds
                  AND (b2.deleted IS NULL OR b2.deleted = false)
            )
            ORDER BY b.addedOn DESC, b.id DESC
            """)
    Page<Long> findBookIdsByAuthorNameAndLibraryIds(@Param("authorName") String authorName, @Param("libraryIds") Collection<Long> libraryIds, Pageable pageable);

    // ============================================
    // SERIES - Distinct Series List (with cover info for navigation feed)
    // ============================================

    // One representative book per series (the one that would sort first within the
    // series) so the series navigation entry can carry a cover image. DISTINCT ON is
    // Postgres-specific, which matches this project's production database.
    @Query(value = """
            SELECT DISTINCT ON (m.series_name)
                m.series_name AS seriesName,
                m.book_id AS bookId,
                m.cover_updated_on AS coverUpdatedOn
            FROM book_metadata m
            JOIN book b ON b.id = m.book_id
            WHERE (b.deleted IS NULL OR b.deleted = false)
              AND m.series_name IS NOT NULL
              AND m.series_name != ''
            ORDER BY m.series_name, COALESCE(m.series_number, 999999), b.added_on DESC, b.id DESC
            """, nativeQuery = true)
    List<SeriesCoverProjection> findDistinctSeriesWithCover();

    @Query(value = """
            SELECT DISTINCT ON (m.series_name)
                m.series_name AS seriesName,
                m.book_id AS bookId,
                m.cover_updated_on AS coverUpdatedOn
            FROM book_metadata m
            JOIN book b ON b.id = m.book_id
            WHERE (b.deleted IS NULL OR b.deleted = false)
              AND b.library_id IN :libraryIds
              AND m.series_name IS NOT NULL
              AND m.series_name != ''
            ORDER BY m.series_name, COALESCE(m.series_number, 999999), b.added_on DESC, b.id DESC
            """, nativeQuery = true)
    List<SeriesCoverProjection> findDistinctSeriesWithCoverByLibraryIds(@Param("libraryIds") Collection<Long> libraryIds);

    interface SeriesCoverProjection {
        String getSeriesName();
        Long getBookId();
        java.time.Instant getCoverUpdatedOn();
    }

    // ============================================
    // BOOKS BY SERIES - Two Query Pattern (sorted by series number)
    // ============================================

    // book.metadata is a @OneToOne, so this join can never fan out a book into
    // multiple rows - DISTINCT is unnecessary here and was actively harmful: on
    // PostgreSQL, "SELECT DISTINCT x ... ORDER BY y" where y isn't in the SELECT
    // list is a hard error (42601), which is what was 500-ing this endpoint.
    @Query("""
            SELECT b.id FROM BookEntity b
            JOIN b.metadata m
            WHERE m.seriesName = :seriesName
              AND (b.deleted IS NULL OR b.deleted = false)
            ORDER BY COALESCE(m.seriesNumber, 999999), b.addedOn DESC, b.id DESC
            """)
    Page<Long> findBookIdsBySeriesName(@Param("seriesName") String seriesName, Pageable pageable);

    @Query("""
            SELECT b.id FROM BookEntity b
            JOIN b.metadata m
            WHERE m.seriesName = :seriesName
              AND b.library.id IN :libraryIds
              AND (b.deleted IS NULL OR b.deleted = false)
            ORDER BY COALESCE(m.seriesNumber, 999999), b.addedOn DESC, b.id DESC
            """)
    Page<Long> findBookIdsBySeriesNameAndLibraryIds(@Param("seriesName") String seriesName, @Param("libraryIds") Collection<Long> libraryIds, Pageable pageable);
}