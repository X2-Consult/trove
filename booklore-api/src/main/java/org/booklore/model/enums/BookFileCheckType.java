package org.booklore.model.enums;

import lombok.Getter;

/**
 * A check the file tasks make on each book file, recorded in {@code book_file_check}. Bump a check's
 * version when what it looks for changes, so files it passed under the old rules are checked again.
 */
public enum BookFileCheckType {
    /** The OceanofPDF advert, by the Find/Remove Website Ads tasks. */
    EBOOK_ADS(1),
    /** A cover readers can show, by the Find/Fix Missing EPUB Covers tasks. */
    EPUB_COVER(1);

    @Getter
    private final int version;

    BookFileCheckType(int version) {
        this.version = version;
    }
}
