package org.booklore.model.dto;

import java.util.List;

/**
 * An ASIN or ISBN held by more than one book in a library where at least two of those books
 * aren't the same work (different Goodreads IDs, or titles that don't match).
 */
public record IdentifierConflictGroup(String identifier, Long libraryId, String libraryName, List<BookIdentifierRow> books) {
}
