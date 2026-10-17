package org.booklore.model.dto;

/** The identifier fields of one book, as read for the duplicate-identifier report. */
public record BookIdentifierRow(Long bookId, Long libraryId, String libraryName, String title,
                                String seriesName, Float seriesNumber, String goodreadsId,
                                String asin, String isbn13, String isbn10) {
}
