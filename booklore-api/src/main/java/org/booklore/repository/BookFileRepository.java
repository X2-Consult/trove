package org.booklore.repository;

import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface BookFileRepository extends JpaRepository<BookFileEntity, Long> {

    @Query("SELECT bf FROM BookFileEntity bf JOIN FETCH bf.book b JOIN FETCH b.libraryPath WHERE bf.bookType IN :bookTypes")
    List<BookFileEntity> findAllWithBookAndLibraryPathByBookTypeIn(@Param("bookTypes") Collection<BookFileType> bookTypes);

    @Query("""
            SELECT bf FROM BookFileEntity bf
            WHERE bf.book.libraryPath.id = :libraryPathId
            AND bf.fileSubPath = :fileSubPath
            AND bf.fileName = :fileName
            """)
    Optional<BookFileEntity> findByLibraryPathIdAndFileSubPathAndFileName(
            @Param("libraryPathId") Long libraryPathId,
            @Param("fileSubPath") String fileSubPath,
            @Param("fileName") String fileName);

    @Query("SELECT COUNT(bf) FROM BookFileEntity bf WHERE bf.book.id = :bookId")
    long countByBookId(@Param("bookId") Long bookId);

    @Query("SELECT bf FROM BookFileEntity bf WHERE bf.currentHash = :currentHash AND bf.isBookFormat = true")
    Optional<BookFileEntity> findByCurrentHashAndIsBookFormatTrue(@Param("currentHash") String currentHash);

    @Query("""
            SELECT DISTINCT bf.book.id FROM BookFileEntity bf
            WHERE bf.bookType = org.booklore.model.enums.BookFileType.EPUB AND bf.isBookFormat = true
              AND (bf.book.deleted IS NULL OR bf.book.deleted = false)
            ORDER BY bf.book.id
            """)
    List<Long> findBookIdsWithEpub();

    @Modifying
    @Transactional
    @Query("UPDATE BookFileEntity bf SET bf.currentHash = :currentHash, bf.fileSizeKb = :fileSizeKb WHERE bf.id = :id")
    void updateCurrentHashAndSize(@Param("id") Long id, @Param("currentHash") String currentHash, @Param("fileSizeKb") Long fileSizeKb);
}
