package org.booklore.repository;

import org.booklore.model.entity.BookFileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BookAdditionalFileRepository extends JpaRepository<BookFileEntity, Long> {

    List<BookFileEntity> findByBookId(Long bookId);

    List<BookFileEntity> findByBookIdAndIsBookFormat(Long bookId, boolean isBookFormat);

    Optional<BookFileEntity> findByAltFormatCurrentHash(String altFormatCurrentHash);

    @Query("SELECT bf FROM BookFileEntity bf WHERE bf.book.libraryPath.id = :libraryPathId AND bf.fileSubPath = :fileSubPath AND bf.fileName = :fileName")
    Optional<BookFileEntity> findByLibraryPath_IdAndFileSubPathAndFileName(@Param("libraryPathId") Long libraryPathId,
                                                                           @Param("fileSubPath") String fileSubPath,
                                                                           @Param("fileName") String fileName);

    @Query("SELECT bf FROM BookFileEntity bf WHERE bf.book.library.id = :libraryId")
    List<BookFileEntity> findByLibraryId(@Param("libraryId") Long libraryId);

    @Modifying
    @Query("""
            UPDATE BookFileEntity bf SET
                bf.fileName = :fileName,
                bf.fileSubPath = :fileSubPath
            WHERE bf.id = :bookFileId
            """)
    void updateFileNameAndSubPath(
            @Param("bookFileId") Long bookFileId,
            @Param("fileName") String fileName,
            @Param("fileSubPath") String fileSubPath);

    /**
     * Keeps the files' copy of the library folder in step with a bulk move of their book: the
     * entity hook that normally does it doesn't run for query updates, and a file left pointing at
     * the old folder is deleted along with it if that folder or its library is removed.
     */
    @Modifying
    @Query("UPDATE BookFileEntity bf SET bf.libraryPathId = :libraryPathId WHERE bf.book.id = :bookId")
    void updateLibraryPathIdByBookId(@Param("bookId") Long bookId, @Param("libraryPathId") Long libraryPathId);
}
