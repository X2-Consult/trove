package org.booklore.service.metadata;

import lombok.RequiredArgsConstructor;
import org.booklore.model.dto.BookIdentifierRow;
import org.booklore.model.dto.IdentifierConflictGroup;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.repository.BookMetadataRepository;
import org.booklore.service.metadata.parser.TitleMatcher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Catches one book's ASIN or ISBN landing on a different book: a series bundle's ASIN copied onto
 * each volume, or one search result reused for several books. Clients such as Bookshelf treat a
 * shared identifier as "another copy of the same book", so these merge distinct books downstream.
 *
 * Two books may share an identifier when they're the same work: matching Goodreads IDs or, when
 * either has none, matching titles.
 */
@Service
@RequiredArgsConstructor
public class IdentifierConflictService {

    private final BookMetadataRepository bookMetadataRepository;

    /** Books in the same library, other than this one, that already hold {@code identifier} and aren't the same work. */
    public List<BookMetadataEntity> findConflicts(BookMetadataEntity metadata, String identifier) {
        if (identifier == null || identifier.isBlank() || metadata.getBook() == null || metadata.getBook().getLibrary() == null) {
            return List.of();
        }
        Long libraryId = metadata.getBook().getLibrary().getId();
        Long bookId = metadata.getBookId() != null ? metadata.getBookId() : -1L;
        return bookMetadataRepository.findOthersInLibraryWithIdentifier(libraryId, bookId, identifier.strip().toUpperCase(Locale.ROOT))
                .stream()
                .filter(other -> !isSameWork(metadata.getGoodreadsId(), metadata.getTitle(), other.getGoodreadsId(), other.getTitle()))
                .toList();
    }

    /**
     * Clears any ASIN/ISBN on a newly imported book that a different book in the library already
     * holds, so identifiers embedded in a file (often a bundle's, in KU volumes) can't merge books
     * downstream. Returns a description of each identifier dropped.
     */
    public List<String> dropConflictingIdentifiers(BookMetadataEntity metadata) {
        List<String> dropped = new ArrayList<>();
        dropIfConflicting(metadata, "ASIN", metadata.getAsin(), () -> metadata.setAsin(null), dropped);
        dropIfConflicting(metadata, "ISBN-13", metadata.getIsbn13(), () -> metadata.setIsbn13(null), dropped);
        dropIfConflicting(metadata, "ISBN-10", metadata.getIsbn10(), () -> metadata.setIsbn10(null), dropped);
        return dropped;
    }

    private void dropIfConflicting(BookMetadataEntity metadata, String label, String value, Runnable clear, List<String> dropped) {
        if (value == null || value.isBlank()) return;
        List<BookMetadataEntity> conflicts = findConflicts(metadata, value);
        if (conflicts.isEmpty()) return;
        clear.run();
        dropped.add(label + " " + value + " (held by " + describe(conflicts) + ")");
    }

    /** Every identifier currently shared by different works within a library, largest groups first. */
    @Transactional(readOnly = true)
    public List<IdentifierConflictGroup> findAllConflicts() {
        Map<String, List<BookIdentifierRow>> byLibraryAndIdentifier = new HashMap<>();
        for (BookIdentifierRow row : bookMetadataRepository.findIdentifierRows()) {
            Stream.of(row.asin(), row.isbn13(), row.isbn10())
                    .filter(v -> v != null && !v.isBlank())
                    .map(v -> v.strip().toUpperCase(Locale.ROOT))
                    .distinct()
                    .forEach(v -> byLibraryAndIdentifier.computeIfAbsent(row.libraryId() + "|" + v, k -> new ArrayList<>()).add(row));
        }

        List<IdentifierConflictGroup> groups = new ArrayList<>();
        byLibraryAndIdentifier.forEach((key, rows) -> {
            if (rows.size() < 2 || !hasDifferentWorks(rows)) return;
            String identifier = key.substring(key.indexOf('|') + 1);
            List<BookIdentifierRow> sorted = rows.stream().sorted(Comparator.comparing(BookIdentifierRow::bookId)).toList();
            groups.add(new IdentifierConflictGroup(identifier, rows.getFirst().libraryId(), rows.getFirst().libraryName(), sorted));
        });
        groups.sort(Comparator.comparingInt((IdentifierConflictGroup g) -> g.books().size()).reversed()
                .thenComparing(IdentifierConflictGroup::libraryName, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(IdentifierConflictGroup::identifier));
        return groups;
    }

    private static boolean hasDifferentWorks(List<BookIdentifierRow> rows) {
        for (int i = 0; i < rows.size(); i++) {
            for (int j = i + 1; j < rows.size(); j++) {
                BookIdentifierRow a = rows.get(i), b = rows.get(j);
                if (!isSameWork(a.goodreadsId(), a.title(), b.goodreadsId(), b.title())) return true;
            }
        }
        return false;
    }

    static boolean isSameWork(String goodreadsA, String titleA, String goodreadsB, String titleB) {
        String grA = goodreadsNumber(goodreadsA), grB = goodreadsNumber(goodreadsB);
        if (grA != null && grB != null) {
            return grA.equals(grB);
        }
        return TitleMatcher.matches(titleA, titleB);
    }

    // Goodreads IDs are stored as "12345", "12345-slug" or "12345.Slug"; only the number identifies the book.
    private static String goodreadsNumber(String goodreadsId) {
        if (goodreadsId == null || goodreadsId.isBlank()) return null;
        String number = goodreadsId.strip().split("[-.]")[0];
        return number.isEmpty() ? null : number;
    }

    /** "#7426 Rock God: Part Two" style labels for log and notification messages. */
    static String describe(Collection<BookMetadataEntity> books) {
        return books.stream()
                .limit(3)
                .map(b -> "#" + b.getBookId() + " " + b.getTitle())
                .collect(Collectors.joining(", "))
                + (books.size() > 3 ? " and " + (books.size() - 3) + " more" : "");
    }
}
