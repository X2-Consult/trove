package org.booklore.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.booklore.model.enums.BookFileCheckResult;
import org.booklore.model.enums.BookFileCheckType;

import java.time.Instant;

/** The last result of one check on one book file, and the file hash it was made against. */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "book_file_check", uniqueConstraints = @UniqueConstraint(columnNames = {"book_file_id", "check_type"}))
public class BookFileCheckEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "book_file_id", nullable = false)
    private Long bookFileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_type", nullable = false, length = 32)
    private BookFileCheckType checkType;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 32)
    private BookFileCheckResult result;

    @Column(name = "file_hash", length = 128)
    private String fileHash;

    @Column(name = "check_version", nullable = false)
    private int checkVersion;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;
}
