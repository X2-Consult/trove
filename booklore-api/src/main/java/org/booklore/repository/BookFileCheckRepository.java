package org.booklore.repository;

import org.booklore.model.entity.BookFileCheckEntity;
import org.booklore.model.enums.BookFileCheckResult;
import org.booklore.model.enums.BookFileCheckType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface BookFileCheckRepository extends JpaRepository<BookFileCheckEntity, Long> {

    Optional<BookFileCheckEntity> findByBookFileIdAndCheckType(Long bookFileId, BookFileCheckType checkType);

    @Query("""
            SELECT c FROM BookFileCheckEntity c
            WHERE c.checkType = :checkType AND c.checkVersion = :version AND c.result IN :results
            """)
    List<BookFileCheckEntity> findByTypeVersionAndResults(@Param("checkType") BookFileCheckType checkType,
                                                          @Param("version") int version,
                                                          @Param("results") Collection<BookFileCheckResult> results);
}
